import { test, expect, screen } from "../lib/fixtures.js";

test("home rails render and the D-pad moves focus without losing it", async ({ page, seedSession, remote, focused, shot }) => {
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await expect(page.locator('[data-screen="home"] [data-focus-group] [data-focusable]').first()).toBeVisible();
  await shot("home");

  const start = await focused();
  expect(start, "something is focused on arrival").not.toBeNull();
  expect(start.count).toBe(1);

  const seen = new Set([JSON.stringify(start)]);
  for (const dir of ["Right", "Right", "Left", "Down", "Up", "Down", "Right", "Up", "Left"]) {
    await remote.press(dir);
    const f = await focused();
    expect(f, `focus survives ${dir}`).not.toBeNull();
    expect(f.count, `exactly one focused element after ${dir}`).toBe(1);
    expect(f.screen).toBe("home");
    seen.add(JSON.stringify(f));
  }
  await shot("home-after-dpad");
  expect(seen.size, "the D-pad actually moved focus somewhere").toBeGreaterThan(1);

  // Rails remember their position: Down then Up returns to the same card.
  const before = await focused();
  await remote.down();
  const moved = await focused();
  if (moved.group !== before.group) {
    await remote.up();
    expect((await focused()).text).toBe(before.text);
  }
});
