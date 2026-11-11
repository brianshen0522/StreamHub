// C. Empty states. Home for an account with an empty library (tizen-qa-empty
// — read-only here: every library write from the page is aborted and must
// not even be attempted), search with no results, and search with one
// provider failing (500) or hanging past the app's 20 s deadline.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { emptyCredentials } from "../lib/env.js";
import { QaApi } from "../lib/api.js";
import { forbidLibraryWrites, fulfillJson, installProbes, seedAs, settledAudit, searchFromHome } from "../lib/qa2.js";

test.describe("empty library (tizen-qa-empty)", () => {
  let api;
  test.beforeAll(async () => {
    const creds = emptyCredentials();
    test.skip(!creds, "STREAMHUB_QA_EMPTY_USER / _PASSWORD not set");
    api = await QaApi.login({ creds });
    const [p, f, h] = await Promise.all([api.json("GET", "/me/progress"), api.json("GET", "/me/favorites"), api.json("GET", "/me/history")]);
    expect(p.progress.length + f.favorites.length + h.history.length, "the empty account really is empty").toBe(0);
  });
  test.afterAll(async () => { await api?.logout(); });

  test("Home with no library: focus lands on 搜尋, helpful copy, search reachable, no writes", async ({ page, remote, shot }) => {
    const writes = await forbidLibraryWrites(page);
    await installProbes(page);
    // A session of its own (so this page's refresh rotation is its own).
    const mine = await QaApi.login({ creds: emptyCredentials() });
    try {
      await seedAs(page, mine);
      await page.goto("/");
      await expect(screen(page, "home")).toBeVisible();
      const empty = page.locator('[data-screen="home"] .tv-empty');
      await expect(empty).toBeVisible({ timeout: 20_000 });
      await expect(empty).toContainText("搜尋");
      // No skeletons left, no caption errors.
      await expect(page.locator('[data-screen="home"] .tv-skeleton')).toHaveCount(0);
      await expect(page.locator('[data-screen="home"] .tv-row-caption.is-error')).toHaveCount(0);
      await page.waitForTimeout(800);
      const a = await settledAudit(page);
      await shot("home-empty");
      expect(a.problems, JSON.stringify(a)).toEqual([]);
      expect(a.key, "focus lands on 搜尋 when there is nothing else").toBe("id:home-search");
      // The D-pad cannot lose focus on an almost empty screen.
      for (const d of ["Down", "Down", "Left", "Right", "Right", "Up", "Down"]) {
        await remote.press(d);
        const x = await settledAudit(page);
        expect(x.problems, `after ${d}: ${JSON.stringify(x)}`).toEqual([]);
      }
      await focusTo(page, remote, '[data-action="search"]', { dirs: ["Left", "Up"] });
      await remote.enter();
      await expect(screen(page, "search")).toBeVisible();
      await expect(page.locator(".is-focused")).toHaveAttribute("name", "q");
      await shot("search-from-empty-home");
      expect(writes, "no library writes from the empty account").toEqual([]);
    } finally {
      await mine.logout();
    }
  });
});

test("search with no results: every provider collapses to 沒有結果, focus stays on the field", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(90_000);
  await installProbes(page);
  await seedSession();
  await page.goto("/");
  const q = "zqxjvkwpq9137";
  await searchFromHome(page, remote, focusTo, q);
  const rails = page.locator('[data-screen="search"] [data-provider]');
  // Wait until no rail is still loading.
  await expect.poll(() => page.locator('[data-screen="search"] .tv-skeleton').count(), { timeout: 45_000 }).toBe(0);
  const states = await rails.evaluateAll((els) => els.map((e) => ({
    provider: e.dataset.provider, caption: e.querySelector(".tv-row-caption")?.innerText || null,
    cards: e.querySelectorAll("[data-focusable]").length,
  })));
  await shot("no-results");
  console.log(`[no-results] ${JSON.stringify(states)}`);
  expect(states.length).toBeGreaterThanOrEqual(2);
  for (const s of states) {
    expect(s.cards, `${s.provider} shows no cards`).toBe(0);
    expect(s.caption, `${s.provider} says why`).toBeTruthy();
  }
  const a = await settledAudit(page);
  expect(a.problems).toEqual([]);
  expect(a.screen).toBe("search");
  // Down has nowhere to go: focus must not vanish.
  await remote.down();
  await remote.down();
  expect((await settledAudit(page)).problems).toEqual([]);
});

for (const mode of ["500", "hang"]) {
  test(`one provider ${mode === "500" ? "answering 500" : "hanging past the 20 s deadline"}: its own caption, other rails fine`, async ({ page, seedSession, remote, shot }) => {
    test.setTimeout(120_000);
    const FAIL = "777tv";
    await page.route(/\/api\/v1\/search\?/, async (route) => {
      const u = new URL(route.request().url());
      if (u.searchParams.get("provider") !== FAIL) return route.fallback();
      if (mode === "500") return fulfillJson(route, 500, { error: "QA: provider exploded" });
      // Never answer within the app's deadline.
      await new Promise((r) => setTimeout(r, 26_000));
      return route.abort().catch(() => {});
    });
    await installProbes(page);
    await seedSession();
    await page.goto("/");
    const t0 = Date.now();
    await searchFromHome(page, remote, focusTo, "黑暗榮耀");
    const failed = page.locator(`[data-screen="search"] [data-provider="${FAIL}"]`);
    const caption = failed.locator(".tv-row-caption.is-error");
    await expect(caption).toBeVisible({ timeout: 35_000 });
    const elapsed = Date.now() - t0;
    const text = await caption.innerText();
    console.log(`[provider ${mode}] caption "${text}" after ${elapsed} ms`);
    if (mode === "500") expect(text).toContain("QA: provider exploded");
    else {
      expect(text).toContain("逾時");
      expect(elapsed, "the deadline is ~20 s").toBeGreaterThan(18_000);
    }
    // The other providers still render cards.
    await expect.poll(() => page.locator(`[data-screen="search"] [data-provider]:not([data-provider="${FAIL}"]) [data-focusable]`).count(), { timeout: 30_000 })
      .toBeGreaterThan(0);
    await expect(failed.locator("[data-focusable]")).toHaveCount(0);
    await shot(`provider-${mode}`);
    // Focus can walk the good rails without dropping into the failed one.
    await focusTo(page, remote, "[data-provider] [data-focusable]", { dirs: ["Down"] });
    for (const d of ["Down", "Down", "Down", "Up", "Up", "Down"]) {
      await remote.press(d);
      const a = await settledAudit(page);
      expect(a.problems, `after ${d}: ${JSON.stringify(a)}`).toEqual([]);
    }
  });
}
