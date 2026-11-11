// H. Performance and stability, as a proxy for the M7 (weak SoC, Chromium
// M120): input→focus latency and long tasks during rapid D-pad (normal and
// 4× CPU throttle), a soak of navigation + mock playback with heap / wasm
// memory / DOM nodes / listeners sampled after GC, and cold start of the
// release build under 4× throttling. Numbers are printed and attached; the
// assertions are deliberately loose — the report carries the figures.
import fs from "node:fs";
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { hermeticTitle, mockPlayer, seedFavorites, searchFromHome, prng } from "../lib/qa2.js";
import { API_BASE, DIST_DIR } from "../lib/env.js";
import { serve } from "../tools/serve.mjs";

const SOAK_MIN = Number(process.env.QA_SOAK_MIN || 10);
const RELEASE = process.env.QA_RELEASE_DIST;

const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p) => (s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : NaN);
  return { n: s.length, p50: +q(0.5).toFixed(1), p95: +q(0.95).toFixed(1), max: +(s[s.length - 1] ?? NaN).toFixed(1) };
};

async function probes(page) {
  await page.addInitScript(() => {
    // Wasm linear memory is invisible to JS-heap metrics: catch the instance.
    const keep = (r) => { try { const inst = r.instance || r; window.__wasmMem = inst.exports.memory; } catch { /* */ } return r; };
    const is = WebAssembly.instantiateStreaming;
    if (is) WebAssembly.instantiateStreaming = (...a) => is.apply(WebAssembly, a).then(keep);
    const i = WebAssembly.instantiate;
    WebAssembly.instantiate = (...a) => i.apply(WebAssembly, a).then(keep);
    window.__lat = []; window.__paint = []; window.__long = [];
    let t = null;
    addEventListener("keydown", (e) => { if (e.keyCode >= 37 && e.keyCode <= 40) t = performance.now(); }, true);
    const mo = new MutationObserver((muts) => {
      if (t === null) return;
      if (!muts.some((m) => m.target.classList?.contains("is-focused"))) return;
      const t0 = t; t = null;
      window.__lat.push(performance.now() - t0);
      requestAnimationFrame(() => requestAnimationFrame(() => window.__paint.push(performance.now() - t0)));
    });
    addEventListener("DOMContentLoaded", () => mo.observe(document.body, { subtree: true, attributes: true, attributeFilter: ["class"] }));
    try {
      new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__long.push(e.duration); })
        .observe({ type: "longtask", buffered: true });
    } catch { /* */ }
  });
}

async function hold(page, keyCode, key, ms, every = 50) {
  await page.evaluate(async ({ keyCode, key, ms, every }) => {
    const fire = (type, repeat) => {
      const ev = new KeyboardEvent(type, { key, bubbles: true, cancelable: true, repeat });
      Object.defineProperty(ev, "keyCode", { get: () => keyCode });
      (document.activeElement || document.body).dispatchEvent(ev);
    };
    fire("keydown", false);
    const end = performance.now() + ms;
    while (performance.now() < end) { await new Promise((r) => setTimeout(r, every)); fire("keydown", true); }
    fire("keyup", false);
  }, { keyCode, key, ms, every });
}

let favs;
test.beforeAll(async ({ qaApi }) => { favs = await seedFavorites(qaApi, { n: 10 }); });
test.afterAll(async () => { await favs?.remove(); });

for (const rate of [1, 4]) {
  test(`input→focus latency and long tasks during rapid D-pad (CPU ${rate}×)`, async ({ page, seedSession, remote }, testInfo) => {
    test.setTimeout(240_000);
    await probes(page);
    await seedSession();
    await page.goto("/");
    await searchFromHome(page, remote, focusTo, "黑暗榮耀");
    await expect.poll(() => page.locator("[data-provider] [data-focusable]").count(), { timeout: 45_000 }).toBeGreaterThan(10);
    await page.waitForTimeout(4000);
    await focusTo(page, remote, "[data-provider] [data-focusable]", { dirs: ["Down"] });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send("Emulation.setCPUThrottlingRate", { rate });
    await page.evaluate(() => { window.__lat = []; window.__paint = []; window.__long = []; });
    // Taps: 30 Right at 80 ms (faster than a thumb), 30 Left, 10 Down/Up.
    for (let i = 0; i < 30; i += 1) await remote.right({ gap: 80 });
    for (let i = 0; i < 30; i += 1) await remote.left({ gap: 80 });
    for (let i = 0; i < 6; i += 1) await remote.down({ gap: 150 });
    for (let i = 0; i < 6; i += 1) await remote.up({ gap: 150 });
    const taps = await page.evaluate(() => ({ lat: window.__lat, paint: window.__paint, long: window.__long }));
    // Holds: 3 s of auto-repeat Right, then Left.
    await page.evaluate(() => { window.__lat = []; window.__paint = []; window.__long = []; });
    await hold(page, 39, "ArrowRight", 3000);
    await hold(page, 37, "ArrowLeft", 3000);
    await page.waitForTimeout(600);
    const holds = await page.evaluate(() => ({ lat: window.__lat, paint: window.__paint, long: window.__long }));
    await cdp.send("Emulation.setCPUThrottlingRate", { rate: 1 });
    const dom = await page.evaluate(() => ({ els: document.getElementsByTagName("*").length,
      cards: document.querySelectorAll("[data-provider] .tv-card").length, imgs: document.querySelectorAll("img").length }));
    const result = {
      cpu: rate, dom,
      tapKeyToFocus: stats(taps.lat), tapKeyToPaint: stats(taps.paint), tapLongTasks: stats(taps.long),
      holdKeyToFocus: stats(holds.lat), holdKeyToPaint: stats(holds.paint), holdLongTasks: stats(holds.long),
      holdLongTaskTotalMs: Math.round(holds.long.reduce((a, b) => a + b, 0)),
    };
    console.log(`[latency cpu=${rate}x] ${JSON.stringify(result)}`);
    await testInfo.attach(`latency-${rate}x.json`, { body: JSON.stringify(result, null, 2), contentType: "application/json" });
    expect(result.tapKeyToFocus.n).toBeGreaterThan(40);
    if (rate === 1) expect(result.tapKeyToFocus.p95, "p95 key→focus, unthrottled").toBeLessThan(100);
    else expect(result.tapKeyToFocus.p95, "p95 key→focus at 4×").toBeLessThan(400);
  });
}

test(`soak: ${SOAK_MIN} min of navigation + mock playback, memory sampled after GC`, async ({ page, seedSession, remote }, testInfo) => {
  test.setTimeout((SOAK_MIN + 6) * 60_000);
  await probes(page);
  await mockPlayer(page, { mockDuration: 1500, mockRate: 20, mockOpenDelay: 200 });
  const h = await hermeticTitle(page);
  await seedSession();
  await page.goto("/");
  await expect(page.locator('[data-screen="home"] .tv-rows [data-focusable]').first()).toBeVisible({ timeout: 30_000 });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Performance.enable");
  const rand = prng(42);
  const samples = [];
  const sample = async (label) => {
    await cdp.send("HeapProfiler.collectGarbage");
    await page.waitForTimeout(300);
    const { metrics } = await cdp.send("Performance.getMetrics");
    const m = Object.fromEntries(metrics.map((x) => [x.name, x.value]));
    const dom = await page.evaluate(() => ({ els: document.getElementsByTagName("*").length, wasm: window.__wasmMem?.buffer.byteLength ?? null }));
    const s = { t: Math.round((Date.now() - t0) / 1000), label, heapMB: +(m.JSHeapUsedSize / 1048576).toFixed(2),
      nodes: m.Nodes, listeners: m.JSEventListeners, docEls: dom.els, wasmMB: dom.wasm ? +(dom.wasm / 1048576).toFixed(2) : null };
    samples.push(s);
    console.log(`[soak] ${JSON.stringify(s)}`);
  };
  const t0 = Date.now();
  await sample("start");
  let cycles = 0;
  while (Date.now() - t0 < SOAK_MIN * 60_000) {
    cycles += 1;
    // Home: wander the rails.
    for (let i = 0; i < 25; i += 1) await remote.press(["Left", "Right", "Right", "Down", "Up"][Math.floor(rand() * 5)], { gap: 90 });
    // Search → Detail → Player.
    await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 12 });
    await remote.enter();
    await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
    await page.keyboard.press("Control+A");
    await page.keyboard.type(h.opts.query);
    await remote.enter();
    await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 12 });
    await remote.enter();
    const play = page.locator('[data-action="play"]');
    await expect.poll(async () => (await play.innerText()).includes("播放"), { timeout: 15_000 }).toBeTruthy();
    for (let i = 0; i < 8; i += 1) await remote.press(["Down", "Right", "Left", "Up"][Math.floor(rand() * 4)], { gap: 120 });
    await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
    await remote.enter();
    const player = screen(page, "player");
    await expect.poll(async () => Number(await player.getAttribute("data-position")).valueOf(), { timeout: 20_000 }).toBeGreaterThan(0);
    // ~25 s of playback with seeks, pause/play and the control bar.
    for (let i = 0; i < 10; i += 1) {
      const k = ["Right", "Left", "PlayPause", "Up", "Back"][Math.floor(rand() * 5)];
      if (k === "Back" && (await player.getAttribute("data-controls")) !== "visible") continue;
      await remote.press(k, { gap: 700 });
      if (k === "PlayPause") await remote.press("PlayPause", { gap: 700 });
    }
    await page.waitForTimeout(15_000);
    // Back out to Home.
    for (let i = 0; i < 8 && !(await screen(page, "home").isVisible()); i += 1) await remote.back({ gap: 500 });
    if (await screen(page, "exit").isVisible()) await remote.back({ gap: 400 });
    await expect(screen(page, "home")).toBeVisible();
    await page.waitForTimeout(800);
    await sample(`cycle ${cycles}`);
  }
  const n = samples.length;
  const third = Math.max(1, Math.floor(n / 3));
  const avg = (arr, k) => arr.reduce((a, s) => a + s[k], 0) / arr.length;
  const first = samples.slice(1, 1 + third); // skip "start" (before first navigation warms caches)
  const last = samples.slice(n - third);
  const trend = {};
  for (const k of ["heapMB", "nodes", "listeners", "docEls", "wasmMB"]) {
    if (samples[0][k] === null) continue;
    let rises = 0;
    for (let i = 2; i < n; i += 1) if (samples[i][k] > samples[i - 1][k]) rises += 1;
    trend[k] = { first: +avg(first, k).toFixed(2), last: +avg(last, k).toFixed(2), ratio: +(avg(last, k) / avg(first, k)).toFixed(3),
      risesOf: `${rises}/${Math.max(0, n - 2)}`, monotonic: n > 4 && rises === n - 2 };
  }
  const out = { minutes: SOAK_MIN, cycles, progressPuts: h.state.puts.length, trend, samples };
  console.log(`[soak trend] ${JSON.stringify(trend)}`);
  await testInfo.attach("soak.json", { body: JSON.stringify(out, null, 2), contentType: "application/json" });
  fs.writeFileSync(testInfo.outputPath("soak.json"), JSON.stringify(out, null, 2));
  expect(cycles).toBeGreaterThan(2);
  for (const [k, v] of Object.entries(trend)) {
    expect(v.monotonic, `${k} grows on every cycle (${JSON.stringify(v)})`).toBe(false);
    expect(v.ratio, `${k} last-third vs first-third`).toBeLessThan(k === "wasmMB" ? 1.5 : 1.3);
  }
});

test.describe("cold start", () => {
  test.skip(!RELEASE || !fs.existsSync(`${RELEASE}/index.html`), "QA_RELEASE_DIST (a release build) not given");

  test("release vs dev build: time to first [data-screen] and to Home rails, CPU 4×", async ({ browser, qaApi }, testInfo) => {
    test.setTimeout(300_000);
    const servers = [await serve(RELEASE, 5298), await serve(DIST_DIR, 5297)];
    const results = [];
    try {
      for (const [name, port] of [["release", 5298], ["dev", 5297]]) {
        for (const signedIn of [false, true]) {
          for (let run = 0; run < 3; run += 1) {
            const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
            const page = await ctx.newPage();
            const session = signedIn ? { access_token: qaApi.session.accessToken, refresh_token: qaApi.session.refreshToken, user: qaApi.session.user } : null;
            await page.addInitScript(({ api, session }) => {
              localStorage.setItem("streamhub.apiBase", api);
              if (session && !sessionStorage.getItem("qa.seeded")) {
                localStorage.setItem("streamhub.session", JSON.stringify(session)); sessionStorage.setItem("qa.seeded", "1");
              }
              window.__marks = {};
              const mo = new MutationObserver(() => {
                if (!window.__marks.screen && document.querySelector("[data-screen]")) window.__marks.screen = performance.now();
                if (!window.__marks.rails && document.querySelector('[data-screen="home"] .tv-rows [data-focusable]')) window.__marks.rails = performance.now();
                if (!window.__marks.focus && document.querySelector(".is-focused")) window.__marks.focus = performance.now();
              });
              addEventListener("DOMContentLoaded", () => mo.observe(document.body, { subtree: true, childList: true, attributes: true }));
            }, { api: API_BASE, session });
            await page.route(/\/api\/v1\/auth\/device\/(start|poll)$/, (route) => route.fulfill({
              status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" },
              body: JSON.stringify(route.request().url().endsWith("/start")
                ? { deviceCode: "x", userCode: "QAQA-TEST", verificationUrl: "https://e.invalid", verificationUrlComplete: "https://e.invalid?code=QAQA-TEST", expiresInSeconds: 600, intervalSeconds: 5 }
                : { status: "pending" }),
            }));
            const cdp = await ctx.newCDPSession(page);
            await cdp.send("Network.enable");
            await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });
            await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
            await page.goto(`http://localhost:${port}/`);
            const want = signedIn ? '[data-screen="home"] .tv-rows [data-focusable]' : '[data-screen="login"]';
            await page.locator(want).first().waitFor({ timeout: 90_000 });
            await page.waitForTimeout(300);
            const m = await page.evaluate(() => {
              const nav = performance.getEntriesByType("navigation")[0];
              const wasm = performance.getEntriesByType("resource").find((r) => r.name.endsWith(".wasm"));
              return { ...window.__marks, dcl: nav.domContentLoadedEventEnd, wasmBytes: wasm?.encodedBodySize, wasmFetchMs: wasm ? wasm.responseEnd - wasm.startTime : null };
            });
            results.push({ build: name, signedIn, run, ...Object.fromEntries(Object.entries(m).map(([k, v]) => [k, typeof v === "number" ? Math.round(v) : v])) });
            await ctx.close();
          }
        }
      }
    } finally {
      servers.forEach((s) => s.close());
    }
    const med = (xs) => xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)];
    const summary = {};
    for (const build of ["release", "dev"]) for (const signedIn of [false, true]) {
      const rs = results.filter((r) => r.build === build && r.signedIn === signedIn);
      summary[`${build}/${signedIn ? "home" : "login"}`] = {
        firstScreenMs: med(rs.map((r) => r.screen)), firstFocusMs: med(rs.map((r) => r.focus)),
        homeRailsMs: signedIn ? med(rs.map((r) => r.rails)) : undefined, wasmBytes: rs[0]?.wasmBytes,
      };
    }
    console.log(`[cold start 4x] ${JSON.stringify(summary)}`);
    await testInfo.attach("cold-start.json", { body: JSON.stringify({ summary, results }, null, 2), contentType: "application/json" });
    expect(summary["release/login"].firstScreenMs, "release first screen under 4× CPU").toBeLessThan(10_000);
    expect(summary["release/home"].homeRailsMs, "release Home rails under 4× CPU").toBeLessThan(15_000);
  });
});
