// F. Content edge cases: a title the provider dropped (404 → 已下架 → search
// the other providers), a 120-character title, items with no poster or a
// broken one, and a real movie end to end into the player.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";
import { fulfillJson, hermeticTitle, installProbes, mockPlayer, requestLog, searchFromHome } from "../lib/qa2.js";

test("unavailable title: /item 404 → 已下架 → 搜尋「…」 searches every provider for it", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(120_000);
  let gone = true;
  await page.route(/\/api\/v1\/item\?/, (route) => gone ? fulfillJson(route, 404, { error: "Not found." }) : route.fallback());
  await seedSession();
  await page.goto("/");
  await searchFromHome(page, remote, focusTo, "黑暗榮耀");
  await expect(page.locator("[data-provider] [data-focusable]").first()).toBeVisible({ timeout: 45_000 });
  await focusTo(page, remote, "[data-provider] [data-focusable]", { dirs: ["Down"] });
  const title = (await page.locator(".is-focused .tv-card-title").innerText()).trim();
  await remote.enter();
  await expect(page.locator('[data-screen="detail"] .tv-gone-title')).toHaveText("已下架");
  const btn = page.locator('[data-action="search-elsewhere"]');
  await expect(btn).toContainText(title);
  await expect(page.locator(".is-focused")).toHaveAttribute("data-action", "search-elsewhere");
  await shot("gone");
  gone = false;
  await remote.enter();
  await expect(screen(page, "search")).toBeVisible();
  await expect(page.locator('input[name="q"]')).toHaveValue(title);
  await expect(page.locator("[data-provider] [data-focusable]").first()).toBeVisible({ timeout: 45_000 });
  await shot("searched-elsewhere");
  // Back from that search returns to the 已下架 page, then on.
  await remote.back({ gap: 400 });
  if (await screen(page, "search").isVisible()) await remote.back({ gap: 400 });
  await expect(screen(page, "detail")).toBeVisible();
});

test("a 120-character title stays inside its card (ellipsis) and inside the Detail page", async ({ page, seedSession, remote, shot }) => {
  const LONG = "超長片名測試".repeat(10) + "ABCDEFGHIJ".repeat(6); // 60 CJK + 60 Latin = 120
  expect([...LONG].length).toBe(120);
  await mockPlayer(page, {});
  const h = await hermeticTitle(page, { title: LONG });
  await installProbes(page);
  await seedSession();
  await page.goto("/");
  await searchFromHome(page, remote, focusTo, h.opts.query);
  const card = page.locator(`[data-provider="${h.opts.provider}"] .tv-card`).first();
  await expect(card).toBeVisible();
  await focusTo(page, remote, `[data-provider="${h.opts.provider}"] [data-focusable]`, { dirs: ["Down", "Right"], steps: 10 });
  await page.waitForTimeout(500);
  const m = await card.evaluate((c) => {
    const t = c.querySelector(".tv-card-title");
    const cr = c.getBoundingClientRect(); const tr = t.getBoundingClientRect(); const cs = getComputedStyle(t);
    return { card: [cr.left, cr.right, cr.bottom], title: [tr.left, tr.right, tr.bottom], overflow: cs.overflow, textOverflow: cs.textOverflow,
      whiteSpace: cs.whiteSpace, clamp: cs.webkitLineClamp, scrollW: t.scrollWidth, clientW: t.clientWidth, scrollH: t.scrollHeight, clientH: t.clientHeight };
  });
  console.log(`[long title card] ${JSON.stringify(m)}`);
  await shot("long-title-card");
  expect(m.title[0]).toBeGreaterThanOrEqual(m.card[0] - 1);
  expect(m.title[1]).toBeLessThanOrEqual(m.card[1] + 1);
  expect(m.textOverflow, "truncated with an ellipsis").toBe("ellipsis");
  expect(m.scrollW > m.clientW || m.scrollH > m.clientH, "the text really is cut").toBeTruthy();

  await remote.enter();
  await expect(page.locator('[data-action="play"]')).toBeVisible();
  await page.waitForTimeout(800);
  const d = await page.locator(".tv-detail-title").evaluate((t) => {
    const r = t.getBoundingClientRect();
    const play = document.querySelector('[data-action="play"]').getBoundingClientRect();
    return { right: r.right, bottom: r.bottom, height: r.height, playTop: play.top, playBottom: play.bottom };
  });
  console.log(`[long title detail] ${JSON.stringify(d)}`);
  await shot("long-title-detail");
  expect(d.right, "title inside the viewport").toBeLessThanOrEqual(1920);
  expect(d.bottom, "title does not run into 播放").toBeLessThanOrEqual(d.playTop);
  expect(d.playBottom, "播放 still on screen").toBeLessThanOrEqual(1080);
});

test("no posterUrl → letter placeholder, no <img>; a broken poster shows no broken-image icon", async ({ page, seedSession, remote, shot }) => {
  await page.route(/\/api\/v1\/search\?/, (route) => {
    const prov = new URL(route.request().url()).searchParams.get("provider");
    const items = prov === "dramasq" ? [
      { provider: prov, title: "無海報片", url: "https://dramasq.qa.invalid/a.html", mediaType: "tv" },
      { provider: prov, title: "壞海報片", url: "https://dramasq.qa.invalid/b.html", posterUrl: "https://img.qa.invalid/broken.jpg", mediaType: "tv" },
    ] : [];
    return fulfillJson(route, 200, { results: [{ provider: prov, items }] });
  });
  await page.route(/\/api\/v1\/poster\?/, (route) => route.fulfill({ status: 404, body: "nope", headers: { "access-control-allow-origin": "*" } }));
  await seedSession();
  await page.goto("/");
  await searchFromHome(page, remote, focusTo, "海報測試");
  const cards = page.locator('[data-provider="dramasq"] .tv-card');
  await expect(cards).toHaveCount(2);
  await page.waitForTimeout(1500);
  const info = await cards.evaluateAll((els) => els.map((c) => {
    const img = c.querySelector("img");
    const fb = c.querySelector(".tv-card-fallback");
    return { title: c.querySelector(".tv-card-title").innerText, hasImg: !!img, alt: img?.getAttribute("alt") ?? null,
      natural: img ? img.naturalWidth : null, imgVisible: img ? getComputedStyle(img).visibility !== "hidden" && getComputedStyle(img).opacity !== "0" : null,
      fallback: fb ? fb.innerText : null, fallbackVisible: fb ? fb.getBoundingClientRect().width > 0 : false };
  }));
  console.log(`[posters] ${JSON.stringify(info)}`);
  await shot("posters");
  expect(info[0].hasImg).toBe(false);
  expect(info[0].fallback).toBe("無");
  expect(info[0].fallbackVisible).toBe(true);
  expect(info[1].hasImg).toBe(true);
  expect(info[1].natural).toBe(0);
  // With alt="" Chromium paints nothing for a failed image, so the letter shows through.
  expect(info[1].alt).toBe("");
  expect(info[1].fallbackVisible).toBe(true);
});

test("a movie end to end: movieffm 奧本海默 → Detail (電影) → player plays", async ({ page, seedSession, remote, shot }) => {
  test.setTimeout(180_000);
  await mockPlayer(page, { mockDuration: 10800 });
  // Progress and source-preference writes are answered locally: history and
  // preferences have no delete route, and 30-browse already proves the PUT lands.
  const puts = [];
  await page.route(/\/api\/v1\/me\/(progress|source-preference)(\?|$)/, (route) => {
    const r = route.request();
    if (r.method() === "GET") return route.fallback();
    if (r.method() === "PUT") puts.push(r.postDataJSON());
    return fulfillJson(route, 200, { ok: true });
  });
  const log = requestLog(page);
  await seedSession();
  await page.goto("/");
  await searchFromHome(page, remote, focusTo, "奧本海默");
  const card = page.locator('[data-provider="movieffm"] .tv-card')
    .filter({ has: page.locator(".tv-card-title", { hasText: /^奧本海默$/ }) }).first();
  await expect(card).toBeVisible({ timeout: 45_000 });
  const id = await card.getAttribute("data-focus-id");
  await focusTo(page, remote, '[data-provider="movieffm"] [data-focusable]', { dirs: ["Down"], steps: 10 });
  await focusTo(page, remote, `[data-focus-id="${id}"]`, { dirs: ["Left", "Right"], steps: 15 });
  await remote.enter();
  await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".tv-detail-chips")).toContainText("電影", { timeout: 30_000 });
  await shot("movie-detail");
  await focusTo(page, remote, '[data-action="play"]', { dirs: ["Up", "Left"] });
  await remote.enter();
  const player = screen(page, "player");
  await expect(player).toBeVisible();
  await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 90_000 }).toBeGreaterThan(0);
  await shot("movie-playing");
  expect(log.some((r) => r.method === "POST" && r.url.includes("/api/v1/check-sources")), "movie sources via POST /check-sources").toBeTruthy();
  const opened = await page.evaluate(() => window.StreamHubPlayer.opened);
  console.log(`[movie] opened ${opened.length}: ${opened.map((o) => o.url.replace(/accessToken=[^&]+/, "accessToken=…").slice(0, 140)).join(" | ")}`);
  expect(opened[0].url).toContain("/api/v1/manifest?target=");
  await expect.poll(() => puts.length, { timeout: 25_000 }).toBeGreaterThan(0);
  expect(puts[0].mediaType).toBe("movie");
  expect(puts[0].episodeLabel ?? "").toBe("");
});
