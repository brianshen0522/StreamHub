// Temporary: a curated walk for screenshots, not a regression spec.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { credentials } from "../lib/env.js";

// SHOWCASE_VIDEO=1 records each walk as a WebM (stills can't show motion).
if (process.env.SHOWCASE_VIDEO) {
  test.use({ video: { mode: "on", size: { width: 1280, height: 720 } } });
}

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
    // itemUrl is validated even though scope "title" ignores it.
    made.titles.push({ providerKey: s.provider, title: it.title, itemUrl: it.url });
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
    // The password card is one step away from the pairing screen.
    await focusTo(page, remote, '[data-action="use-password"]', { dirs: ["Down", "Up"] });
    await remote.enter();
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
    await page.waitForTimeout(90);
    await shot("home-mid-transition");
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
    await page.waitForTimeout(700);
    await shot("search-skeletons");
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
    await remote.down({ times: 2 });
    await remote.right();
    await page.waitForTimeout(700);
    await shot("detail-episodes");

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
    await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right"] });
    await remote.enter();
    await page.waitForTimeout(500);
    await shot("signout-confirm");
    await remote.back();
    await page.waitForTimeout(300);
    // Back walks to the first card first, then opens the exit prompt.
    for (let i = 0; i < 3 && !(await screen(page, "exit").isVisible()); i += 1) {
      await remote.back();
      await page.waitForTimeout(300);
    }
    await expect(screen(page, "exit")).toBeVisible();
    await page.waitForTimeout(400);
    await shot("exit-prompt");
    await remote.back();
  } finally {
    await page.evaluate(() => localStorage.removeItem("streamhub.mockDuration")).catch(() => {});
    for (const id of made.favs) if (id) await qaApi.call("DELETE", `/me/favorites/${id}`);
    const seen = new Set();
    for (const t of [...made.titles, { providerKey: "movieffm", title: "瑞克和莫蒂", itemUrl: "https://www.mvffm.net/" }]) {
      const k = t.providerKey + t.title;
      if (seen.has(k)) continue;
      seen.add(k);
      await qaApi.call("DELETE", "/me/progress", { scope: "title", ...t });
    }
  }
});

// Player states the walk above does not reach: loading, paused, the source
// picker and the error panel. Appended by the player agent. It starts playback
// with a cast `play` from a second session (as a phone would) rather than
// walking search → detail, so it does not depend on those screens.
test("showcase: player states", async ({ page, seedSession, remote, shot, qaApi }) => {
  test.setTimeout(240_000);
  const { QaApi } = await import("../lib/api.js");
  const { API_BASE } = await import("../lib/env.js");
  const ITEM = "https://777tv.ai/vod/detail/id/231767.html";
  const TITLE = "黑暗榮耀";
  const cleanup = () => qaApi.call("DELETE", "/me/progress", { scope: "title", providerKey: "777tv", itemUrl: ITEM, title: TITLE });
  await seedSession();
  let ws = null;
  try {
    await page.goto("/");
    await expect(screen(page, "home")).toBeVisible({ timeout: 20_000 });

    // A real source line, as the phone would have it.
    // Source probes run live against the provider's CDNs and now and then
    // come back empty; ask again before calling it a failure.
    let line = null;
    for (let attempt = 0; attempt < 4 && !line; attempt += 1) {
      if (attempt) await page.waitForTimeout(3000);
      const sources = await fetch(`${API_BASE}/api/v1/sources?provider=777tv&sourceUrl=${encodeURIComponent(ITEM)}&episode=${encodeURIComponent("第01集")}`,
        { headers: { Authorization: `Bearer ${qaApi.session.accessToken}` } }).then((r) => r.text());
      line = sources.split("\n").filter(Boolean).map((l) => JSON.parse(l)).find((l) => l.directUrl) || null;
    }
    expect(line, "777tv has a playable source for 第01集").toBeTruthy();

    const tvToken = await page.evaluate(() => JSON.parse(localStorage.getItem("streamhub.session")).access_token);
    const tvSid = JSON.parse(Buffer.from(tvToken.split(".")[1], "base64url").toString()).sid;
    const phone = await QaApi.login({ client: "android" });
    ws = new WebSocket(`${API_BASE.replace(/^http/, "ws")}/api/realtime`);
    let listed = false;
    ws.onmessage = (e) => {
      const f = JSON.parse(e.data);
      if (f.receivers) listed = f.receivers.some((r) => r.sessionId === tvSid);
    };
    await new Promise((r) => { ws.onopen = r; });
    ws.send(JSON.stringify({ type: "auth", token: phone.session.accessToken }));
    await expect.poll(() => listed, { timeout: 15_000 }).toBe(true);
    ws.send(JSON.stringify({ type: "command", to: tvSid, command: { action: "play", playback: {
      streamUrl: line.directUrl, provider: "777tv", itemUrl: ITEM, title: TITLE, subtitle: line.sourceLabel,
      posterUrl: "", episodeLabel: "第01集", nextEpisodeLabel: "第02集", episodeUrl: "", positionMs: 754_000,
    } } }));

    const player = screen(page, "player");
    await expect(player.locator('[data-overlay="loading"]')).toBeVisible({ timeout: 20_000 });
    await shot("player-loading");

    await expect(player).toHaveAttribute("data-state", "playing", { timeout: 45_000 });
    await page.waitForTimeout(800);
    await remote.enter();
    await expect(player).toHaveAttribute("data-state", "paused");
    await page.waitForTimeout(400);
    await shot("player-paused");
    await remote.enter();
    await expect(player).toHaveAttribute("data-state", "playing");

    // Seeking: three Rights accumulate into one +30 s jump.
    await page.waitForTimeout(4500);
    await remote.right({ times: 3, gap: 80 });
    await page.waitForTimeout(120);
    await shot("player-seek-accumulate");
    await page.waitForTimeout(800);

    // Up next: the phone seeks near the end; the card slides in.
    ws.send(JSON.stringify({ type: "command", to: tvSid, command: { action: "seek", positionMs: 1_420_000 } }));
    await expect(page.locator('[data-overlay="up-next"]')).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(1500);
    await shot("player-up-next-card");
    await remote.back();
    ws.send(JSON.stringify({ type: "command", to: tvSid, command: { action: "seek", positionMs: 760_000 } }));

    // Wait for the other sources to arrive so the picker has a list.
    await page.waitForTimeout(6000);
    await remote.up();
    await focusTo(page, remote, '[data-action="sources"]', { dirs: ["Right"] });
    await page.waitForTimeout(400);
    await shot("player-controls-sources-focused");
    await remote.enter();
    await expect(page.locator('[data-overlay="sources"]')).toBeVisible();
    await page.waitForTimeout(500);
    await shot("player-sources");

    // Every source failing ends on the error panel.
    await page.evaluate(() => localStorage.setItem("streamhub.mockPlayerFail", "all"));
    await remote.down();
    if (await page.evaluate(() => !!document.querySelector(".vp-source.is-focused.is-current"))) await remote.up();
    await remote.enter();
    await expect(page.locator('[data-overlay="error"]')).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(700);
    await shot("player-error");
    await page.evaluate(() => localStorage.removeItem("streamhub.mockPlayerFail"));
    await remote.back();
    await expect(player).toBeHidden({ timeout: 5_000 });
    await phone.logout();
  } finally {
    ws?.close();
    await page.evaluate(() => localStorage.removeItem("streamhub.mockPlayerFail")).catch(() => {});
    await page.waitForTimeout(1500).catch(() => {});
    await cleanup();
  }
});
