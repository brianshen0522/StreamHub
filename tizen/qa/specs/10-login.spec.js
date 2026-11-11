import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { credentials } from "../lib/env.js";

test("login with username and password", async ({ page, remote, focused, shot }) => {
  await page.goto("/");
  await expect(screen(page, "login")).toBeVisible();
  await shot("login");

  const { username, password } = credentials();
  // Text goes in the way the IME delivers it: into the focused field.
  await focusTo(page, remote, 'input[name="username"]');
  expect((await focused()).isActive, "the focused field also holds DOM focus (IME needs it)").toBeTruthy();
  await page.keyboard.type(username);
  await focusTo(page, remote, 'input[name="password"]', { dirs: ["Down", "Right"] });
  await page.keyboard.type(password);
  await shot("credentials-filled");
  await focusTo(page, remote, '[data-action="login"]', { dirs: ["Down", "Right"] });
  await remote.enter();

  await expect(screen(page, "home")).toBeVisible();
  await shot("home-after-login");
});

test("login by pairing: the TV shows a code, a phone approves it", async ({ page, remote, qaApi, shot }, testInfo) => {
  // Every start/poll answer, attached to the report: when this fails, the
  // first question is whether the TV ever heard "approved".
  const polls = [];
  page.on("response", async (r) => {
    if (!/\/auth\/device\/(start|poll)$/.test(r.url())) return;
    let status = "";
    try { status = (await r.json()).status || "(start)"; } catch { status = "(no json)"; }
    polls.push(`${new Date().toISOString()} ${r.url().split("/api")[1]} HTTP ${r.status()} ${status}`);
  });
  page.on("requestfailed", (r) => {
    if (/\/auth\/device\//.test(r.url())) polls.push(`${new Date().toISOString()} FAILED ${r.url()} ${r.failure()?.errorText}`);
  });
  const attachPolls = () => testInfo.attach("device-polls.txt", { body: polls.join("\n"), contentType: "text/plain" });
  await page.goto("/");
  await expect(screen(page, "login")).toBeVisible();
  // The code may be on the login screen already, or behind a "pair" choice.
  const code = page.locator("[data-user-code]");
  if (!(await code.isVisible().catch(() => false))) {
    await focusTo(page, remote, '[data-action="pair"]');
    await remote.enter();
  }
  await expect(code).toHaveText(/^[A-Z0-9]{4}-?[A-Z0-9]{4}$/);
  const userCode = (await code.innerText()).trim();
  await shot("pairing-code");

  const pending = await qaApi.json("GET", `/auth/device/pending?code=${encodeURIComponent(userCode)}`);
  expect(pending.clientKind).toBe("tv");
  await qaApi.json("POST", "/auth/device/approve", { code: userCode });
  polls.push(`${new Date().toISOString()} approved ${userCode} via API`);

  try {
    await expect(screen(page, "home")).toBeVisible({ timeout: 20_000 });
  } finally {
    await attachPolls();
  }
  await shot("home-after-pairing");
});
