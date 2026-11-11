// A. Focus integrity. A long seeded D-pad walk across Home, Search and
// Detail: after every press exactly one .is-focused element, visible inside
// the 1920×1080 viewport (not off-screen, not faded under the hidden top
// bar, not painted over), and document.activeElement agrees. Then every
// [data-focusable] each screen renders must be reachable with the D-pad.
//
// QA_SEED picks the walk (default 1234); a failure names the seed and step.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { installProbes, settledAudit, prng, reachAll, seedFavorites, searchFromHome, focusables } from "../lib/qa2.js";

const SEED = Number(process.env.QA_SEED || 1234);
const QUERY = "黑暗榮耀";

test.describe.configure({ mode: "serial" });

let favs;
test.beforeAll(async ({ qaApi }) => { favs = await seedFavorites(qaApi, { n: 8 }); });
test.afterAll(async () => { await favs?.remove(); });

async function walk(page, remote, name, presses, rand, { expectScreen }) {
  const failures = [];
  const keys = new Set();
  // Biased toward Left/Right (rails are long), in runs of 1–4 presses.
  const plan = [];
  while (plan.length < presses) {
    const dir = rand() < 0.6 ? (rand() < 0.5 ? "Left" : "Right") : (rand() < 0.5 ? "Up" : "Down");
    const run = 1 + Math.floor(rand() * 4);
    for (let r = 0; r < run; r += 1) plan.push(dir);
  }
  plan.length = presses;
  for (const [i, dir] of plan.entries()) {
    await remote.press(dir, { gap: 60 });
    const a = await settledAudit(page);
    keys.add(a.key);
    if (a.screen !== expectScreen) a.problems.push(`focus on screen ${a.screen}, expected ${expectScreen}`);
    if (a.problems.length) failures.push({ step: `${name}#${i}`, dir, ...a });
  }
  return { failures, distinct: keys.size };
}

test("seeded ~300-press D-pad walk keeps exactly one visible, DOM-focused element", async ({ page, seedSession, remote, shot }, testInfo) => {
  test.setTimeout(420_000);
  expect(favs.count, "seeded favourites so Home has rails").toBeGreaterThan(2);
  await installProbes(page);
  await seedSession();
  await page.goto("/");
  await expect(screen(page, "home")).toBeVisible();
  await expect(page.locator('[data-screen="home"] .tv-rows [data-focusable]').first()).toBeVisible({ timeout: 30_000 });
  const rand = prng(SEED);
  const report = {};

  report.home = await walk(page, remote, "home", 110, rand, { expectScreen: "home" });
  await shot("home-after-walk");

  await searchFromHome(page, remote, focusTo, QUERY);
  await expect.poll(() => page.locator('[data-screen="search"] [data-provider] [data-focusable]').count(), { timeout: 45_000 })
    .toBeGreaterThan(3);
  // Let the slow providers land so the walk sees the final layout.
  await page.waitForTimeout(4000);
  await focusTo(page, remote, '[data-provider] [data-focusable]', { dirs: ["Down"] });
  report.search = await walk(page, remote, "search", 100, rand, { expectScreen: "search" });
  await shot("search-after-walk");

  await focusTo(page, remote, '[data-provider] [data-focusable]', { dirs: ["Down", "Up"] });
  await remote.enter();
  await expect(screen(page, "detail")).toBeVisible();
  await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  report.detail = await walk(page, remote, "detail", 100, rand, { expectScreen: "detail" });
  await shot("detail-after-walk");

  await testInfo.attach("walk-report.json", { body: JSON.stringify({ seed: SEED, report }, null, 2), contentType: "application/json" });
  const all = [...report.home.failures, ...report.search.failures, ...report.detail.failures];
  console.log(`[walk seed=${SEED}] distinct focus targets home=${report.home.distinct} search=${report.search.distinct} detail=${report.detail.distinct}; failures=${all.length}`);
  for (const f of all.slice(0, 15)) console.log(`  ${f.step} ${f.dir} → ${f.key} ${JSON.stringify(f.rect)} ${f.problems.join("; ")}`);
  expect(all, `focus invariant failures (seed ${SEED})`).toEqual([]);
  expect(report.home.distinct).toBeGreaterThan(3);
  expect(report.search.distinct).toBeGreaterThan(3);
});

for (const where of ["home", "search", "detail"]) {
  test(`no focus traps: every focusable on ${where} is reachable by D-pad`, async ({ page, seedSession, remote, shot }, testInfo) => {
    test.setTimeout(420_000);
    await installProbes(page);
    await seedSession();
    await page.goto("/");
    await expect(page.locator('[data-screen="home"] .tv-rows [data-focusable]').first()).toBeVisible({ timeout: 30_000 });
    if (where !== "home") {
      await searchFromHome(page, remote, focusTo, QUERY);
      await expect.poll(() => page.locator('[data-screen="search"] [data-provider] [data-focusable]').count(), { timeout: 45_000 })
        .toBeGreaterThan(3);
      await page.waitForTimeout(5000);
    }
    if (where === "detail") {
      await focusTo(page, remote, '[data-provider] [data-focusable]', { dirs: ["Down"] });
      await remote.enter();
      await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 30_000 });
      await page.waitForTimeout(2000);
    }
    const before = await focusables(page);
    const result = await reachAll(page, remote);
    await shot(`${where}-reach-end`);
    await testInfo.attach(`${where}-reach.json`, { body: JSON.stringify({ result, before }, null, 2), contentType: "application/json" });
    console.log(`[reach ${where}] ${result.reached}/${result.total} reachable` +
      (result.unreachable.length ? `; unreachable: ${result.unreachable.map((u) => `${u.key} "${u.text}"`).join(", ")}` : ""));
    expect(result.unreachable, `unreachable focusables on ${where}`).toEqual([]);
  });
}

test("Down from the search field enters every provider rail that has cards, from the field and from deep in the first rail", async ({ page, seedSession, remote, shot }, testInfo) => {
  test.setTimeout(150_000);
  await installProbes(page);
  await seedSession();
  await page.goto("/");
  await searchFromHome(page, remote, focusTo, QUERY);
  await expect.poll(() => page.locator('[data-screen="search"] .tv-skeleton').count(), { timeout: 45_000 }).toBe(0);
  const withCards = await page.locator("[data-provider]").evaluateAll((els) =>
    els.filter((e) => e.querySelector("[data-focusable]")).map((e) => e.dataset.provider));
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up"] });
  const down = [];
  for (let i = 0; i < withCards.length + 2; i += 1) {
    await remote.down({ gap: 350 });
    down.push(await page.evaluate(() => document.querySelector(".is-focused")?.closest("[data-provider]")?.dataset.provider ?? null));
  }
  // Also from deep in the first rail: move right 12, then Down through the rest.
  await focusTo(page, remote, 'input[name="q"]', { dirs: ["Up"], steps: 12 });
  await remote.down({ gap: 350 });
  for (let i = 0; i < 12; i += 1) await remote.right({ gap: 120 });
  const deep = [];
  for (let i = 0; i < withCards.length + 1; i += 1) {
    await remote.down({ gap: 350 });
    deep.push(await page.evaluate(() => document.querySelector(".is-focused")?.closest("[data-provider]")?.dataset.provider ?? null));
  }
  await shot("rails-down");
  const out = { withCards, downFromField: down, downFromDeep: deep };
  console.log(`[rails down] ${JSON.stringify(out)}`);
  await testInfo.attach("rails-down.json", { body: JSON.stringify(out, null, 2), contentType: "application/json" });
  for (const p of withCards) {
    expect(down, `Down from the field reaches ${p}`).toContain(p);
    if (p !== withCards[0]) expect(deep, `Down from deep in the first rail reaches ${p}`).toContain(p);
  }
});
