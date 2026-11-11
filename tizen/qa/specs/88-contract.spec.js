// J. Contract conformance, observed on the wire over one session that goes
// Home → Search (live, posters) → Detail → Player (hermetic sources):
// X-StreamHub-Client: tv on every fetch, the access token only ever to our
// own server's three media routes, never to a CDN, the realtime socket at
// /api/realtime authenticating in its first frame, and progress bodies as
// shared/api/README.md specifies.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { API_ORIGIN, hermeticTitle, mockPlayer, requestLog, searchFromHome } from "../lib/qa2.js";
import { APP_URL } from "../lib/env.js";

test("wire-level contract over a full session", async ({ page, seedSession, remote }, testInfo) => {
  test.setTimeout(180_000);
  const sockets = [];
  page.on("websocket", (ws) => {
    const s = { url: ws.url(), sent: [], closed: null };
    sockets.push(s);
    ws.on("framesent", (f) => { s.sent.push(typeof f.payload === "string" ? f.payload : "<binary>"); });
    ws.on("close", () => { s.closed = Date.now(); });
  });
  const log = requestLog(page);
  await mockPlayer(page, { mockDuration: 1500, mockRate: 2 });
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();

  // Live search with posters.
  await searchFromHome(page, remote, focusTo, "黑暗榮耀");
  await expect(page.locator("[data-provider] img").first()).toBeAttached({ timeout: 45_000 });
  await page.waitForTimeout(3000);

  // Hermetic title from here on (installed late: the live search above stays live).
  const h = await hermeticTitle(page);
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up"], steps: 12 });
  await page.keyboard.press("Control+A");
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 12 });
  await remote.enter();
  const play = page.locator('[data-action="play"]');
  await expect.poll(async () => (await play.innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  const player = screen(page, "player");
  await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 20_000 }).toBeGreaterThan(0);
  await expect.poll(() => h.state.puts.length, { timeout: 25_000 }).toBeGreaterThan(0);
  await remote.back({ gap: 400 });
  if (await player.isVisible()) await remote.back({ gap: 600 });
  await page.waitForTimeout(500);
  const opened = await page.evaluate(() => window.StreamHubPlayer.opened);

  const appOrigin = new URL(APP_URL).origin;
  const api = log.filter((r) => r.url.startsWith(API_ORIGIN));
  const fetches = api.filter((r) => ["fetch", "xhr"].includes(r.type) && r.method !== "OPTIONS");
  const media = api.filter((r) => !["fetch", "xhr"].includes(r.type));
  const external = log.filter((r) => !r.url.startsWith(API_ORIGIN) && !r.url.startsWith(appOrigin) && !r.url.startsWith("data:"));
  const summary = {
    requests: log.length, apiFetches: fetches.length, apiMedia: media.length,
    mediaPaths: [...new Set(media.map((r) => new URL(r.url).pathname))],
    externalOrigins: [...new Set(external.map((r) => new URL(r.url).origin))],
    unversioned: [...new Set(fetches.filter((r) => !new URL(r.url).pathname.startsWith("/api/v1/")).map((r) => new URL(r.url).pathname))],
    sockets: sockets.map((s) => ({ url: s.url, first: s.sent[0] ? JSON.parse(s.sent[0]).type : null, frames: s.sent.length })),
    opened: opened.map((o) => o.url.replace(/accessToken=[^&]+/, "accessToken=T")),
  };
  await testInfo.attach("contract-summary.json", { body: JSON.stringify(summary, null, 2), contentType: "application/json" });
  console.log(`[contract] ${JSON.stringify(summary)}`);

  // 1. Every fetch to our server identifies the client, pinned to /api/v1.
  const missing = fetches.filter((r) => r.headers["x-streamhub-client"] !== "tv").map((r) => `${r.method} ${r.url}`);
  expect(missing, "fetches without X-StreamHub-Client: tv").toEqual([]);
  expect(summary.unversioned, "every REST call under /api/v1").toEqual([]);
  // 2. Tokens: in a URL only for /poster, /stream, /manifest on our host; never anywhere else.
  const tokenUrls = log.filter((r) => /[?&]accessToken=/.test(r.url));
  for (const r of tokenUrls) {
    expect(r.url.startsWith(API_ORIGIN), `token sent off-server: ${r.url.slice(0, 120)}`).toBeTruthy();
    expect(new URL(r.url).pathname).toMatch(/^\/api\/v1\/(poster|stream|manifest)$/);
  }
  const authOff = log.filter((r) => !r.url.startsWith(API_ORIGIN) && (r.headers.authorization || /accessToken/.test(r.url)));
  expect(authOff.map((r) => r.url), "no Authorization or token to any other origin").toEqual([]);
  // Posters only ever through our proxy (never hot-linked from the provider).
  expect(external.filter((r) => r.type === "image").map((r) => r.url)).toEqual([]);
  // 3. The player is handed our manifest; the CDN URL inside `target` carries no token.
  expect(opened.length).toBeGreaterThan(0);
  for (const o of opened) {
    const u = new URL(o.url);
    expect(u.origin).toBe(API_ORIGIN);
    expect(u.pathname).toMatch(/^\/api\/v1\/(manifest|stream)$/);
    expect(u.searchParams.get("target") || "").not.toContain("accessToken");
  }
  // 4. Realtime: /api/realtime, no token in the URL, first frame is auth.
  expect(sockets.length).toBeGreaterThan(0);
  for (const s of sockets) {
    const u = new URL(s.url);
    expect(u.pathname).toBe("/api/realtime");
    expect(s.url).not.toMatch(/token/i);
    if (s.sent.length) {
      const first = JSON.parse(s.sent[0]);
      expect(first.type).toBe("auth");
      expect(typeof first.token).toBe("string");
      expect(first.token.split(".").length).toBe(3);
    }
  }
  expect(sockets.some((s) => s.sent.length > 0), "at least one socket authenticated").toBeTruthy();
  // 5. Progress bodies.
  for (const { body } of h.state.puts) {
    expect(Number.isInteger(body.positionSeconds) && Number.isInteger(body.durationSeconds)).toBeTruthy();
    expect(body).not.toHaveProperty("progressPercent");
    expect(body).not.toHaveProperty("isCompleted");
    for (const k of ["providerKey", "mediaType", "title", "itemUrl", "seasonUrl", "episodeLabel", "positionSeconds", "durationSeconds"]) {
      expect(body, `progress body has ${k}`).toHaveProperty(k);
    }
  }
  // 6. Heartbeat while foregrounded (30–60 s): at least the first one.
  expect(fetches.some((r) => r.url.endsWith("/api/v1/auth/heartbeat"))).toBeTruthy();
});

test("logout sends the refresh token in the body and clears the session", async ({ page, seedSession, remote }) => {
  const log = requestLog(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  const stored = await page.evaluate(() => JSON.parse(localStorage.getItem("streamhub.session")));
  await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right"], steps: 12 });
  await remote.enter();
  await focusTo(page, remote, '[data-action="signout-confirm"]', { dirs: ["Right", "Left"] });
  await page.route(/\/api\/v1\/auth\/device\/(start|poll)$/, (route) => route.fulfill({
    status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" },
    body: JSON.stringify(route.request().url().endsWith("/start")
      ? { deviceCode: "x", userCode: "QAQA-TEST", verificationUrl: "https://e.invalid", verificationUrlComplete: "https://e.invalid?code=QAQA-TEST", expiresInSeconds: 600, intervalSeconds: 5 }
      : { status: "pending" }),
  }));
  await remote.enter();
  await expect(screen(page, "login")).toBeVisible();
  const out = log.find((r) => r.url.endsWith("/api/v1/auth/logout"));
  expect(out, "POST /auth/logout").toBeTruthy();
  expect(out.body).toEqual({ refreshToken: stored.refresh_token });
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
});
