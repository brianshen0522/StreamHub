// Remote-control behaviour: holding keys, Back walking home first, the Play
// and red keys on cards, long-press OK, edge bumps, keys pressed while a
// screen loads, recent searches and the row keys. Driven with the D-pad only.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";

const QUERY = "黑暗榮耀";
const RECENT_KEY = "streamhub.tizen.recentSearches";

/** Dispatch one key event with a Tizen keyCode (and `repeat` for holds). */
async function keyEvent(page, type, keyCode, key = "", repeat = false) {
  await page.evaluate(({ type, keyCode, key, repeat }) => {
    const target = document.activeElement || document.body;
    const ev = new KeyboardEvent(type, { key, bubbles: true, cancelable: true, repeat });
    Object.defineProperty(ev, "keyCode", { get: () => keyCode });
    Object.defineProperty(ev, "which", { get: () => keyCode });
    target.dispatchEvent(ev);
  }, { type, keyCode, key, repeat });
}

/** Hold a key: one keydown, repeats every `every` ms for `ms`, then keyup. Returns the keydown count. */
async function hold(page, keyCode, key, ms, every = 50) {
  await keyEvent(page, "keydown", keyCode, key, false);
  let n = 1;
  const end = Date.now() + ms;
  while (Date.now() < end) {
    await page.waitForTimeout(every);
    await keyEvent(page, "keydown", keyCode, key, true);
    n += 1;
  }
  await keyEvent(page, "keyup", keyCode, key, false);
  return n;
}

const RIGHT = [39, "ArrowRight"];
const LEFT = [37, "ArrowLeft"];
const DOWN = [40, "ArrowDown"];

/** Index of the focused element within its rail track, and the rail's group. */
const railPos = (page) => page.evaluate(() => {
  const f = document.querySelector(".is-focused");
  const track = f?.closest("[data-rail-track]");
  if (!track) return { index: -1, group: null };
  return { index: [...track.querySelectorAll("[data-focusable]")].indexOf(f), group: track.dataset.focusGroup };
});
const focusedSel = (page, sel) => page.evaluate((s) => !!document.querySelector(".is-focused")?.matches(s), sel);
const focusedGroup = (page) => page.evaluate(() => document.querySelector(".is-focused")?.closest("[data-focus-group]")?.dataset.focusGroup ?? null);

async function searchFor(page, remote, q) {
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Right"] });
  await remote.enter();
  await expect(screen(page, "search")).toBeVisible();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up"] });
  await page.keyboard.type(q);
  await remote.enter();
  await expect.poll(() => page.locator('[data-provider="movieffm"] [data-focusable]').count(), { timeout: 45_000 }).toBeGreaterThan(20);
}

/** One favourite (and optionally a half-watched progress row) made through the API. */
async function seedLibrary(qaApi, { progress = false } = {}) {
  const r = await qaApi.json("GET", `/search?q=${encodeURIComponent("甄嬛傳")}&provider=777tv`);
  const it = (r.results?.[0]?.items || []).find((x) => x.title === "甄嬛傳") || r.results?.[0]?.items?.[0];
  if (!it) throw new Error("no seed title");
  const fav = await qaApi.json("POST", "/me/favorites", {
    providerKey: "777tv", mediaType: "tv", title: it.title, posterUrl: it.posterUrl, itemUrl: it.url,
  });
  if (progress) {
    await qaApi.json("PUT", "/me/progress", {
      providerKey: "777tv", mediaType: "tv", title: it.title, posterUrl: it.posterUrl, itemUrl: it.url,
      detailUrl: it.url, seasonUrl: "", episodeLabel: "第02集", positionSeconds: 600, durationSeconds: 2700,
    });
  }
  return {
    item: it,
    cleanup: async () => {
      await qaApi.call("DELETE", `/me/favorites/${fav.favorite?.id || fav.id}`);
      await qaApi.call("DELETE", "/me/progress", { scope: "title", providerKey: "777tv", itemUrl: it.url, title: it.title });
    },
  };
}

test.describe.configure({ mode: "serial" });

test("holding Left/Right accelerates, stops exactly on release and bumps at the end; Up/Down hold is steady",
  async ({ page, seedSession, remote, shot }) => {
    test.setTimeout(150_000);
    await seedSession();
    await page.goto("/");
    await expect(screen(page, "home")).toBeVisible();
    await searchFor(page, remote, QUERY);
    await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"] });
    const start = await railPos(page);
    expect(start.index).toBe(0);

    // A short hold is paced: 7 keydowns in 300 ms are not 7 cards.
    const shortEvents = await hold(page, ...RIGHT, 300);
    const afterShort = await railPos(page);
    expect(afterShort.index, `short hold (${shortEvents} keydowns)`).toBeGreaterThanOrEqual(1);
    expect(afterShort.index).toBeLessThanOrEqual(3);

    // A long hold speeds up, ending in multi-card jumps.
    const longEvents = await hold(page, ...RIGHT, 2600);
    const afterLong = await railPos(page);
    const moved = afterLong.index - afterShort.index;
    expect(moved, `long hold of ${longEvents} keydowns`).toBeGreaterThan(longEvents * 0.6);
    expect(afterLong.group).toBe(start.group);
    await shot("after-long-hold");

    // Released: nothing queued keeps moving.
    await page.waitForTimeout(500);
    expect((await railPos(page)).index, "stops exactly on keyup").toBe(afterLong.index);

    // Back to the start, then the edge bumps instead of wrapping.
    await hold(page, ...LEFT, 6000);
    expect((await railPos(page)).index).toBe(0);
    await page.waitForTimeout(400);
    await remote.left();
    expect((await railPos(page)).index, "never wraps").toBe(0);
    expect(await focusedSel(page, '[data-bump="left"]'), "a bump at the start of the rail").toBeTruthy();

    // Up/Down while held: a row at a time, at a steady pace (once every
    // provider has answered — a row still loading holds the key).
    await expect(page.locator(".tv-skeleton")).toHaveCount(0, { timeout: 45_000 });
    expect(await page.locator(".tv-results [data-rail-track]").count()).toBeGreaterThanOrEqual(2);
    const row0 = await focusedGroup(page);
    await hold(page, ...DOWN, 230);
    const rows = await page.evaluate(() => [...document.querySelectorAll('.tv-results [data-rail-track]')].map((t) => t.dataset.focusGroup));
    const moves = rows.indexOf(await focusedGroup(page)) - rows.indexOf(row0);
    expect(moves, "230 ms of Down held").toBeGreaterThanOrEqual(1);
    expect(moves).toBeLessThanOrEqual(2);
  });

test("Back walks home first: Search results → input → Home; Detail rows → 播放 → out; Home → first card → exit",
  async ({ page, seedSession, remote, qaApi, shot }) => {
    test.setTimeout(150_000);
    const seed = await seedLibrary(qaApi);
    try {
      await seedSession();
      await page.goto("/");
      await expect(screen(page, "home")).toBeVisible();
      await expect(page.locator(".tv-rows [data-focusable]").first()).toBeVisible();
      await expect(page.locator(".tv-skeleton")).toHaveCount(0, { timeout: 20_000 });

      await searchFor(page, remote, QUERY);
      await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"] });
      await remote.enter();
      await expect(screen(page, "detail")).toBeVisible();
      await expect.poll(() => focusedSel(page, '[data-action="play"]'), { timeout: 30_000 }).toBeTruthy();
      await focusTo(page, remote, '[data-focus-group="detail-episodes"] [data-focusable]', { dirs: ["Down"] });
      await remote.right();
      await remote.back();
      expect(await focusedSel(page, '[data-action="play"]'), "Back in the episode rail returns to 播放").toBeTruthy();
      await expect(screen(page, "detail")).toBeVisible();
      await shot("detail-back-to-play");
      await remote.back();
      await expect(screen(page, "search")).toBeVisible();

      // Search: results focused → Back → the input; Back again leaves.
      await expect.poll(() => page.evaluate(() => !!document.querySelector(".tv-results .is-focused"))).toBeTruthy();
      await remote.right({ times: 3 });
      await remote.back();
      await expect(screen(page, "search")).toBeVisible();
      expect(await focusedSel(page, 'input[name="q"]'), "Back from results lands on the input").toBeTruthy();
      expect(await page.evaluate(() => [...document.querySelectorAll("[data-rail-track]")].every((t) => (t.dataset.shift || "0") === "0")),
        "rails back at rest").toBeTruthy();
      await remote.back();
      await expect(screen(page, "home")).toBeVisible();

      // Home: anywhere else → Back → first card; Back again → the exit prompt.
      await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right"] });
      await remote.back();
      await expect(screen(page, "exit")).toBeHidden();
      expect(await page.evaluate(() => document.querySelector(".is-focused") === document.querySelector(".tv-rows [data-focusable]")),
        "Back returns to the first card of the first rail").toBeTruthy();
      await remote.back();
      await expect(screen(page, "exit")).toBeVisible();
      await remote.back();
      await expect(screen(page, "exit")).toBeHidden();
    } finally {
      await seed.cleanup();
    }
  });

test("Play key on a Home card, a Search card and Detail starts playback; Back returns where it was pressed",
  async ({ page, seedSession, remote, qaApi }) => {
    test.setTimeout(180_000);
    const seed = await seedLibrary(qaApi);
    const backTo = async (name) => {
      for (let i = 0; i < 4 && !(await screen(page, name).isVisible()); i += 1) {
        await remote.back();
        await page.waitForTimeout(500);
      }
      await expect(screen(page, name)).toBeVisible();
    };
    try {
      await seedSession();
      await page.goto("/");
      await expect(screen(page, "home")).toBeVisible();
      await focusTo(page, remote, '[data-focus-group="home-favorites"] [data-playable]', { dirs: ["Down", "Right"] });
      await remote.playpause();
      await expect(screen(page, "player")).toBeVisible({ timeout: 45_000 });
      await backTo("home");

      await searchFor(page, remote, QUERY);
      await focusTo(page, remote, '[data-provider="movieffm"] [data-playable]', { dirs: ["Down"] });
      await remote.playpause();
      await expect(screen(page, "player")).toBeVisible({ timeout: 45_000 });
      await backTo("search");

      await remote.enter();
      await expect(screen(page, "detail")).toBeVisible();
      await expect.poll(() => focusedSel(page, '[data-action="play"]'), { timeout: 30_000 }).toBeTruthy();
      await remote.down();
      await remote.playpause();
      await expect(screen(page, "player")).toBeVisible({ timeout: 45_000 });
      await backTo("detail");
    } finally {
      await seed.cleanup();
    }
  });

test("long-press OK opens the card menu (and its release doesn't click); short OK still opens Detail",
  async ({ page, seedSession, remote, qaApi, shot }) => {
    test.setTimeout(120_000);
    const seed = await seedLibrary(qaApi, { progress: true });
    try {
      await seedSession();
      await page.goto("/");
      await expect(screen(page, "home")).toBeVisible();

      // Short OK: Detail, as before.
      await focusTo(page, remote, '[data-focus-group="home-favorites"] [data-longpress]', { dirs: ["Down", "Right"] });
      await remote.enter();
      await expect(screen(page, "detail")).toBeVisible();
      await expect.poll(() => focusedSel(page, '[data-action="play"]'), { timeout: 30_000 }).toBeTruthy();
      await remote.back();
      await expect(screen(page, "home")).toBeVisible();

      // Long press on a favourite: 取消收藏.
      await focusTo(page, remote, `[data-focus-group="home-favorites"] [data-longpress]`, { dirs: ["Down", "Right"] });
      await keyEvent(page, "keydown", 13, "Enter");
      await page.waitForTimeout(900);
      await expect(page.locator("[data-menu]")).toBeVisible();
      await keyEvent(page, "keyup", 13, "Enter");
      await page.waitForTimeout(300);
      await expect(screen(page, "home"), "the release did not open Detail").toBeVisible();
      await expect(screen(page, "detail")).toHaveCount(0);
      expect(await focusedSel(page, '[data-action="menu-unfavorite"]')).toBeTruthy();
      await shot("favourite-menu");
      await remote.enter();
      await expect.poll(async () => (await qaApi.json("GET", "/me/favorites")).favorites.some((f) => f.itemUrl === seed.item.url),
        { timeout: 15_000 }).toBeFalsy();

      // Long press on continue watching: 從清單移除.
      await focusTo(page, remote, '[data-focus-group="home-continue"] [data-longpress]', { dirs: ["Up", "Down", "Left"] });
      // Make sure it is the seeded title's card that gets removed.
      for (let i = 0; i < 20; i += 1) {
        const t = await page.evaluate(() => document.querySelector(".is-focused .tv-card-title")?.textContent);
        if (t === seed.item.title) break;
        await remote.right();
      }
      const cardId = await page.evaluate(() => document.querySelector(".is-focused")?.dataset.focusId);
      expect(await page.evaluate(() => document.querySelector(".is-focused .tv-card-title")?.textContent)).toBe(seed.item.title);
      await keyEvent(page, "keydown", 13, "Enter");
      await page.waitForTimeout(900);
      await keyEvent(page, "keyup", 13, "Enter");
      await expect(page.locator('[data-action="menu-remove"]')).toBeVisible();
      await expect(page.locator('[data-action="menu-details"]')).toBeVisible();
      await focusTo(page, remote, '[data-action="menu-remove"]', { dirs: ["Left"] });
      await remote.enter();
      await expect.poll(async () => (await qaApi.json("GET", "/me/continue-watching")).items.some((i) => i.title === seed.item.title && i.providerKey === "777tv"),
        { timeout: 15_000 }).toBeFalsy();
      await expect(page.locator("[data-menu]")).toHaveCount(0);
      await expect(page.locator(`[data-focus-id="${cardId}"]`), "the card leaves the rail").toHaveCount(0, { timeout: 15_000 });
      await expect(page.locator(".is-focused"), "focus lands somewhere real").toHaveCount(1);
    } finally {
      await seed.cleanup();
    }
  });

test("edges bump instead of doing nothing, and never wrap", async ({ page, seedSession, remote, focused }) => {
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await expect(page.locator(".tv-skeleton")).toHaveCount(0, { timeout: 20_000 });
  await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right"] });
  await remote.right();
  expect((await focused()).data.action, "Right at the end stays").toBe("signout");
  expect(await focusedSel(page, '[data-bump="right"]')).toBeTruthy();
  await page.waitForTimeout(400);
  await remote.up();
  expect((await focused()).data.action).toBe("signout");
  expect(await focusedSel(page, '[data-bump="up"]')).toBeTruthy();
});

test("keys pressed while Detail is loading are kept and replayed", async ({ page, seedSession, remote }) => {
  test.setTimeout(120_000);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await searchFor(page, remote, QUERY);
  await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"] });
  // OK then Down straight away, while Detail has nothing focusable yet.
  await remote.enter({ gap: 0 });
  await remote.down({ gap: 0 });
  await expect(screen(page, "detail")).toBeVisible();
  await expect.poll(() => focusedGroup(page), { timeout: 30_000 }).toMatch(/^detail-(seasons|episodes)$/);
});

test("recent searches: shown before typing, OK runs one, Up from the first rail lands on the input",
  async ({ page, seedSession, remote }) => {
    test.setTimeout(120_000);
    await seedSession();
    const many = Array.from({ length: 12 }, (_, i) => `舊查詢${i}`);
    await page.addInitScript(([k, v]) => localStorage.setItem(k, v), [RECENT_KEY, JSON.stringify([QUERY, ...many])]);
    await page.goto("/");
    await expect(screen(page, "home")).toBeVisible();
    await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Right"] });
    await remote.enter();
    await expect(screen(page, "search")).toBeVisible();
    const chips = page.locator('[data-focus-group="search-recent"] [data-focusable]');
    await expect(chips.first()).toHaveText(QUERY);
    expect(await chips.count(), "at most 8 recent searches").toBeLessThanOrEqual(8);
    await focusTo(page, remote, '[data-focus-group="search-recent"] [data-focusable]', { dirs: ["Down"] });
    await remote.enter();
    await expect(page.locator('input[name="q"]')).toHaveValue(QUERY);
    await expect.poll(() => page.locator('[data-provider] [data-focusable]').count(), { timeout: 45_000 }).toBeGreaterThan(3);
    await focusTo(page, remote, '.tv-results [data-focusable]', { dirs: ["Down"] });
    await remote.right({ times: 4 });
    await remote.up();
    expect(await focusedSel(page, 'input[name="q"]'), "Up from the first result rail").toBeTruthy();
  });

test("CH+/CH− move a whole row; the red key toggles a favourite", async ({ page, seedSession, remote, qaApi }) => {
  test.setTimeout(120_000);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await searchFor(page, remote, QUERY);
  await focusTo(page, remote, '.tv-results [data-provider] [data-focusable]', { dirs: ["Down"] });
  const first = await focusedGroup(page);
  await keyEvent(page, "keydown", 428);
  await keyEvent(page, "keyup", 428);
  const second = await focusedGroup(page);
  expect(second, "CH− goes a row down").not.toBe(first);
  await keyEvent(page, "keydown", 427);
  await keyEvent(page, "keyup", 427);
  expect(await focusedGroup(page), "CH+ comes back up").toBe(first);
  await keyEvent(page, "keydown", 34);
  expect(await focusedGroup(page), "PageDown too").toBe(second);
  await keyEvent(page, "keydown", 33);

  const itemUrl = await page.evaluate(() => document.querySelector(".is-focused")?.dataset.focusId?.split(":").slice(2).join(":"));
  const isFav = async () => (await qaApi.json("GET", "/me/favorites")).favorites.some((f) => f.itemUrl === itemUrl);
  const before = await isFav();
  try {
    await keyEvent(page, "keydown", 403);
    await expect(page.locator("[data-toast]")).toBeVisible();
    await expect.poll(isFav, { timeout: 15_000 }).toBe(!before);
    await page.waitForTimeout(400);
    await keyEvent(page, "keydown", 403);
    await expect.poll(isFav, { timeout: 15_000 }).toBe(before);
  } finally {
    if (!before && (await isFav())) {
      for (const f of (await qaApi.json("GET", "/me/favorites")).favorites.filter((x) => x.itemUrl === itemUrl)) {
        await qaApi.call("DELETE", `/me/favorites/${f.id}`);
      }
    }
  }
});

test("switching seasons quickly: the rail holds the season picked last, not the slower answer",
  async ({ page, seedSession, remote }) => {
    test.setTimeout(120_000);
    await seedSession();
    await page.goto("/");
    await expect(screen(page, "home")).toBeVisible();
    await searchFor(page, remote, QUERY);
    await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"] });
    // The second /episodes answer (the first season switch) is held back 3 s.
    let calls = 0;
    await page.route(/\/api\/v1\/episodes\?/, async (route) => {
      calls += 1;
      if (calls === 2) await new Promise((r) => setTimeout(r, 3000));
      return route.fallback();
    });
    await remote.enter();
    await expect(screen(page, "detail")).toBeVisible();
    await expect.poll(() => focusedSel(page, '[data-action="play"]'), { timeout: 30_000 }).toBeTruthy();
    const pills = page.locator('[data-focus-group="detail-seasons"] [data-focusable]');
    test.skip((await pills.count()) < 2, "the first result has a single season today");
    const episodes = page.locator('[data-focus-group="detail-episodes"] [data-focusable]');
    const firstLabel = (await pills.first().textContent()).trim();
    const firstCount = await episodes.count();

    await focusTo(page, remote, '[data-focus-group="detail-seasons"] [data-focusable]', { dirs: ["Down"] });
    await remote.right();
    await remote.enter(); // season 2: slow
    await remote.left();
    await remote.enter(); // season 1 again: fast
    await page.waitForTimeout(4000); // the slow answer has landed by now
    expect(calls).toBeGreaterThanOrEqual(3);
    await expect(page.locator('[data-focus-group="detail-seasons"] .is-selected')).toHaveText(firstLabel);
    await expect(episodes, "episodes are the first season's").toHaveCount(firstCount);
  });
