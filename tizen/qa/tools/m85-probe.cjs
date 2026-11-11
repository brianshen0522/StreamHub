// Drives a real Chromium 85 (the 2022 Smart Monitor's engine) over CDP: loads
// the staged widget from file:// with no server override, so the compiled-in
// base is what gets used, and reports console errors plus a screenshot.
// Optional argv[2]: a JS expression to run after load (e.g. key presses).
const { chromium } = require(require("path").join(__dirname, "..", "node_modules", "playwright"));
(async () => {
  const browser = await chromium.connectOverCDP("http://127.0.0.1:9333");
  const ctx = browser.contexts()[0];
  const page = ctx.pages()[0] || (await ctx.newPage());
  const logs = [];
  page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") logs.push(`${m.type()}: ${m.text()}`.slice(0, 300)); });
  page.on("pageerror", (e) => logs.push(`pageerror: ${e.message}`.slice(0, 300)));
  await page.goto("file:///w/index.html");
  await page.waitForTimeout(5000);
  if (process.argv[2]) { await page.evaluate(process.argv[2]); await page.waitForTimeout(3000); }
  const ua = await page.evaluate(() => navigator.userAgent);
  const screens = await page.evaluate(() => [...document.querySelectorAll("[data-screen]")].map((e) => e.dataset.screen).join(","));
  console.log(`UA: ${ua}\nscreens: ${screens || "(none mounted)"}`);
  // Expected on a desktop: no $WEBAPIS/webapis.js, and file: fetch refused (boot.js falls back to XHR).
  const expected = /webapis\.js|ERR_FILE_NOT_FOUND|Fetch API cannot load file:\/\/\/w\/streamhub-tizen_bg\.wasm/;
  const unexpected = logs.filter((l) => !expected.test(l));
  console.log(unexpected.length ? unexpected.join("\n") : "no unexpected console errors");
  await page.screenshot({ path: "/out/m85.png" });
  await browser.close();
  process.exit(unexpected.length ? 1 : 0);
})().catch((e) => { console.error("probe failed:", e.message); process.exit(2); });
