// B. The Back chain and the Exit key. From every screen and overlay, Back
// alone must walk home and then to the exit prompt, never out of the app;
// only 離開 or the Exit key (10182) leaves. tizen.application is stubbed so
// "left the app" is observable: getCurrentApplication().exit() counts.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { fakePairing, hermeticTitle, installProbes, mockPlayer, seedFavorites } from "../lib/qa2.js";

test.beforeEach(async ({ page }) => { await fakePairing(page); });

async function stubTizen(page) {
  await page.addInitScript(() => {
    window.__exits = 0;
    window.close = () => { window.__closes = (window.__closes || 0) + 1; };
    window.tizen = window.tizen || {};
    window.tizen.application = { getCurrentApplication: () => ({ exit: () => { window.__exits += 1; } }) };
  });
}
const exits = (page) => page.evaluate(() => (window.__exits || 0) + (window.__closes || 0));
const where = (page) => page.evaluate(() => {
  const s = [...document.querySelectorAll("[data-screen]")].map((e) => e.dataset.screen);
  return s.includes("exit") ? "exit" : s[0] || null;
});

/** Press Back until the exit prompt shows; returns the screens passed through. */
async function backToExit(page, remote, { max = 12 } = {}) {
  const trail = [await where(page)];
  for (let i = 0; i < max; i += 1) {
    await remote.back({ gap: 450 });
    const w = await where(page);
    if (w !== trail[trail.length - 1]) trail.push(w);
    expect(await exits(page), `Back #${i + 1} must not leave the app (trail ${trail.join(" → ")})`).toBe(0);
    if (w === "exit") return { trail, presses: i + 1 };
  }
  throw new Error(`exit prompt never reached; trail ${trail.join(" → ")}`);
}

async function openHermeticPlayer(page, remote, hermetic) {
  await expect(screen(page, "home")).toBeVisible();
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
  await remote.enter();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
  await page.keyboard.type(hermetic.opts.query);
  await remote.enter();
  const card = page.locator(`[data-provider="${hermetic.opts.provider}"] [data-focusable]`).first();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await focusTo(page, remote, `[data-provider="${hermetic.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await remote.enter();
  await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 20_000 });
  await expect.poll(async () => (await page.locator('[data-action="play"]').innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  const player = screen(page, "player");
  await expect(player).toBeVisible();
  await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 20_000 }).toBeGreaterThan(0);
  return player;
}

test("Back from the player walks Player → Detail → Search → Home → exit prompt, never leaving", async ({ page, seedSession, remote, shot }, testInfo) => {
  test.setTimeout(150_000);
  await stubTizen(page);
  await mockPlayer(page, { mockDuration: 3000 });
  const h = await hermeticTitle(page);
  await seedSession();
  await page.goto("/");
  const player = await openHermeticPlayer(page, remote, h);
  // Controls up first, so the chain includes "Back hides controls".
  await remote.up();
  await expect(player).toHaveAttribute("data-controls", "visible");
  await shot("player-controls");
  const { trail, presses } = await backToExit(page, remote);
  await shot("exit-prompt");
  await testInfo.attach("back-trail.txt", { body: `${trail.join(" → ")} in ${presses} presses`, contentType: "text/plain" });
  console.log(`[back chain] ${trail.join(" → ")} in ${presses} presses`);
  expect(trail).toEqual(["player", "detail", "search", "home", "exit"]);
  expect(presses, "Back presses from player-with-controls to exit prompt").toBeLessThanOrEqual(9);
  // Focus lands on 取消, and Back once more closes the prompt rather than exiting.
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "exit-cancel");
  await remote.back({ gap: 400 });
  await expect(screen(page, "exit")).toBeHidden();
  await expect(screen(page, "home")).toBeVisible();
  expect(await exits(page)).toBe(0);
});

test("Back on Home: twenty presses toggle the prompt and never exit; 離開 exits", async ({ page, seedSession, remote }) => {
  await stubTizen(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await page.waitForTimeout(1500);
  for (let i = 0; i < 20; i += 1) await remote.back({ gap: 150 });
  expect(await exits(page)).toBe(0);
  // 20 presses: whatever state, the app is still here and one exit prompt at most.
  expect(await page.locator('[data-screen="exit"]').count()).toBeLessThanOrEqual(1);
  if (!(await screen(page, "exit").isVisible())) await remote.back({ gap: 300 });
  await expect(screen(page, "exit")).toBeVisible();
  await focusTo(page, remote, '[data-action="exit-confirm"]', { dirs: ["Right", "Left"] });
  await remote.enter();
  await expect.poll(() => exits(page)).toBe(1);
});

test("overlays close on Back and hand focus back: exit prompt, sign-out confirm, long-press menu", async ({ page, seedSession, remote, qaApi, shot }) => {
  test.setTimeout(120_000);
  const favs = await seedFavorites(qaApi, { n: 3 });
  try {
    await stubTizen(page);
    await installProbes(page);
    await seedSession();
    await page.goto("/");
    const card = page.locator('[data-screen="home"] .tv-rows [data-focusable]').first();
    await expect(card).toBeVisible({ timeout: 30_000 });

    // Long-press OK on a favourite opens the menu; Back closes it, focus returns to the card.
    await focusTo(page, remote, '.tv-rows [data-focusable][data-longpress]', { dirs: ["Down", "Right"] });
    const cardKey = await page.evaluate(() => window.__qaKey(document.querySelector(".is-focused")));
    await page.evaluate(() => {
      const ev = new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
      Object.defineProperty(ev, "keyCode", { get: () => 13 });
      (document.activeElement || document.body).dispatchEvent(ev);
    });
    await page.waitForTimeout(900);
    await page.evaluate(() => {
      const ev = new KeyboardEvent("keyup", { key: "Enter", bubbles: true, cancelable: true });
      Object.defineProperty(ev, "keyCode", { get: () => 13 });
      (document.activeElement || document.body).dispatchEvent(ev);
    });
    await expect(page.locator("[data-menu]")).toBeVisible();
    await expect(page.locator(".is-focused")).toHaveAttribute("data-menu-first", "");
    await shot("long-press-menu");
    await remote.back({ gap: 400 });
    await expect(page.locator("[data-menu]")).toHaveCount(0);
    await expect(screen(page, "home")).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.__qaKey(document.querySelector(".is-focused")))).toBe(cardKey);

    // Sign-out confirm: Back answers "no" and focus returns to 登出.
    await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right"], steps: 10 });
    await remote.enter();
    await expect(page.locator('[data-action="signout-cancel"]')).toBeVisible();
    await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "signout-cancel");
    await remote.back({ gap: 400 });
    await expect(page.locator('[data-action="signout-cancel"]')).toHaveCount(0);
    await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "signout");
    expect(await page.evaluate(() => !!localStorage.getItem("streamhub.session"))).toBeTruthy();

    // Exit prompt from the top bar: Back closes it and focus is valid again.
    await remote.back({ gap: 400 });
    if (!(await screen(page, "exit").isVisible())) await remote.back({ gap: 400 });
    await expect(screen(page, "exit")).toBeVisible();
    await remote.back({ gap: 400 });
    await expect(screen(page, "exit")).toBeHidden();
    const a = await page.evaluate(() => window.__qaAudit());
    expect(a.count).toBe(1);
    expect(a.screen).toBe("home");
    expect(await exits(page)).toBe(0);
  } finally {
    await favs.remove();
  }
});

test("player overlays: Back closes sources picker and episode strip, dismisses up-next, leaves from the error panel", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(150_000);
  await stubTizen(page);
  await mockPlayer(page, { mockDuration: 400, mockRate: 1 });
  const h = await hermeticTitle(page, { sources: 2 });
  await seedSession();
  await page.goto("/");
  const player = await openHermeticPlayer(page, remote, h);

  // Episode strip (Down with an episode list).
  await remote.down({ gap: 400 });
  const strip = page.locator('[data-overlay="episodes"]');
  if (await strip.isVisible()) {
    await shot("episode-strip");
    await remote.back({ gap: 400 });
    await expect(strip).toBeHidden();
    await expect(player).toBeVisible();
  } else {
    test.info().annotations.push({ type: "note", description: "Down did not open an episode strip" });
  }

  // Sources picker from the control bar.
  await remote.up({ gap: 300 });
  await expect(player).toHaveAttribute("data-controls", "visible");
  await focusTo(page, remote, '[data-screen="player"] [data-action="sources"]', { dirs: ["Right", "Left"], steps: 8 });
  await remote.enter();
  const picker = page.locator('[data-overlay="sources"]');
  await expect(picker).toBeVisible();
  await shot("sources-picker");
  await remote.back({ gap: 400 });
  await expect(picker).toBeHidden();
  await expect(player).toBeVisible();
  if ((await player.getAttribute("data-controls")) === "visible") await remote.back({ gap: 400 });
  await expect(player).toHaveAttribute("data-controls", "hidden");

  // Up-next: seek near the end (Right ×N, 10 s each) until the card shows.
  const upnext = page.locator('[data-overlay="up-next"]');
  // Seeks are debounced (400 ms): press slower than that, or every press
  // folds into one jump straight past the end.
  for (let i = 0; i < 40 && !(await upnext.isVisible()); i += 1) await remote.right({ gap: 650 });
  await expect(upnext).toBeVisible({ timeout: 10_000 });
  await shot("up-next");
  await remote.back({ gap: 400 });
  await expect(upnext).toBeHidden();
  await expect(player).toBeVisible();
  expect(await exits(page)).toBe(0);
});

test("player error panel: Back leaves to Detail (not out of the app)", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(120_000);
  await stubTizen(page);
  await mockPlayer(page, { mockPlayerFail: "all", mockOpenDelay: 150 });
  const h = await hermeticTitle(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
  await remote.enter();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await remote.enter();
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await expect.poll(async () => (await page.locator('[data-action="play"]').innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  await remote.enter();
  const panel = page.locator('[data-overlay="error"]');
  await expect(panel).toBeVisible({ timeout: 30_000 });
  await shot("error-panel");
  await remote.back({ gap: 400 });
  await expect(screen(page, "detail")).toBeVisible();
  expect(await exits(page)).toBe(0);
});

const EXIT_FROM = ["login", "home", "search", "detail", "player", "exit-prompt"];
for (const from of EXIT_FROM) {
  test(`Exit key (10182) leaves at once from ${from}`, async ({ page, seedSession, remote }) => {
    test.setTimeout(120_000);
    await stubTizen(page);
    await mockPlayer(page, { mockDuration: 3000 });
    const h = await hermeticTitle(page);
    const puts = h.state.puts;
    if (from !== "login") await seedSession();
    await page.goto("/");
    if (from === "login") {
      await expect(screen(page, "login")).toBeVisible();
    } else {
      await expect(screen(page, "home")).toBeVisible();
      if (from === "exit-prompt") {
        await remote.back({ gap: 400 });
        if (!(await screen(page, "exit").isVisible())) await remote.back({ gap: 400 });
        await expect(screen(page, "exit")).toBeVisible();
      }
      if (["search", "detail", "player"].includes(from)) {
        await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
        await remote.enter();
        await expect(screen(page, "search")).toBeVisible();
      }
      if (["detail", "player"].includes(from)) {
        await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
        await page.keyboard.type(h.opts.query);
        await remote.enter();
        await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
        await remote.enter();
        await expect(page.locator('[data-action="play"]')).toBeVisible();
      }
      if (from === "player") {
        await expect.poll(async () => (await page.locator('[data-action="play"]').innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
        await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
        await remote.enter();
        await expect.poll(async () => Number(await screen(page, "player").getAttribute("data-position")), { timeout: 20_000 }).toBeGreaterThan(0);
      }
    }
    await remote.exit({ gap: 300 });
    await expect.poll(() => exits(page)).toBe(1);
    if (from === "player") {
      // The player's last chance to save: a pause report on Exit.
      await expect.poll(() => puts.map((p) => p.body.event)).toContain("pause");
    }
  });
}

test("Back on the Login screen opens the exit prompt rather than leaving", async ({ page, remote }) => {
  await stubTizen(page);
  await page.goto("/");
  await expect(screen(page, "login")).toBeVisible();
  await remote.back({ gap: 400 });
  expect(await exits(page)).toBe(0);
  await expect(screen(page, "exit")).toBeVisible();
  await remote.back({ gap: 400 });
  await expect(screen(page, "exit")).toBeHidden();
  await expect(screen(page, "login")).toBeVisible();
  // Focus is somewhere sensible again, not lost.
  await expect(page.locator(".is-focused")).toHaveCount(1);
});
