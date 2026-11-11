// A. Focus must not be taken away from the viewer. Home's first load moves
// focus from 搜尋 (the autofocus) to the first card once the library
// arrives — fine while the viewer has not touched the remote, wrong once
// they have: an OK meant for 搜尋 then opens whatever title is first.
import { test, expect, screen } from "../lib/fixtures.js";
import { installProbes, seedFavorites } from "../lib/qa2.js";

let favs;
test.beforeAll(async ({ qaApi }) => { favs = await seedFavorites(qaApi, { n: 2 }); });
test.afterAll(async () => { await favs?.remove(); });

test("Home's late library load does not move focus the viewer already moved", async ({ page, seedSession, remote, shot }) => {
  // Was a bug (screens/home.rs load(): `untouched` = "focus is on 搜尋", not "the
  // viewer has not pressed anything"): the viewer goes Right off 搜尋 and back
  // Left to 搜尋 while the rails load; when they land, focus jumps to the
  // first card and the next OK opens a title instead of Search.
  // Fixed: home.rs moves focus on load only if no key was pressed since the
  // screen mounted (focus::key_count), or if the focused card disappeared.
  await installProbes(page);
  await page.route(/\/api\/v1\/me\/(continue-watching|favorites|history)(\?|$)/, async (route) => {
    await new Promise((r) => setTimeout(r, 2500));
    return route.fallback();
  });
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  // While the first load is pending nothing is focused (no default landing on
  // 搜尋 for the rails to take away); the first key wakes focus on 搜尋 and is
  // then applied, so Right lands on the next top-bar button (音效, then 登出).
  await page.waitForTimeout(500);
  await remote.right();
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "sfx");
  await remote.left();
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "search");
  await expect(page.locator('[data-screen="home"] .tv-rows [data-focusable]').first()).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(600);
  await shot("after-load");
  await expect(page.locator(".is-focused"), "focus stays where the viewer put it").toHaveAttribute("data-action", "search");
});
