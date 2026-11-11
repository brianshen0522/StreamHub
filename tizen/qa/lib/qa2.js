// Helpers for the 60–89 specs: focus audits, a greedy D-pad navigator, a
// seeded PRNG, sessions for any account, request logs, and a hermetic
// backend for one fake title (so player specs never write to the live
// account and never depend on a scraper being up).
import { QaApi } from "./api.js";
import { API_BASE } from "./env.js";
import { KEYS } from "./fixtures.js";

export const VIEW = { w: 1920, h: 1080 };
export const API_ORIGIN = new URL(API_BASE).origin;

/** mulberry32: the walk is reproducible from QA_SEED. */
export function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Sign `page` in as `api`'s account before the app loads (like seedSession). */
export async function seedAs(page, api) {
  const s = { access_token: api.session.accessToken, refresh_token: api.session.refreshToken, user: api.session.user };
  await page.addInitScript((raw) => {
    if (!sessionStorage.getItem("qa.seeded")) {
      localStorage.setItem("streamhub.session", raw);
      sessionStorage.setItem("qa.seeded", "1");
    }
  }, JSON.stringify(s));
}

/** A fresh session for `creds` (default: the general QA account). */
export async function freshSession(creds) {
  return creds ? QaApi.login({ creds }) : QaApi.login();
}

/** In-page helpers, installed before the app: element keys and the focus audit. */
export async function installProbes(page) {
  await page.addInitScript(() => {
    const path = (el) => {
      const parts = [];
      for (let n = el; n && n.nodeType === 1 && n !== document.body; n = n.parentElement) {
        const i = n.parentElement ? [...n.parentElement.children].indexOf(n) : 0;
        parts.unshift(`${n.tagName.toLowerCase()}:${i}`);
      }
      return parts.join(">");
    };
    window.__qaKey = (el) => {
      if (!el) return null;
      if (el.dataset.focusId) return `id:${el.dataset.focusId}`;
      if (el.dataset.action) return `action:${el.dataset.action}${el.dataset.index ? ":" + el.dataset.index : ""}`;
      return `path:${path(el)}`;
    };
    const usable = (el) => {
      const r = el.getBoundingClientRect();
      return el.isConnected && el.getAttribute("aria-disabled") !== "true" && !el.hasAttribute("disabled")
        && r.width > 0 && r.height > 0;
    };
    window.__qaScope = () => [...document.querySelectorAll("[data-focus-scope]")].filter((e) => {
      const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0;
    }).pop() || document;
    window.__qaFocusables = () => [...window.__qaScope().querySelectorAll("[data-focusable]")].filter(usable)
      .map((el) => {
        const r = el.getBoundingClientRect();
        return { key: window.__qaKey(el), x: r.x, y: r.y, w: r.width, h: r.height, text: (el.innerText || el.value || "").trim().slice(0, 40) };
      });
    window.__qaAudit = () => {
      const all = [...document.querySelectorAll(".is-focused")];
      const el = all[0];
      const out = { count: all.length, key: null, screen: null, problems: [] };
      if (all.length !== 1) out.problems.push(`is-focused count ${all.length}`);
      if (!el) return out;
      out.key = window.__qaKey(el);
      out.text = (el.innerText || el.value || "").trim().slice(0, 40);
      out.screen = el.closest("[data-screen]")?.dataset.screen ?? null;
      const active = document.activeElement;
      out.active = active === el || el.contains(active);
      if (!out.active) out.problems.push(`activeElement is ${active ? active.tagName + "." + active.className : "null"}`);
      const r = el.getBoundingClientRect();
      out.rect = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
      // A focused card is scaled 1.08 and lifted: allow a small overhang.
      const slack = Math.max(24, r.width * 0.06);
      if (r.width === 0 || r.height === 0) out.problems.push("zero-size");
      if (r.left < -slack || r.top < -slack || r.right > innerWidth + slack || r.bottom > innerHeight + slack) {
        out.problems.push(`outside viewport ${JSON.stringify(out.rect)}`);
      }
      // Effective opacity (the hidden top bar fades to 0) and visibility.
      let op = 1;
      for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
        const cs = getComputedStyle(n);
        op *= Number(cs.opacity);
        if (cs.visibility === "hidden" || cs.display === "none") op = 0;
      }
      out.opacity = Math.round(op * 100) / 100;
      if (op < 0.3) out.problems.push(`effective opacity ${out.opacity}`);
      // Something else painted over its centre (e.g. under the top bar)?
      const cx = Math.min(innerWidth - 1, Math.max(0, r.left + r.width / 2));
      const cy = Math.min(innerHeight - 1, Math.max(0, r.top + r.height / 2));
      const hit = document.elementFromPoint(cx, cy);
      out.hit = hit ? window.__qaKey(hit.closest("[data-focusable]") || hit) : null;
      if (hit && !el.contains(hit) && !hit.contains(el)) {
        const cover = hit.closest("[class]");
        out.problems.push(`covered by ${cover ? cover.tagName.toLowerCase() + "." + [...cover.classList].join(".") : hit.tagName}`);
      }
      return out;
    };
  });
}

/** The audit after focus has had time to settle (transitions are 240 ms). */
export async function settledAudit(page, { timeout = 900 } = {}) {
  const t0 = Date.now();
  let a;
  for (;;) {
    a = await page.evaluate(() => window.__qaAudit());
    if (!a.problems.length || Date.now() - t0 > timeout) return a;
    await page.waitForTimeout(80);
  }
}

export const focusables = (page) => page.evaluate(() => window.__qaFocusables());
export const focusedKey = (page) => page.evaluate(() => window.__qaKey(document.querySelector(".is-focused")));

/**
 * Walk toward the focusable whose key is `key` by geometry: change rows first
 * (Up/Down), then move along the row. Returns the presses used, or -1.
 */
export async function navigateTo(page, remote, key, { max = 60, gap = 200 } = {}) {
  let stuck = 0;
  const tried = new Set();
  for (let i = 0; i < max; i += 1) {
    const st = await page.evaluate((k) => {
      const cur = document.querySelector(".is-focused");
      const items = window.__qaFocusables();
      const t = items.find((x) => x.key === k);
      if (!cur || !t) return { missing: !t, curKey: cur ? window.__qaKey(cur) : null };
      const r = cur.getBoundingClientRect();
      return { curKey: window.__qaKey(cur), c: { x: r.x, y: r.y, w: r.width, h: r.height }, t };
    }, key);
    if (st.curKey === key) return i;
    if (st.missing || !st.c) return -1;
    const { c, t } = st;
    const overlapV = Math.min(c.y + c.h, t.y + t.h) - Math.max(c.y, t.y) > Math.min(c.h, t.h) * 0.3;
    const dx = t.x + t.w / 2 - (c.x + c.w / 2);
    const dy = t.y + t.h / 2 - (c.y + c.h / 2);
    let order = overlapV ? [dx > 0 ? "Right" : "Left", dy > 0 ? "Down" : "Up"]
      : [dy > 0 ? "Down" : "Up", dx > 0 ? "Right" : "Left"];
    order = [...order, ...["Down", "Up", "Right", "Left"].filter((d) => !order.includes(d))];
    const dir = order.find((d) => !tried.has(`${st.curKey}|${d}`)) || order[0];
    tried.add(`${st.curKey}|${dir}`);
    await remote.press(dir, { gap });
    const after = await focusedKey(page);
    stuck = after === st.curKey ? stuck + 1 : 0;
    if (stuck >= 4) return -1;
  }
  return -1;
}

/**
 * Every usable focusable on the current screen (inside the active modal
 * scope, if any), reached by D-pad. Returns { total, reached, unreachable[] }.
 */
export async function reachAll(page, remote, { maxPerTarget = 40 } = {}) {
  const seen = new Set([await focusedKey(page)]);
  const unreachable = [];
  const list = await focusables(page);
  for (const item of list) {
    if (seen.has(item.key)) continue;
    const n = await navigateTo(page, remote, item.key, { max: maxPerTarget });
    if (n < 0) unreachable.push(item);
    else seen.add(item.key);
    // Everything focus passed through on the way counts too.
    seen.add(await focusedKey(page));
  }
  return { total: list.length, reached: list.length - unreachable.length, unreachable };
}

/** Records every request to our server: method, path, headers, body. */
export function requestLog(page) {
  const log = [];
  page.on("request", async (r) => {
    const url = r.url();
    const entry = { url, method: r.method(), type: r.resourceType(), headers: {}, at: Date.now() };
    log.push(entry);
    try { entry.headers = await r.allHeaders(); } catch { /* gone */ }
    try { entry.body = r.postDataJSON(); } catch { entry.body = r.postData(); }
  });
  return log;
}

/** Fail-safe for the account whose library must stay empty: abort any library write. */
export async function forbidLibraryWrites(page) {
  const attempts = [];
  await page.route(/\/api\/v1\/me\//, (route) => {
    const m = route.request().method();
    if (m === "GET" || m === "HEAD") return route.fallback();
    attempts.push(`${m} ${route.request().url()}`);
    return route.abort();
  });
  return attempts;
}

const json = (route, status, body, headers = {}) => route.fulfill({
  status, contentType: "application/json", headers: { "access-control-allow-origin": "*", ...headers }, body: JSON.stringify(body),
});
export { json as fulfillJson };

/**
 * One fake title, served by intercepting the five routes Detail and the
 * player use. Progress PUTs are recorded (never reach the server) and fed
 * back to GET /me/progress, so resume can be checked end to end. The title
 * is found by searching `query` (the search for `provider` answers it; every
 * other provider answers empty).
 */
export async function hermeticTitle(page, opts = {}) {
  const o = {
    provider: "dramasq",
    url: "https://dramasq.qa.invalid/detail/qa-hermetic.html",
    title: "QA 測試劇",
    query: "QA測試劇",
    kind: "series",
    episodes: ["第01集", "第02集", "第03集"],
    durationSeconds: 1500,
    sources: 1,
    progress: [],
    ...opts,
  };
  const state = { puts: [], progress: [...o.progress], sourceCalls: 0, itemCalls: 0, prefPosts: 0 };
  const ndjson = (route) => {
    state.sourceCalls += 1;
    const lines = Array.from({ length: o.sources }, (_, i) => ({
      sourceLabel: `QA-${i + 1}`,
      directUrl: `https://cdn${i + 1}.qa.invalid/hls/${encodeURIComponent(o.title)}/index.m3u8`,
      proxyUrl: `/api/stream?target=${encodeURIComponent(`https://cdn${i + 1}.qa.invalid/hls/index.m3u8`)}`,
      durationSeconds: o.durationSeconds,
    }));
    return route.fulfill({
      status: 200, contentType: "application/x-ndjson", headers: { "access-control-allow-origin": "*" },
      body: lines.map((l) => JSON.stringify(l)).join("\n") + "\n",
    });
  };
  await page.route(/\/api\/v1\/(search|item|sources|check-sources|episodes|me\/progress|me\/source-preference)(\?|$)/, async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname.replace(/^\/api\/v1/, "");
    if (p === "/search") {
      const prov = u.searchParams.get("provider");
      const items = prov === o.provider && u.searchParams.get("q") === o.query
        ? [{ provider: o.provider, title: o.title, url: o.url, posterUrl: "", mediaType: o.kind === "movie" ? "movie" : "tv" }]
        : [];
      return json(route, 200, { results: [{ provider: prov, items }] });
    }
    if (p === "/item") {
      if (u.searchParams.get("url") !== o.url) return route.fallback();
      state.itemCalls += 1;
      if (o.kind === "movie") {
        return json(route, 200, { title: o.title, posterUrl: "", mediaType: "movie",
          streams: [{ label: "QA-1", url: `https://cdn1.qa.invalid/hls/movie.m3u8` }] });
      }
      return json(route, 200, { title: o.title, posterUrl: "", mediaType: "tv", episodes: o.episodes });
    }
    if (p === "/sources" || p === "/check-sources") return ndjson(route);
    if (p === "/episodes") return json(route, 200, { episodes: o.episodes });
    if (p === "/me/source-preference") {
      if (req.method() === "GET") return json(route, 200, { preference: null });
      state.prefPosts += 1;
      return json(route, 200, { ok: true });
    }
    if (p === "/me/progress") {
      if (req.method() === "PUT") {
        const b = req.postDataJSON();
        state.puts.push({ at: Date.now(), body: b });
        const row = {
          ...b, id: `qa-${b.episodeLabel}`,
          progressPercent: b.durationSeconds ? (b.positionSeconds / b.durationSeconds) * 100 : 0,
          isCompleted: b.durationSeconds ? b.positionSeconds / b.durationSeconds >= 0.95 : false,
          lastWatchedAt: new Date().toISOString(),
        };
        state.progress = state.progress.filter((r) => (r.episodeLabel || "") !== (b.episodeLabel || ""));
        state.progress.unshift(row);
        return json(route, 200, { progress: row });
      }
      if (req.method() === "GET" && u.searchParams.get("itemUrl") === o.url) {
        return json(route, 200, { progress: state.progress });
      }
      return route.fallback();
    }
    return route.fallback();
  });
  return { opts: o, state };
}

/** Search for `q` from Home with the remote; resolves once rails exist. */
export async function searchFromHome(page, remote, focusTo, q) {
  // Let Home finish loading first: its first load moves focus from 搜尋 to the
  // first card (see 61-home-focus-steal), which would swallow our OK.
  await page.locator('[data-screen="home"]').waitFor();
  await page.waitForFunction(() => !document.querySelector('[data-screen="home"] .tv-skeleton'), null, { timeout: 30_000 });
  await page.waitForTimeout(300);
  await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"], steps: 10 });
  await remote.enter();
  await page.locator('[data-screen="search"]').waitFor();
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up", "Left"] });
  await page.keyboard.type(q);
  await remote.enter();
  await page.locator('[data-screen="search"] [data-provider]').first().waitFor({ timeout: 45_000 });
}

/** Mock-player knobs, set before the app loads. */
export async function mockPlayer(page, knobs = {}) {
  await page.addInitScript((k) => {
    for (const [key, v] of Object.entries(k)) {
      if (v === null || v === undefined) localStorage.removeItem(`streamhub.${key}`);
      else localStorage.setItem(`streamhub.${key}`, String(v));
    }
  }, knobs);
}

/** Dispatch a raw key with a Tizen keyCode (one not in KEYS). */
export async function pressCode(page, keyCode, key = "Unidentified") {
  await page.evaluate(({ keyCode, key }) => {
    const target = document.activeElement || document.body;
    for (const type of ["keydown", "keyup"]) {
      const ev = new KeyboardEvent(type, { key, bubbles: true, cancelable: true });
      Object.defineProperty(ev, "keyCode", { get: () => keyCode });
      Object.defineProperty(ev, "which", { get: () => keyCode });
      target.dispatchEvent(ev);
    }
  }, { keyCode, key });
}

export { KEYS };

/**
 * Give the account `n` favourites from a live search (so Home has rails to
 * walk), returning a function that removes exactly those again.
 */
export async function seedFavorites(api, { q = "黑暗榮耀", n = 8 } = {}) {
  const { providers } = await api.json("GET", "/me/providers");
  const items = [];
  for (const p of providers) {
    if (items.length >= n) break;
    const r = await api.call("GET", `/search?q=${encodeURIComponent(q)}&provider=${encodeURIComponent(p.key)}`);
    for (const it of r.json?.results?.[0]?.items || []) {
      if (items.length < n) items.push({ ...it, provider: it.provider || p.key });
    }
  }
  const before = new Set((await api.json("GET", "/me/favorites")).favorites.map((f) => f.id));
  for (const it of items) {
    await api.call("POST", "/me/favorites", {
      providerKey: it.provider, mediaType: ["movie", "tv"].includes(it.mediaType) ? it.mediaType : "unknown",
      title: it.title, posterUrl: it.posterUrl || "", itemUrl: it.url, seasonUrl: "", episodeLabel: "",
    });
  }
  const created = (await api.json("GET", "/me/favorites")).favorites.filter((f) => !before.has(f.id));
  return {
    count: created.length,
    rows: created,
    remove: async () => {
      const now = (await api.json("GET", "/me/favorites")).favorites;
      for (const f of now) if (!before.has(f.id)) await api.call("DELETE", `/me/favorites/${f.id}`);
    },
  };
}

/**
 * Answer the pairing calls locally (a fake code that stays pending), so a
 * spec that merely passes through Login does not spend the server's
 * 30-starts-per-15-minutes budget the other suites share.
 */
export async function fakePairing(page) {
  await page.route(/\/api\/v1\/auth\/device\/(start|poll)$/, (route) => {
    if (route.request().url().endsWith("/start")) {
      return json(route, 200, {
        deviceCode: "qa-fake-device-code", userCode: "QAQA-TEST",
        verificationUrl: "https://example.invalid/pair", verificationUrlComplete: "https://example.invalid/pair?code=QAQA-TEST",
        expiresInSeconds: 600, intervalSeconds: 5,
      });
    }
    return json(route, 200, { status: "pending" });
  });
}
