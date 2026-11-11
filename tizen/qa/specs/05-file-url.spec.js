// The packaged widget loads from file://…/res/wgt/index.html. Load the staged
// package directory (package.sh STAGE_ONLY=1 → tizen/target/wgt-stage) the
// same way, and check that the app mounts. Chromium is given
// --allow-file-access-from-files because Tizen's web runtime lets a widget
// read its own files; without the flag desktop Chromium refuses both fetch and
// XHR of file:, which the device does not.
import fs from "node:fs";
import path from "node:path";
import { test, expect } from "../lib/fixtures.js";
import { QA_DIR } from "../lib/env.js";

const STAGE = path.resolve(QA_DIR, "..", "target", "wgt-stage");
test.use({ launchOptions: { args: ["--allow-file-access-from-files"] } });

test("the staged widget mounts when loaded over file://", async ({ page, shot }) => {
  test.skip(!fs.existsSync(path.join(STAGE, "index.html")), "no staged package — run ../package.sh with STAGE_ONLY=1");
  expect(fs.existsSync(path.join(STAGE, "config.xml"))).toBeTruthy();
  const index = fs.readFileSync(path.join(STAGE, "index.html"), "utf8");
  expect(index, "no root-absolute URLs").not.toMatch(/(href|src)="\/[^/]/);
  expect(index, "no module scripts (Chromium refuses them from file://)").not.toMatch(/type="module"/);

  await page.goto(`file://${STAGE}/index.html`);
  await expect(page.locator("[data-screen]").first()).toBeAttached({ timeout: 30_000 });
  await shot("file-url-mounted");
});
