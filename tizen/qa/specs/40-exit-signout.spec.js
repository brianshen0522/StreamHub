import { test, expect, focusTo, screen } from "../lib/fixtures.js";

test("Back on Home opens the exit prompt with 取消 focused", async ({ page, seedSession, remote, focused, shot }) => {
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await remote.back();
  await expect(screen(page, "exit")).toBeVisible();
  const f = await focused();
  expect(f.text).toBe("取消");
  expect(f.data.action).toBe("exit-cancel");
  await shot("exit-prompt");
  await remote.enter();
  await expect(screen(page, "exit")).toBeHidden();
  await expect(screen(page, "home")).toBeVisible();
});

test("signing out returns to Login", async ({ page, seedSession, remote, shot }) => {
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await focusTo(page, remote, '[data-action="signout"]', { dirs: ["Up", "Right", "Down", "Left"], steps: 12 });
  await remote.enter();
  // A confirmation, if the UI asks for one.
  const confirm = page.locator('[data-action="signout-confirm"]');
  if (await confirm.isVisible().catch(() => false)) {
    await focusTo(page, remote, '[data-action="signout-confirm"]', { dirs: ["Left", "Right"] });
    await remote.enter();
  }
  await expect(screen(page, "login")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("streamhub.session"))).toBeNull();
  await shot("login-after-signout");
});
