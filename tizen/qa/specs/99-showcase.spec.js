// Temporary: a curated walk for screenshots, not a regression spec.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { credentials } from "../lib/env.js";

const SHELF = [
  { q: "蘭香如故", provider: "chinaqtw", pick: (t) => t === "蘭香如故" },
  { q: "甄嬛傳", provider: "777tv", pick: (t) => t === "甄嬛傳" },
  { q: "黑暗榮耀", provider: "777tv", pick: (t) => t === "黑暗榮耀" },
  { q: "奧本海默", provider: "777tv", pick: (t) => t.startsWith("奧本海默") },
];

test("showcase", async ({ page, remote, shot, qaApi }) => {
  test.setTimeout(300_000);
  const made = { favs: [], titles: [] };

  // A shelf with real posters: three favourites and one half-watched title.
  for (const s of SHELF) {
    const r = await qaApi.json("GET", `/search?q=${encodeURIComponent(s.q)}&provider=${s.provider}`);
    const it = (r.results?.[0]?.items || []).find((x) => s.pick(x.title));
    if (!it) continue;
    const fav = await qaApi.json("POST", "/me/favorites", {
      providerKey: s.provider, mediaType: it.mediaType === "movie" ? "movie" : "tv",
      title: it.title, posterUrl: it.posterUrl, itemUrl: it.url,
    });
    made.favs.push(fav.favorite?.id || fav.id);
    made.titles.push({ providerKey: s.provider, title: it.title });
    if (s.q === "蘭香如故") {
      await qaApi.json("PUT", "/me/progress", {
        providerKey: s.provider, mediaType: "tv", title: it.title, posterUrl: it.posterUrl,
        itemUrl: it.url, detailUrl: it.url, seasonUrl: "", episodeLabel: "第05集", sourceLabel: "lzm3u8",
        positionSeconds: 1260, durationSeconds: 2700,
      });
    }
  }

  try {
    await page.goto("/");
    await expect(screen(page, "login")).toBeVisible();
    await expect(page.locator("[data-user-code]")).not.toBeEmpty({ timeout: 15_000 });
    await page.waitForTimeout(600);
    await shot("login-pairing");

    const { username, password } = credentials();
    await focusTo(page, remote, 'input[name="username"]', { dirs: ["Left", "Up"] });
    await page.keyboard.type(username);
    await remote.down();
    await page.keyboard.type(password);
    await remote.down();
    await shot("login-form");
    await remote.enter();

    await expect(screen(page, "home")).toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(2500);
    await shot("home");
    await remote.down();
    await remote.right();
    await page.waitForTimeout(800);
    await shot("home-rail-focus");
    await remote.down();
    await remote.right();
    await page.waitForTimeout(800);
    await shot("home-favourites");

    await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Right"] });
    await remote.enter();
    await focusTo(page, remote, 'input[name="q"]');
    await page.keyboard.type("瑞克和莫蒂");
    await remote.enter();
    await expect.poll(() => page.locator('[data-provider] [data-focusable]').count(), { timeout: 45_000 }).toBeGreaterThan(3);
    await page.waitForTimeout(6000);
    await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"] });
    await remote.right();
    await page.waitForTimeout(800);
    await shot("search");

    await remote.left();
    await remote.enter();
    await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 30_000 });
    await page.waitForTimeout(2500);
    await shot("detail-seasons");

    await page.evaluate(() => localStorage.setItem("streamhub.mockDuration", "135"));
    await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
    await remote.enter();
    const player = screen(page, "player");
    await expect(player).toBeVisible({ timeout: 45_000 });
    await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 45_000 }).toBeGreaterThan(1);
    await shot("player-bar");
    await remote.up();
    await page.waitForTimeout(500);
    await shot("player-controls");
    await page.waitForTimeout(4500);
    await remote.right({ times: 3, gap: 80 });
    await page.waitForTimeout(150);
    await shot("player-seek");
    await expect(page.locator('[data-overlay="up-next"]')).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(800);
    await shot("player-up-next");

    for (let i = 0; i < 6 && !(await screen(page, "home").isVisible()); i += 1) {
      await remote.back();
      await page.waitForTimeout(700);
    }
    await remote.back();
    await expect(screen(page, "exit")).toBeVisible();
    await page.waitForTimeout(400);
    await shot("exit-prompt");
    await remote.back();
  } finally {
    await page.evaluate(() => localStorage.removeItem("streamhub.mockDuration")).catch(() => {});
    for (const id of made.favs) if (id) await qaApi.call("DELETE", `/me/favorites/${id}`);
    const seen = new Set();
    for (const t of [...made.titles, { providerKey: "movieffm", title: "瑞克和莫蒂" }]) {
      const k = t.providerKey + t.title;
      if (seen.has(k)) continue;
      seen.add(k);
      await qaApi.call("DELETE", "/me/progress", { scope: "title", ...t });
    }
  }
});
