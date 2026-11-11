// Renders package/icon.png (512×512) from the SVG below with Playwright's
// Chromium — the build host has no ImageMagick or PIL. Run from tizen/qa:
//   node tools/make-icon.mjs   (needs a Chromium that launches; on this host run it in
//   mcr.microsoft.com/playwright:v1.65.0-noble, as qa/run.sh does for the specs)
import { chromium } from "@playwright/test";
import { fileURLToPath } from "node:url";
import path from "node:path";

const out = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "package", "icon.png");
const svg = `
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#1b2030"/><stop offset="1" stop-color="#0b0d14"/>
    </linearGradient>
    <linearGradient id="fg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffb347"/><stop offset="1" stop-color="#ff6a3d"/>
    </linearGradient>
  </defs>
  <rect width="512" height="512" rx="96" fill="url(#bg)"/>
  <path d="M352 150 C330 118 292 104 252 104 C192 104 150 138 150 188 C150 300 362 236 362 336
           C362 384 316 410 260 410 C212 410 172 390 152 352"
        fill="none" stroke="url(#fg)" stroke-width="64" stroke-linecap="round"/>
</svg>`;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 512, height: 512 } });
await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
await page.locator("svg").screenshot({ path: out, omitBackground: true });
await browser.close();
console.log("wrote", out);
