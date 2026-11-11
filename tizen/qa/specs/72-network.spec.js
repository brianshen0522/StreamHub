// E. Server unreachable. Every /api request aborted (the "Wi-Fi with no
// internet" case: navigator.onLine stays true) on Home, Search, Detail and
// the Player: a visible, recoverable state and no panic; once the server is
// back, recovery by retry or by navigating.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { hermeticTitle, installProbes, mockPlayer, settledAudit } from "../lib/qa2.js";

/** Registered last, so it wins over every other route while `down`. */
async function outage(page) {
  const o = { down: false, aborted: 0 };
  await page.route(/\/api\/v1\//, (route) => {
    if (!o.down) return route.fallback();
    o.aborted += 1;
    return route.abort("connectionrefused");
  });
  return o;
}

async function openSearch(page, remote) {
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
  await remote.enter();
  await expect(screen(page, "search")).toBeVisible();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
}

test("Home with the server down: captions, usable focus; navigating back after restore reloads", async ({ page, seedSession, remote, shot }) => {
  await installProbes(page);
  const o = await outage(page);
  o.down = true;
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  const captions = page.locator('[data-screen="home"] .tv-row-caption.is-error');
  await expect(captions.first()).toBeVisible({ timeout: 20_000 });
  expect(await captions.allInnerTexts()).toEqual(expect.arrayContaining(["無法連線到伺服器"]));
  const a = await settledAudit(page);
  expect(a.problems, JSON.stringify(a)).toEqual([]);
  await shot("home-offline");

  o.down = false;
  // Does Home come back by itself? (Observation, reported, not required.)
  await page.waitForTimeout(8000);
  const selfHealed = (await captions.count()) === 0;
  console.log(`[offline home] recovered without navigation: ${selfHealed}`);
  test.info().annotations.push({ type: "home-self-heal", description: String(selfHealed) });
  if (!selfHealed) {
    await openSearch(page, remote);
    await remote.down();
    await remote.back({ gap: 300 });
    if (await screen(page, "search").isVisible()) await remote.back({ gap: 400 });
    await expect(screen(page, "home")).toBeVisible();
  }
  await expect(captions).toHaveCount(0, { timeout: 20_000 });
  await shot("home-recovered");
});

test("Home recovers by itself once the server answers again (no navigation)", async ({ page, seedSession }) => {
  // Was a bug: Home loaded its rails once (home.rs load()) and only reloads on a
  // realtime favourites/progress frame. After an outage the rails stay on
  // 「無法連線到伺服器」 until the viewer happens to navigate away and back,
  // and the socket's reconnect ("ready") does not refetch either, so events
  // missed while offline are never heard (CLAUDE.md: the web app refetches
  // on reconnect). Common on a TV: the app starts before Wi-Fi is up.
  // Fixed: home.rs retries a failed load with backoff (2/4/8/15/30 s) and
  // reloads on realtime::library_version() bumps.
  test.setTimeout(90_000);
  const o = await outage(page);
  o.down = true;
  await seedSession();
  await page.goto("/");
  const captions = page.locator('[data-screen="home"] .tv-row-caption.is-error');
  await expect(captions.first()).toBeVisible({ timeout: 20_000 });
  o.down = false;
  await expect(captions).toHaveCount(0, { timeout: 45_000 });
});

test("Search with the server down says so; submitting again after restore works", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(90_000);
  await installProbes(page);
  const o = await outage(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await openSearch(page, remote);
  o.down = true;
  await page.keyboard.type("黑暗榮耀");
  await remote.enter();
  await expect(page.locator('[data-screen="search"] .tv-notice')).toHaveText("無法連線到伺服器", { timeout: 15_000 });
  expect((await settledAudit(page)).problems).toEqual([]);
  await shot("search-offline");
  o.down = false;
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  await expect(page.locator('[data-screen="search"] [data-provider] [data-focusable]').first()).toBeVisible({ timeout: 45_000 });
  await expect(page.locator('[data-screen="search"] .tv-notice')).toHaveCount(0);
  await shot("search-recovered");
});

test("Detail with the server down shows 重試; restore + 重試 loads the title", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(90_000);
  await mockPlayer(page, {});
  const h = await hermeticTitle(page);
  const o = await outage(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await openSearch(page, remote);
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  o.down = true;
  await remote.enter();
  await expect(screen(page, "detail")).toBeVisible();
  const retry = page.locator('[data-screen="detail"] [data-action="retry"]');
  await expect(retry).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-screen="detail"] .tv-error')).toContainText("無法連線");
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "retry");
  await shot("detail-offline");
  o.down = false;
  await remote.enter();
  await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 15_000 });
  await expect(retry).toHaveCount(0);
  await shot("detail-recovered");
});

test("Player started with the server down waits, says so, and starts by itself on restore", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(120_000);
  await mockPlayer(page, { mockDuration: 1500 });
  const h = await hermeticTitle(page);
  const o = await outage(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await openSearch(page, remote);
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await remote.enter();
  const play = page.locator('[data-action="play"]');
  await expect.poll(async () => (await play.innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  o.down = true;
  await remote.enter();
  const player = screen(page, "player");
  await expect(player).toBeVisible();
  // A visible, non-fatal waiting state — not the error panel.
  await expect(player).toContainText(/網路連線中斷|重新連線/, { timeout: 20_000 });
  await expect(page.locator('[data-overlay="error"]')).toHaveCount(0);
  await shot("player-offline");
  o.down = false;
  await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 45_000 }).toBeGreaterThan(0);
  await expect(page.locator('[data-overlay="error"]')).toHaveCount(0);
  await shot("player-recovered");
});

test("Player mid-playback: the server going away does not stop playback or raise the error panel", async ({ page, seedSession, remote }) => {
  test.setTimeout(120_000);
  await mockPlayer(page, { mockDuration: 1500, mockRate: 4 });
  const h = await hermeticTitle(page);
  const o = await outage(page);
  await seedSession();
  await page.goto("/");
  await openSearch(page, remote);
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await remote.enter();
  await expect.poll(async () => (await page.locator('[data-action="play"]').innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  const player = screen(page, "player");
  const pos = async () => Number(await player.getAttribute("data-position"));
  await expect.poll(pos, { timeout: 20_000 }).toBeGreaterThan(0);
  o.down = true;
  const p0 = await pos();
  await page.waitForTimeout(20_000); // spans a 15 s progress report, which fails
  expect(await pos()).toBeGreaterThan(p0 + 40);
  await expect(page.locator('[data-overlay="error"]')).toHaveCount(0);
  o.down = false;
  // The next report after restore lands.
  const n = h.state.puts.length;
  await expect.poll(() => h.state.puts.length, { timeout: 25_000 }).toBeGreaterThan(n);
});
