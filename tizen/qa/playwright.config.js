import { defineConfig } from "@playwright/test";
import { APP_URL } from "./lib/env.js";

export default defineConfig({
  testDir: "./specs",
  outputDir: "./test-results",
  globalSetup: "./global-setup.js",
  // One account, one server: specs share library state, so never in parallel.
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["json", { outputFile: "test-results/results.json" }]],
  use: {
    baseURL: APP_URL,
    viewport: { width: 1920, height: 1080 },
    screenshot: "on",
    trace: "retain-on-failure",
    // M120 is what the monitor runs; the bundled Chromium is newer, so
    // anything that only works here is still a risk on the device.
    browserName: "chromium",
  },
});
