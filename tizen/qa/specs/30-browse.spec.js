// Search → detail → player, one continuous walk with the remote, as a person
// would. Serial: each step needs the previous one.
import { test, expect, focusTo, screen } from "../lib/fixtures.js";

const QUERY = "黑暗榮耀";
test.describe.configure({ mode: "serial" });


test("search 黑暗榮耀 shows per-provider rails, opens detail, plays, reports progress, seeks and backs out",
  async ({ page, seedSession, remote, focused, shot, qaApi }) => {
    test.setTimeout(240_000);
    await seedSession();
    await page.goto("/");
    await expect(screen(page, "home")).toBeVisible();

    await test.step("search", async () => {
      await focusTo(page, remote, '[data-action="search"]', { dirs: ["Up", "Left", "Down", "Right"] });
      await remote.enter();
      await expect(screen(page, "search")).toBeVisible();
      const input = page.locator('input[name="q"]');
      await focusTo(page, remote, 'input[name="q"]');
      await page.keyboard.type(QUERY);
      await remote.enter();
      const rails = page.locator('[data-screen="search"] [data-provider]');
      await expect(rails.first()).toBeVisible({ timeout: 45_000 });
      // Every provider that answered gets its own rail.
      await expect.poll(async () => rails.count(), { timeout: 45_000 }).toBeGreaterThanOrEqual(2);
      const providers = await rails.evaluateAll((els) => els.map((e) => e.dataset.provider));
      expect(new Set(providers).size, "one rail per provider").toBe(providers.length);
      await shot("search-results");
      await expect(input).toHaveValue(QUERY);
    });

    await test.step("open detail", async () => {
      await focusTo(page, remote, '[data-provider] [data-focusable]', { dirs: ["Down"] });
      await remote.enter();
      await expect(screen(page, "detail")).toBeVisible({ timeout: 30_000 });
      await expect(page.locator('[data-action="play"]')).toBeVisible({ timeout: 30_000 });
      await shot("detail");
    });

    const puts = [];
    page.on("request", (r) => {
      if (r.method() === "PUT" && /\/api\/v1\/me\/progress$/.test(r.url())) puts.push(r.postDataJSON());
    });

    await test.step("play pushes the player", async () => {
      await focusTo(page, remote, '[data-action="play"]');
      await remote.enter();
      await expect(screen(page, "player")).toBeVisible({ timeout: 45_000 });
      const player = screen(page, "player");
      await expect.poll(async () => Number(await player.getAttribute("data-position")), { timeout: 45_000 })
        .toBeGreaterThan(0);
      await shot("player");
    });

    await test.step("progress PUT reaches the server", async () => {
      await expect.poll(() => puts.length, { timeout: 60_000 }).toBeGreaterThan(0);
      const sent = puts[puts.length - 1];
      expect(Number.isInteger(sent.positionSeconds), "positions are integer seconds").toBeTruthy();
      expect(sent).not.toHaveProperty("progressPercent");
      const query = `/me/progress?providerKey=${encodeURIComponent(sent.providerKey)}&itemUrl=${encodeURIComponent(sent.itemUrl)}`;
      // The request is seen before the server has written it, so poll.
      await expect.poll(async () => {
        const { progress } = await qaApi.json("GET", query);
        return progress.map((r) => r.episodeLabel || "");
      }, { timeout: 20_000, message: `progress row for ${JSON.stringify({ title: sent.title, episodeLabel: sent.episodeLabel, event: sent.event })}` })
        .toContain(sent.episodeLabel || "");
    });

    const player = screen(page, "player");
    const pos = async () => Number(await player.getAttribute("data-position"));

    await test.step("Left/Right seek (controls hidden)", async () => {
      // With the control bar up the arrows move between its buttons, so
      // seeking is tested with it down; Back is how a person puts it away.
      if ((await player.getAttribute("data-controls")) === "visible") await remote.back();
      await expect(player).toHaveAttribute("data-controls", "hidden");
      const p0 = await pos();
      await remote.right();
      await expect.poll(pos).toBeGreaterThanOrEqual(p0 + 5);
      await shot("seek-forward");
      const p1 = await pos();
      await remote.left();
      await expect.poll(pos).toBeLessThan(p1);
      await shot("seek-back");
      await expect(screen(page, "player")).toBeVisible();
    });

    await test.step("Back hides controls, then leaves the player", async () => {
      await remote.up();
      await expect(player).toHaveAttribute("data-controls", "visible");
      await shot("controls");
      await remote.back();
      await expect(player).toHaveAttribute("data-controls", "hidden");
      await expect(player).toBeVisible();
      await remote.back();
      await expect(screen(page, "detail")).toBeVisible();
      await shot("back-to-detail");
    });
  });
