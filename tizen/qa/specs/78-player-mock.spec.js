// G. The player against the mock backend and a hermetic title (no live
// writes): progress PUT cadence and body, resume at position − 30 s, up-next
// inside the last 120 s, none on the last episode, and the recovery ladder
// down to the error panel's 重試 / 返回.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { hermeticTitle, mockPlayer } from "../lib/qa2.js";

const ALLOWED = new Set(["providerKey", "mediaType", "title", "posterUrl", "itemUrl", "detailUrl", "seasonUrl",
  "seasonLabel", "episodeLabel", "sourceLabel", "positionSeconds", "durationSeconds", "event"]);

async function toDetail(page, remote, h) {
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
  await remote.enter();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
  await page.keyboard.type(h.opts.query);
  await remote.enter();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await remote.enter();
  const play = page.locator('[data-action="play"]');
  await expect.poll(async () => (await play.innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
  return play;
}
async function pressPlay(page, remote) {
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  const player = screen(page, "player");
  await expect(player).toBeVisible();
  return player;
}
const pos = async (player) => Number(await player.getAttribute("data-position"));
const opened = (page) => page.evaluate(() => window.StreamHubPlayer.opened.map((o) => ({ ...o, url: o.url.replace(/accessToken=[^&]+/, "accessToken=T") })));

test("progress PUT every ≈15 s with integer seconds and only contract fields", async ({ page, seedSession, remote }, testInfo) => {
  test.setTimeout(150_000);
  await mockPlayer(page, { mockDuration: 1500, mockRate: 1 });
  const h = await hermeticTitle(page);
  await seedSession();
  await toDetail(page, remote, h);
  const player = await pressPlay(page, remote);
  await expect.poll(() => pos(player), { timeout: 20_000 }).toBeGreaterThan(0);
  await page.waitForTimeout(62_000);
  const progress = h.state.puts.filter((p) => p.body.event === "progress");
  const gaps = progress.slice(1).map((p, i) => (p.at - progress[i].at) / 1000);
  await testInfo.attach("puts.json", { body: JSON.stringify(h.state.puts, null, 2), contentType: "application/json" });
  console.log(`[cadence] events=${h.state.puts.map((p) => p.body.event).join(",")} progress gaps(s)=${gaps.map((g) => g.toFixed(1)).join(",")}`);
  expect(progress.length).toBeGreaterThanOrEqual(3);
  for (const g of gaps) { expect(g).toBeGreaterThan(12); expect(g).toBeLessThan(18.5); }
  for (const { body } of h.state.puts) {
    expect(Number.isInteger(body.positionSeconds)).toBeTruthy();
    expect(Number.isInteger(body.durationSeconds)).toBeTruthy();
    expect(body).not.toHaveProperty("progressPercent");
    expect(body).not.toHaveProperty("isCompleted");
    for (const k of Object.keys(body)) expect(ALLOWED.has(k), `unexpected field ${k}`).toBeTruthy();
    expect(body).toMatchObject({ providerKey: h.opts.provider, itemUrl: h.opts.url, title: h.opts.title, mediaType: "tv", episodeLabel: "第01集" });
    expect(body.durationSeconds).toBe(1500);
    expect(body.positionSeconds).toBeLessThanOrEqual(body.durationSeconds);
  }
  // Positions track the clock (rate 1): the last report is ~ the elapsed time.
  const last = progress[progress.length - 1].body.positionSeconds;
  expect(last).toBeGreaterThan(40);
});

test("resume: a saved position of 10:00 opens at ≈9:30, and Back + re-open resumes where it left", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(150_000);
  await mockPlayer(page, { mockDuration: 1500, mockRate: 10 });
  const h = await hermeticTitle(page, {
    progress: [{ id: "p2", providerKey: "dramasq", itemUrl: "https://dramasq.qa.invalid/detail/qa-hermetic.html", title: "QA 測試劇",
      mediaType: "tv", seasonUrl: "", episodeLabel: "第02集", positionSeconds: 600, durationSeconds: 1500, progressPercent: 40,
      isCompleted: false, lastWatchedAt: new Date().toISOString() }],
  });
  await seedSession();
  const play = await toDetail(page, remote, h);
  await expect(play).toContainText("繼續播放");
  await expect(play).toContainText("第02集");
  await expect(play).toContainText("9:30");
  await shot("resume-label");
  const player = await pressPlay(page, remote);
  await expect.poll(() => pos(player), { timeout: 20_000 }).toBeGreaterThan(0);
  const o1 = await opened(page);
  expect(o1[0].startMs, "start = position − 30 s").toBeGreaterThanOrEqual(569_000);
  expect(o1[0].startMs).toBeLessThanOrEqual(571_000);
  expect(await pos(player)).toBeGreaterThanOrEqual(570);

  // Play on (rate 10) a little, then Back: the leave report is what re-open resumes from.
  await page.waitForTimeout(5000);
  await remote.back({ gap: 300 });
  if (await screen(page, "player").isVisible()) await remote.back({ gap: 500 });
  await expect(screen(page, "detail")).toBeVisible();
  await expect.poll(() => h.state.puts.length, { timeout: 10_000 }).toBeGreaterThan(0);
  const saved = h.state.puts[h.state.puts.length - 1].body.positionSeconds;
  console.log(`[resume] first start ${o1[0].startMs} ms; saved on leave ${saved}s (events ${h.state.puts.map((p) => p.body.event).join(",")})`);
  expect(saved).toBeGreaterThan(600);
  // Detail reloads progress on return? (The label must show the new position.)
  await expect.poll(async () => page.locator('[data-action="play"]').innerText(), { timeout: 10_000 })
    .toContain(`${Math.floor((saved - 30) / 60)}:`);
  await pressPlay(page, remote);
  await expect.poll(async () => (await opened(page)).length, { timeout: 20_000 }).toBeGreaterThan(o1.length);
  const o2 = (await opened(page)).slice(o1.length);
  console.log(`[resume] re-open start ${o2[0].startMs} ms`);
  expect(Math.abs(o2[0].startMs - (saved - 30) * 1000)).toBeLessThanOrEqual(1500);
});

test("up-next appears inside the last 120 s; OK plays the next episode", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(120_000);
  await mockPlayer(page, { mockDuration: 200, mockRate: 10 });
  const h = await hermeticTitle(page);
  await seedSession();
  await toDetail(page, remote, h);
  const player = await pressPlay(page, remote);
  const card = page.locator('[data-overlay="up-next"]');
  await expect(card).toBeVisible({ timeout: 30_000 });
  const at = await pos(player);
  console.log(`[up-next] shown at ${at}s of 200`);
  expect(200 - at).toBeLessThanOrEqual(120);
  expect(200 - at).toBeGreaterThan(100); // not late either
  await expect(card).toContainText("第02集");
  await shot("up-next");
  await remote.enter();
  await expect.poll(() => h.state.puts.map((p) => p.body.episodeLabel), { timeout: 20_000 }).toContain("第02集");
  await expect(screen(page, "player")).toBeVisible();
  await expect(card).toBeHidden();
});

test("the last episode offers no up-next and ends back on Detail", async ({ page, seedSession, remote }) => {
  test.setTimeout(120_000);
  await mockPlayer(page, { mockDuration: 200, mockRate: 10 });
  const h = await hermeticTitle(page, {
    progress: [{ id: "p3", providerKey: "dramasq", itemUrl: "https://dramasq.qa.invalid/detail/qa-hermetic.html", title: "QA 測試劇",
      mediaType: "tv", seasonUrl: "", episodeLabel: "第03集", positionSeconds: 40, durationSeconds: 200, progressPercent: 20,
      isCompleted: false, lastWatchedAt: new Date().toISOString() }],
  });
  await seedSession();
  const play = await toDetail(page, remote, h);
  await expect(play).toContainText("第03集");
  const player = await pressPlay(page, remote);
  let sawUpNext = false;
  const t0 = Date.now();
  while (Date.now() - t0 < 40_000 && (await screen(page, "player").count())) {
    if (await page.locator('[data-overlay="up-next"]').count()) sawUpNext = true;
    await page.waitForTimeout(250);
  }
  expect(sawUpNext, "no up-next card on the last episode").toBe(false);
  await expect(screen(page, "detail")).toBeVisible();
  const ended = h.state.puts.find((p) => p.body.event === "ended");
  expect(ended, "an ended report").toBeTruthy();
  expect(ended.body.positionSeconds).toBe(ended.body.durationSeconds);
  void player;
});

test("ladder: a failing /manifest recovers on the /stream relay without the error panel", async ({ page, seedSession, remote }) => {
  await mockPlayer(page, { mockPlayerFail: "direct", mockOpenDelay: 150 });
  const h = await hermeticTitle(page);
  await seedSession();
  await toDetail(page, remote, h);
  const player = await pressPlay(page, remote);
  await expect.poll(() => pos(player), { timeout: 30_000 }).toBeGreaterThan(0);
  const o = await opened(page);
  console.log(`[ladder direct] ${o.map((x) => new URL(x.url).pathname).join(" → ")}`);
  expect(o[0].url).toContain("/api/v1/manifest?");
  expect(o[o.length - 1].url).toContain("/api/v1/stream?");
  await expect(page.locator('[data-overlay="error"]')).toHaveCount(0);
});

test("ladder: a one-off failure is retried in place", async ({ page, seedSession, remote }) => {
  await mockPlayer(page, { mockPlayerFail: "once", mockOpenDelay: 150 });
  const h = await hermeticTitle(page);
  await seedSession();
  await toDetail(page, remote, h);
  const player = await pressPlay(page, remote);
  await expect.poll(() => pos(player), { timeout: 30_000 }).toBeGreaterThan(0);
  const o = await opened(page);
  console.log(`[ladder once] ${o.map((x) => new URL(x.url).pathname).join(" → ")}`);
  expect(o.length).toBe(2);
  expect(o[1].url).toBe(o[0].url);
});

test("ladder exhausted: error panel, 重試 focused, 重試 climbs again, 返回 goes to Detail", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(120_000);
  await mockPlayer(page, { mockPlayerFail: "all", mockOpenDelay: 150 });
  const h = await hermeticTitle(page, { sources: 2 });
  await seedSession();
  await toDetail(page, remote, h);
  await pressPlay(page, remote);
  const panel = page.locator('[data-overlay="error"]');
  await expect(panel).toBeVisible({ timeout: 30_000 });
  const o = await opened(page);
  console.log(`[ladder all] ${o.map((x) => `${new URL(x.url).pathname}(${decodeURIComponent(x.url).match(/cdn\d/)?.[0]})`).join(" → ")}`);
  expect(o.some((x) => decodeURIComponent(x.url).includes("cdn2"))).toBeTruthy();
  expect(o.some((x) => x.url.includes("/api/v1/stream?"))).toBeTruthy();
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "retry");
  await shot("error-panel");
  const n = o.length;
  await remote.enter();
  await expect.poll(async () => (await opened(page)).length, { timeout: 15_000 }).toBeGreaterThan(n);
  await expect(panel).toBeVisible({ timeout: 30_000 });
  await focusTo(page, remote, '[data-overlay="error"] [data-action="back"]', { dirs: ["Right", "Left", "Down"] });
  await remote.enter();
  await expect(screen(page, "detail")).toBeVisible();
});
