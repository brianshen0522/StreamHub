// The player under the M7's minimal remote: D-pad, OK, Back, play/pause.
// Holds are real key repeats (keydown with repeat:true every ~50 ms, then a
// keyup), dispatched inside the page so their timing is not at the mercy of
// the test runner. Playback is started by a cast `play` from a second session,
// as a phone would, so this spec does not depend on the browse screens.
import { test, expect, screen } from "../lib/fixtures.js";
import { QaApi } from "../lib/api.js";
import { API_BASE } from "../lib/env.js";

const PROVIDER = "777tv";
const ITEM = "https://777tv.ai/vod/detail/id/231767.html";
const TITLE = "黑暗榮耀";
const CODES = { Left: [37, "ArrowLeft"], Right: [39, "ArrowRight"], Up: [38, "ArrowUp"], Down: [40, "ArrowDown"],
  Enter: [13, "Enter"], Back: [10009, "XF86Back"], PlayPause: [10252, "MediaPlayPause"] };

/** keydown (optionally repeat) or keyup, on the focused element, like the fixture's remote. */
function send(page, name, type, repeat = false) {
  const [keyCode, key] = CODES[name];
  return page.evaluate(({ keyCode, key, type, repeat }) => {
    const ev = new KeyboardEvent(type, { key, repeat, bubbles: true, cancelable: true });
    Object.defineProperty(ev, "keyCode", { get: () => keyCode });
    Object.defineProperty(ev, "which", { get: () => keyCode });
    (document.activeElement || document.body).dispatchEvent(ev);
  }, { keyCode, key, type, repeat });
}
const tap = async (page, name) => { await send(page, name, "keydown"); await send(page, name, "keyup"); await page.waitForTimeout(80); };

/**
 * Hold a key for `ms`: one keydown, repeats every 50 ms, then keyup unless
 * `release` is false. Runs in the page; returns a promise to await later so
 * the caller can observe the screen mid-hold.
 */
function hold(page, name, ms, { release = true } = {}) {
  const [keyCode, key] = CODES[name];
  return page.evaluate(({ keyCode, key, ms, release }) => new Promise((done) => {
    const fire = (type, repeat) => {
      const ev = new KeyboardEvent(type, { key, repeat, bubbles: true, cancelable: true });
      Object.defineProperty(ev, "keyCode", { get: () => keyCode });
      Object.defineProperty(ev, "which", { get: () => keyCode });
      (document.activeElement || document.body).dispatchEvent(ev);
    };
    fire("keydown", false);
    const t0 = performance.now();
    const iv = setInterval(() => {
      if (performance.now() - t0 >= ms) {
        clearInterval(iv);
        if (release) fire("keyup", false);
        done();
        return;
      }
      fire("keydown", true);
    }, 50);
  }), { keyCode, key, ms, release });
}

test("player remote: scrub holds, OK feedback and long press, episode strip, Back layers, up-next, keys while loading",
  async ({ page, seedSession, qaApi }) => {
    test.setTimeout(240_000);
    await seedSession();
    await page.addInitScript(() => {
      localStorage.setItem("streamhub.mockDuration", "1500");
      // Test-only: count the seeks the player actually sends.
      const wrap = () => {
        const p = window.StreamHubPlayer;
        if (!p || p.__counted) return setTimeout(wrap, 20);
        const seek = p.seek;
        window.__seeks = [];
        p.seek = (ms) => { window.__seeks.push(ms); return seek(ms); };
        p.__counted = true;
      };
      wrap();
    });
    // 第02集 has history, so choosing it from the strip resumes (600 → 570).
    await qaApi.call("PUT", "/me/progress", {
      providerKey: PROVIDER, mediaType: "tv", title: TITLE, posterUrl: "", itemUrl: ITEM, detailUrl: ITEM,
      seasonUrl: "", episodeLabel: "第02集", sourceLabel: "", positionSeconds: 600, durationSeconds: 1500,
    });

    let ws = null;
    let phone = null;
    try {
      await page.goto("/");
      await expect(screen(page, "home")).toBeVisible({ timeout: 20_000 });

      // Source probes run live against the provider's CDNs and now and then
      // come back empty; ask again before calling it a failure.
      let line = null;
      for (let attempt = 0; attempt < 4 && !line; attempt += 1) {
        if (attempt) await page.waitForTimeout(3000);
        const sources = await fetch(`${API_BASE}/api/v1/sources?provider=${PROVIDER}&sourceUrl=${encodeURIComponent(ITEM)}&episode=${encodeURIComponent("第01集")}`,
          { headers: { Authorization: `Bearer ${qaApi.session.accessToken}` } }).then((r) => r.text());
        line = sources.split("\n").filter(Boolean).map((l) => JSON.parse(l)).find((l) => l.directUrl) || null;
      }
      expect(line, "a playable 777tv source").toBeTruthy();

      const tvToken = await page.evaluate(() => JSON.parse(localStorage.getItem("streamhub.session")).access_token);
      const tvSid = JSON.parse(Buffer.from(tvToken.split(".")[1], "base64url").toString()).sid;
      phone = await QaApi.login({ client: "android" });
      ws = new WebSocket(`${API_BASE.replace(/^http/, "ws")}/api/realtime`);
      let listed = false;
      ws.onmessage = (e) => { const f = JSON.parse(e.data); if (f.receivers) listed = f.receivers.some((r) => r.sessionId === tvSid); };
      await new Promise((r) => { ws.onopen = r; });
      ws.send(JSON.stringify({ type: "auth", token: phone.session.accessToken }));
      await expect.poll(() => listed, { timeout: 15_000 }).toBe(true);
      const command = (c) => ws.send(JSON.stringify({ type: "command", to: tvSid, command: c }));
      const play = (extra = {}) => command({ action: "play", playback: {
        streamUrl: line.directUrl, provider: PROVIDER, itemUrl: ITEM, title: TITLE, subtitle: line.sourceLabel,
        posterUrl: "", episodeLabel: "第01集", nextEpisodeLabel: "第02集", episodeUrl: "", positionMs: 300_000, ...extra,
      } });

      const player = screen(page, "player");
      const pos = async () => Number(await player.getAttribute("data-position"));
      const seeks = () => page.evaluate(() => window.__seeks.length);
      const playing = () => expect(player).toHaveAttribute("data-state", "playing", { timeout: 20_000 });

      await test.step("keys while loading are applied once the player is ready", async () => {
        await page.evaluate(() => localStorage.setItem("streamhub.mockOpenDelay", "2500"));
        play();
        await expect(player).toHaveAttribute("data-state", "loading", { timeout: 15_000 });
        await tap(page, "Right"); await tap(page, "Right"); await tap(page, "Right");
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-state", "paused", { timeout: 10_000 });
        expect(await pos(), "three taps during loading = +30 s").toBeGreaterThanOrEqual(329);
        expect(await pos()).toBeLessThanOrEqual(332);
        await page.evaluate(() => localStorage.removeItem("streamhub.mockOpenDelay"));
        await tap(page, "Enter");
        await playing();
      });

      await test.step("taps accumulate ±10 s into one seek", async () => {
        await page.waitForTimeout(4600); // let the bar hide
        const before = await pos();
        const n = await seeks();
        await tap(page, "Right"); await tap(page, "Right"); await tap(page, "Left");
        await page.waitForTimeout(700);
        expect(await seeks() - n, "one seek for the burst").toBe(1);
        expect(Math.abs((await pos()) - (before + 10))).toBeLessThanOrEqual(2);
      });

      await test.step("holding Right scrubs: accelerates, playback runs, one seek on release", async () => {
        await page.waitForTimeout(4600);
        const before = await pos();
        const n = await seeks();
        const h = hold(page, "Right", 2000);
        await expect(player).toHaveAttribute("data-scrub", "1", { timeout: 1500 });
        await expect(page.locator('[data-role="seek-target"]')).toBeVisible();
        const mid1 = await pos();
        await page.waitForTimeout(1100);
        expect(await pos(), "playback keeps running underneath").toBeGreaterThan(mid1);
        expect(await seeks() - n, "nothing sent while scrubbing").toBe(0);
        await h;
        await expect(player).not.toHaveAttribute("data-scrub", "1");
        expect(await seeks() - n, "one seek on release").toBe(1);
        const jump = (await pos()) - before;
        // 10 s steps for the first second, then 30 s: far beyond 2 s of taps.
        expect(jump, `2 s hold jumped ${jump}s`).toBeGreaterThan(150);

        await page.waitForTimeout(4600);
        const b2 = await pos();
        await hold(page, "Left", 500);
        const short = b2 - (await pos());
        expect(short, `0.5 s hold went back ${short}s`).toBeGreaterThan(15);
        expect(short, "short holds stay in 10 s steps").toBeLessThan(80);
      });

      await test.step("OK while scrubbing commits now; Back cancels", async () => {
        await page.waitForTimeout(4600);
        let n = await seeks();
        let before = await pos();
        const h1 = hold(page, "Right", 700, { release: false });
        await h1;
        await expect(player).toHaveAttribute("data-scrub", "1");
        await send(page, "Enter", "keydown");
        await expect(player).not.toHaveAttribute("data-scrub", "1");
        expect(await seeks() - n, "OK committed the scrub").toBe(1);
        expect((await pos()) - before).toBeGreaterThan(40);
        await send(page, "Enter", "keyup");
        await send(page, "Right", "keyup");
        await page.waitForTimeout(300);
        expect(await player.getAttribute("data-state"), "OK's release did not also toggle").toBe("playing");

        await page.waitForTimeout(4600);
        n = await seeks();
        before = await pos();
        await hold(page, "Left", 700, { release: false });
        await expect(player).toHaveAttribute("data-scrub", "1");
        await tap(page, "Back");
        await expect(player).not.toHaveAttribute("data-scrub", "1");
        await send(page, "Left", "keyup");
        await page.waitForTimeout(500);
        expect(await seeks() - n, "Back cancelled: no seek").toBe(0);
        expect(Math.abs((await pos()) - before)).toBeLessThanOrEqual(3);
        await expect(player, "Back took one layer only").toBeVisible();
      });

      await test.step("OK when hidden toggles with a glyph and the bar; long OK opens the sources", async () => {
        await page.waitForTimeout(4600);
        await expect(player).toHaveAttribute("data-controls", "hidden");
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-state", "paused");
        await expect(page.locator('[data-overlay="flash"][data-glyph="pause"]')).toBeVisible();
        await expect(page.locator(".vp-chrome.vp-show")).toBeVisible();
        await expect(page.locator('[data-overlay="flash"]')).toHaveCount(0, { timeout: 2000 });
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-state", "playing");
        await expect(page.locator('[data-overlay="flash"][data-glyph="play"]')).toBeVisible();

        await page.waitForTimeout(4600);
        await hold(page, "Enter", 1000);
        await expect(page.locator('[data-overlay="sources"]')).toBeVisible();
        expect(await player.getAttribute("data-state"), "a long press does not toggle").toBe("playing");
        await tap(page, "Back");
        await expect(page.locator('[data-overlay="sources"]')).toHaveCount(0);
        await expect(player).toBeVisible();
      });

      await test.step("Back while paused with controls: hide controls, then (later) leave", async () => {
        await tap(page, "Back"); // the picker's close raised the controls
        await expect(player).toHaveAttribute("data-controls", "hidden");
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-state", "paused");
        await tap(page, "Up");
        await expect(player).toHaveAttribute("data-controls", "visible");
        await tap(page, "Back");
        await expect(player).toHaveAttribute("data-controls", "hidden");
        await expect(player).toBeVisible();
        await tap(page, "PlayPause");
        await playing();
      });

      await test.step("Down opens the episode strip; Left/Right browse; Up and Back close it", async () => {
        await page.waitForTimeout(4600);
        await tap(page, "Down");
        const strip = page.locator('[data-overlay="episodes"]');
        await expect(strip).toBeVisible();
        const focusedIndex = () => strip.locator(".vp-ep.is-focused").getAttribute("data-index");
        expect(await focusedIndex(), "the current episode is focused").toBe("0");
        await tap(page, "Right");
        expect(await focusedIndex()).toBe("1");
        await tap(page, "Right");
        expect(await focusedIndex(), "stops at the last").toBe("1");
        await tap(page, "Left");
        expect(await focusedIndex()).toBe("0");
        await tap(page, "Up");
        await expect(strip).toHaveCount(0);
        await tap(page, "Down");
        await expect(strip).toBeVisible();
        await tap(page, "Back");
        await expect(strip).toHaveCount(0);
        await expect(player, "Back closed the strip only").toBeVisible();
      });

      await test.step("up-next: Left/Right do nothing, Down dismisses, OK plays now", async () => {
        command({ action: "seek", positionMs: 1_420_000 });
        const card = page.locator('[data-overlay="up-next"]');
        await expect(card).toBeVisible({ timeout: 10_000 });
        const n = await seeks();
        await tap(page, "Left"); await tap(page, "Right");
        await page.waitForTimeout(600);
        await expect(card).toBeVisible();
        expect(await seeks() - n, "no seek from Left/Right on the card").toBe(0);
        await tap(page, "Down");
        await expect(card).toHaveCount(0);
        await expect(player).toBeVisible();
        command({ action: "seek", positionMs: 1_000_000 });
        await page.waitForTimeout(600);
        command({ action: "seek", positionMs: 1_420_000 });
        await expect(card).toBeVisible({ timeout: 10_000 });
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-episode", "第02集", { timeout: 20_000 });
      });

      await test.step("OK on a strip tile plays that episode from its resume point", async () => {
        play({ positionMs: 100_000 });
        await expect(player).toHaveAttribute("data-episode", "第01集", { timeout: 20_000 });
        await playing();
        // Playing 第02集 above rewrote its progress; put the 600 s back.
        await qaApi.call("PUT", "/me/progress", {
          providerKey: PROVIDER, mediaType: "tv", title: TITLE, posterUrl: "", itemUrl: ITEM, detailUrl: ITEM,
          seasonUrl: "", episodeLabel: "第02集", sourceLabel: "", positionSeconds: 600, durationSeconds: 1500,
        });
        await page.waitForTimeout(4600);
        await tap(page, "Down");
        await expect(page.locator('[data-overlay="episodes"]')).toBeVisible();
        await expect(page.locator('.vp-ep[data-index="1"] .vp-ep-meta'), "the strip shows 第02集's progress").toHaveText("已看 40%");
        await tap(page, "Right");
        await tap(page, "Enter");
        await expect(player).toHaveAttribute("data-episode", "第02集", { timeout: 20_000 });
        await playing();
        const p = await pos();
        expect(p, "resumed 30 s before the recorded 600 s").toBeGreaterThanOrEqual(569);
        expect(p).toBeLessThanOrEqual(575);
      });

      await test.step("a movie: Down shows the controls instead", async () => {
        play({ episodeLabel: "", nextEpisodeLabel: "", positionMs: 0 });
        await expect(player).toHaveAttribute("data-episode", "", { timeout: 20_000 });
        await playing();
        await page.waitForTimeout(4600);
        await tap(page, "Down");
        await expect(player).toHaveAttribute("data-controls", "visible");
        await expect(page.locator('[data-overlay="episodes"]')).toHaveCount(0);
        await tap(page, "Back");
        await expect(player).toHaveAttribute("data-controls", "hidden");
        await tap(page, "Back");
        await expect(player).toBeHidden();
      });
    } finally {
      ws?.close();
      await phone?.logout().catch(() => {});
      await page.evaluate(() => localStorage.removeItem("streamhub.mockOpenDelay")).catch(() => {});
      await page.waitForTimeout(1500).catch(() => {});
      await qaApi.call("DELETE", "/me/progress", { scope: "title", providerKey: PROVIDER, itemUrl: ITEM, title: TITLE });
    }
  });

test("tizen-keys registers the media keys and the extra keys, each guarded, never Back", async ({ page }) => {
  await page.addInitScript(() => {
    window.__registered = [];
    window.tizen = { tvinputdevice: {
      // A set without "Info": it must be skipped, not sink the batch.
      getSupportedKeys: () => ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaRewind",
        "MediaFastForward", "MediaTrackPrevious", "MediaTrackNext", "ChannelUp", "ChannelDown", "ColorF0Red",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "Back", "Exit"].map((name) => ({ name })),
      registerKeyBatch: (names, ok) => { window.__registered.push(...names); ok && ok(); },
      registerKey: (name) => window.__registered.push(name),
    } };
  });
  await page.goto("/");
  const got = await page.evaluate(() => window.__registered);
  for (const k of ["MediaPlayPause", "MediaTrackNext", "ChannelUp", "ChannelDown", "ColorF0Red", "0", "9"]) {
    expect(got, `registers ${k}`).toContain(k);
  }
  expect(got, "unsupported keys are skipped").not.toContain("Info");
  expect(got, "Back is never registered").not.toContain("Back");
});
