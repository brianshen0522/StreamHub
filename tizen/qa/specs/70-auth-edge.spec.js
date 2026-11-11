// D. Auth edge cases: wrong password (live server, one failed attempt),
// admin refusal and rate limit (simulated with page.route — never provoked
// for real), an admin token that slipped through, access-token expiry
// mid-session with a single-flight refresh, and refresh failures.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { credentials } from "../lib/env.js";
import { fakePairing, fulfillJson, requestLog } from "../lib/qa2.js";

test.beforeEach(async ({ page }) => { await fakePairing(page); });

async function typeLogin(page, remote, username, password) {
  // The password card is one step away from the pairing screen.
  await focusTo(page, remote, '[data-action="use-password"]', { dirs: ["Down", "Up"] });
  await remote.enter();
  await focusTo(page, remote, 'input[name="username"]');
  await page.keyboard.type(username);
  await focusTo(page, remote, 'input[name="password"]', { dirs: ["Down", "Right"] });
  await page.keyboard.type(password);
  await focusTo(page, remote, '[data-action="login"]', { dirs: ["Down", "Right"] });
  await remote.enter();
}
const errorText = (page) => page.locator('[data-screen="login"] .tv-login-form .tv-error');

test("wrong password: the server's message, still on Login, nothing stored", async ({ page, remote, shot }) => {
  await page.goto("/");
  await expect(screen(page, "login")).toBeVisible();
  const { username } = credentials();
  const wrong = `wrong-${Date.now()}`;
  await typeLogin(page, remote, username, wrong);
  await expect(errorText(page)).not.toBeEmpty();
  let msg = await errorText(page).innerText();
  if (/（50\d）/.test(msg)) {
    // The live server was restarting (see report: poster-proxy crash); try once more.
    await page.waitForTimeout(15_000);
    await focusTo(page, remote, '[data-action="login"]', { dirs: ["Down", "Right", "Up"] });
    await remote.enter();
    await page.waitForTimeout(2500);
    msg = await errorText(page).innerText();
  }
  expect(msg, "the 401 text, not a gateway error").not.toMatch(/（5\d\d）/);
  console.log(`[wrong password] "${msg}"`);
  expect(msg).not.toContain(wrong);
  await expect(screen(page, "login")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
  await expect(page.locator('[data-action="login"]')).toHaveText("登入");
  await shot("wrong-password");
});

test("admin refusal (403 as the server sends it to X-StreamHub-Client) is shown, in Chinese", async ({ page, remote, shot }) => {
  const MSG = "Administrator accounts cannot sign in on this app. Use a viewer account.";
  let sawHeader = null;
  await page.route(/\/api\/v1\/auth\/login$/, (route) => {
    sawHeader = route.request().headers()["x-streamhub-client"];
    return fulfillJson(route, 403, { error: MSG });
  });
  await page.goto("/");
  await typeLogin(page, remote, "someadmin", "irrelevant-password");
  // Server copy is English; the screen maps it (screens/common.rs friendly_error).
  await expect(errorText(page)).toContainText("管理員");
  expect(sawHeader).toBe("tv");
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
  await shot("admin-403");
});

test("an ADMIN session that slipped through (200, role ADMIN) is refused client-side", async ({ page, remote }) => {
  await page.route(/\/api\/v1\/auth\/login$/, (route) => fulfillJson(route, 200, {
    user: { id: "x", username: "admin", role: "ADMIN" }, accessToken: "a.b.c", refreshToken: "r",
  }));
  await page.goto("/");
  await typeLogin(page, remote, "admin", "irrelevant-password");
  await expect(errorText(page)).toContainText("管理員");
  await expect(screen(page, "login")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
});

test("429 rate limit: the message naming the wait is shown", async ({ page, remote, shot }) => {
  const MSG = "Too many failed attempts. Try again in 14 minutes.";
  await page.route(/\/api\/v1\/auth\/login$/, (route) => fulfillJson(route, 429, { error: MSG }, { "retry-after": "840" }));
  await page.goto("/");
  await typeLogin(page, remote, "someone", "irrelevant-password");
  await expect(errorText(page)).toHaveText("嘗試次數過多，請在 14 分鐘後再試");
  await shot("rate-limited");
});

test("access token expiry mid-session: three parallel 401s → exactly ONE refresh, then Home loads", async ({ page, seedSession, shot }) => {
  const once = new Set();
  const paths = ["/me/continue-watching", "/me/favorites", "/me/history"];
  await page.route(/\/api\/v1\/me\/(continue-watching|favorites|history)(\?|$)/, (route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api\/v1/, "");
    if (!once.has(p)) { once.add(p); return fulfillJson(route, 401, { error: "Unauthorized" }); }
    return route.fallback();
  });
  const log = requestLog(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await expect.poll(() => once.size, { timeout: 15_000 }).toBe(3);
  await expect(page.locator('[data-screen="home"] .tv-skeleton')).toHaveCount(0, { timeout: 20_000 });
  await page.waitForTimeout(1500);
  const refreshes = log.filter((r) => r.method === "POST" && r.url.endsWith("/api/v1/auth/refresh"));
  const retried = paths.map((p) => log.filter((r) => r.url.includes(`/api/v1${p}`)).length);
  console.log(`[single-flight] refreshes=${refreshes.length} requests per path=${retried.join(",")}`);
  expect(refreshes.length, "single-flight refresh").toBe(1);
  expect(retried, "each 401 retried once").toEqual([2, 2, 2]);
  await expect(page.locator('[data-screen="home"] .tv-row-caption.is-error')).toHaveCount(0);
  await expect(screen(page, "home")).toBeVisible();
  const s = await page.evaluate(() => JSON.parse(localStorage.getItem("streamhub.session")));
  expect(s?.access_token, "the rotated session was stored").toBeTruthy();
  await shot("home-after-refresh");
});

test("refresh rejected (401) → back to Login cleanly, session cleared, no panic", async ({ page, seedSession, shot }) => {
  await page.route(/\/api\/v1\/me\//, (route) => fulfillJson(route, 401, { error: "Unauthorized" }));
  await page.route(/\/api\/v1\/auth\/refresh$/, (route) => fulfillJson(route, 401, { error: "Invalid refresh token." }));
  const log = requestLog(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "login")).toBeVisible({ timeout: 20_000 });
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
  await page.waitForTimeout(1500);
  const refreshes = log.filter((r) => r.url.endsWith("/auth/refresh")).length;
  console.log(`[refresh rejected] refresh calls=${refreshes}`);
  expect(refreshes).toBeLessThanOrEqual(1);
  // A pairing code still comes up: the login screen is fully alive.
  await expect(page.locator("[data-user-code]")).toHaveText(/^[A-Z0-9]{4}-?[A-Z0-9]{4}$/, { timeout: 20_000 });
  await expect(page.locator(".is-focused")).toHaveCount(1);
  await shot("login-after-refresh-failure");
});

test("refresh with no answer (network) keeps the session and says offline", async ({ page, seedSession }) => {
  await page.route(/\/api\/v1\/me\/(continue-watching|favorites|history)(\?|$)/, (route) => fulfillJson(route, 401, { error: "Unauthorized" }));
  await page.route(/\/api\/v1\/auth\/refresh$/, (route) => route.abort("internetdisconnected"));
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await expect(page.locator('[data-screen="home"] .tv-row-caption.is-error').first()).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('[data-screen="home"] .tv-row-caption.is-error').first()).toContainText("無法連線");
  expect(await page.evaluate(() => !!localStorage.getItem("streamhub.session"))).toBeTruthy();
});

for (const status of [502, 429]) {
  test(`refresh answering ${status} must not sign the TV out`, async ({ page, seedSession }) => {
    // Regression: until 554d53a any non-offline refresh error cleared the
    // session, so a 502 during a server deploy, or a 429 caused by someone
    // else's typos on the household's address, signed the television out
    // although its refresh token was still good. Only 400/401/403 may.
    await page.route(/\/api\/v1\/me\/(continue-watching|favorites|history)(\?|$)/, (route) => fulfillJson(route, 401, { error: "Unauthorized" }));
    await page.route(/\/api\/v1\/auth\/refresh$/, (route) => status === 502
      ? route.fulfill({ status: 502, contentType: "text/html", body: "<html>502 Bad Gateway</html>", headers: { "access-control-allow-origin": "*" } })
      : fulfillJson(route, 429, { error: "Too many attempts." }, { "retry-after": "600" }));
    await seedSession();
    await page.goto("/");
    await page.waitForTimeout(4000);
    expect(await page.evaluate(() => !!localStorage.getItem("streamhub.session")), "session kept").toBeTruthy();
    await expect(screen(page, "home")).toBeVisible();
  });
}
