// Test fixtures: the TV remote, the focused element, step screenshots, the QA
// API client, and a guard that fails any test whose page panicked.
import { test as base, expect } from "@playwright/test";
import { QaApi } from "./api.js";
import { API_BASE } from "./env.js";

// Tizen key codes (tizen.tvinputdevice) and the `key` values the platform sends.
export const KEYS = {
  Up: [38, "ArrowUp"], Down: [40, "ArrowDown"], Left: [37, "ArrowLeft"], Right: [39, "ArrowRight"],
  Enter: [13, "Enter"], Back: [10009, "XF86Back"], PlayPause: [10252, "MediaPlayPause"],
  Exit: [10182, "XF86Exit"],
};

function makeRemote(page) {
  const press = async (name, { times = 1, gap = 120 } = {}) => {
    const [keyCode, key] = KEYS[name];
    for (let i = 0; i < times; i += 1) {
      await page.evaluate(({ keyCode, key }) => {
        const target = document.activeElement || document.body;
        for (const type of ["keydown", "keyup"]) {
          const ev = new KeyboardEvent(type, { key, bubbles: true, cancelable: true });
          Object.defineProperty(ev, "keyCode", { get: () => keyCode });
          Object.defineProperty(ev, "which", { get: () => keyCode });
          target.dispatchEvent(ev);
        }
      }, { keyCode, key });
      await page.waitForTimeout(gap);
    }
  };
  const remote = Object.fromEntries(Object.keys(KEYS).map((k) => [k.toLowerCase(), (o) => press(k, o)]));
  remote.press = press;
  return remote;
}

/** The element carrying `is-focused`: its text, tag and data-* attributes (null if none). */
export async function focused(page) {
  return page.evaluate(() => {
    const all = document.querySelectorAll(".is-focused");
    const el = all[0];
    if (!el) return null;
    return {
      count: all.length,
      tag: el.tagName.toLowerCase(),
      text: (el.innerText || el.value || "").trim(),
      name: el.getAttribute("name"),
      data: { ...el.dataset },
      group: el.closest("[data-focus-group]")?.dataset.focusGroup ?? null,
      screen: el.closest("[data-screen]")?.dataset.screen ?? null,
      isActive: document.activeElement === el || el.contains(document.activeElement),
    };
  });
}

/**
 * Walk the D-pad until the focused element matches `selector`. Tries each
 * direction in turn for up to `steps` presses; the remote is the only way a
 * person reaches anything, so the specs never click.
 */
export async function focusTo(page, remote, selector, { dirs = ["Down", "Right", "Up", "Left"], steps = 8 } = {}) {
  const matches = () => page.evaluate((sel) => !!document.querySelector(".is-focused")?.matches(sel), selector);
  if (await matches()) return true;
  for (const dir of dirs) {
    for (let i = 0; i < steps; i += 1) {
      await remote.press(dir);
      if (await matches()) return true;
    }
  }
  throw new Error(`could not reach ${selector} with the D-pad`);
}

export const test = base.extend({
  qaApi: [async ({}, use) => {
    const api = await QaApi.login();
    await use(api);
    await api.logout();
  }, { scope: "worker" }],

  // Sign the app in by writing a session the way session.rs persists it,
  // once per page (a later reload or sign-out is not re-seeded).
  seedSession: async ({ page }, use) => {
    await use(async () => {
      const fresh = await QaApi.login();
      const s = { access_token: fresh.session.accessToken, refresh_token: fresh.session.refreshToken, user: fresh.session.user };
      await page.addInitScript((raw) => {
        if (!sessionStorage.getItem("qa.seeded")) {
          localStorage.setItem("streamhub.session", raw);
          sessionStorage.setItem("qa.seeded", "1");
        }
      }, JSON.stringify(s));
    });
  },

  page: async ({ page }, use, testInfo) => {
    const errors = [];
    const panics = [];
    page.on("console", (msg) => {
      if (msg.type() !== "error") return;
      const text = msg.text();
      errors.push(text);
      if (text.includes("panicked")) panics.push(text);
    });
    page.on("pageerror", (err) => panics.push(`pageerror: ${err.message}`));
    await page.addInitScript((apiBase) => {
      localStorage.setItem("streamhub.apiBase", apiBase);
      localStorage.setItem("streamhub.mockPlayer", "1");
    }, API_BASE);
    await use(page);
    if (errors.length) {
      await testInfo.attach("console-errors.txt", { body: errors.join("\n"), contentType: "text/plain" });
    }
    expect(panics, "no Rust panics or uncaught page errors").toEqual([]);
  },

  remote: async ({ page }, use) => { await use(makeRemote(page)); },
  focused: async ({ page }, use) => { await use(() => focused(page)); },

  // shot("home") → test-results/<test>/NN-home.png, and attached to the report.
  shot: async ({ page }, use, testInfo) => {
    let n = 0;
    await use(async (name) => {
      n += 1;
      const file = testInfo.outputPath(`${String(n).padStart(2, "0")}-${name}.png`);
      await page.screenshot({ path: file });
      await testInfo.attach(name, { path: file, contentType: "image/png" });
    });
  },
});

export { expect };
export const screen = (page, name) => page.locator(`[data-screen="${name}"]`);
