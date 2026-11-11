// I. Packaging sanity on a signed .wgt (QA_WGT, default tizen/StreamHub.wgt):
// signatures present, config.xml privileges / ids / CSP, no module scripts,
// every file index.html references is in the package, wasm size, and the
// package itself — extracted — mounts over file://.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect, screen } from "../lib/fixtures.js";
import { QA_DIR } from "../lib/env.js";
import { readZip } from "../lib/zip.js";
import { fakePairing } from "../lib/qa2.js";

const WGT = process.env.QA_WGT && fs.existsSync(process.env.QA_WGT)
  ? process.env.QA_WGT : path.resolve(QA_DIR, "..", "StreamHub.wgt");
// Tizen lets a widget read its own files; desktop Chromium needs the flag (as 05-file-url).
test.use({ launchOptions: { args: ["--allow-file-access-from-files"] } });

const WASM_BUDGET = 2 * 1024 * 1024; // release wasm; the dev build is ~29 MB and must never ship

test.describe("signed package", () => {
  test.skip(!fs.existsSync(WGT), `no package at ${WGT} — run ../package.sh`);

  test("signatures, config.xml, CSP, scripts, referenced files, sizes", async ({}, testInfo) => {
    const zip = readZip(WGT);
    const names = [...zip.entries.keys()];
    const facts = { wgt: WGT, bytes: zip.size, files: names.length };
    expect(names).toEqual(expect.arrayContaining(["author-signature.xml", "signature1.xml", "config.xml", "index.html", "icon.png"]));
    const sig = zip.read("signature1.xml").toString("utf8");
    expect(sig, "distributor signature covers index.html").toContain('URI="index.html"');
    expect(sig).toContain('URI="streamhub-tizen_bg.wasm"');
    expect(names.filter((n) => /\.(project|tproject|wgt)$|^\.sign|\.p12$|\.password$|\.pwd$/.test(n)), "no secrets / IDE junk").toEqual([]);

    const config = zip.read("config.xml").toString("utf8");
    expect(config).toMatch(/<tizen:application id="StrmHubTV1\.StreamHub" package="StrmHubTV1"/);
    expect(config).toContain('<tizen:profile name="tv-samsung"/>');
    const privileges = [...config.matchAll(/<tizen:privilege name="([^"]+)"/g)].map((m) => m[1]);
    facts.privileges = privileges;
    expect(privileges).toEqual(expect.arrayContaining([
      "http://tizen.org/privilege/internet", "http://tizen.org/privilege/tv.inputdevice"]));
    // Nothing partner/platform-level that a public certificate cannot sign.
    expect(privileges.filter((p) => /partner|platform|drmplay|filesystem/.test(p))).toEqual([]);
    const csp = config.match(/<tizen:content-security-policy>([^<]+)</)?.[1] || "";
    facts.csp = csp;
    const scriptSrc = csp.match(/script-src([^;]+)/)?.[1] || "";
    expect(scriptSrc, "wasm compiles under the CSP").toContain("'wasm-unsafe-eval'");
    expect(csp).toMatch(/connect-src[^;]*\*/);
    expect(csp).toMatch(/media-src[^;]*\*/);
    expect(csp).toMatch(/object-src 'none'/);
    const content = config.match(/<content src="([^"]+)"/)?.[1];
    expect(content).toBe("index.html");
    const icon = config.match(/<icon src="([^"]+)"/)?.[1];
    expect(names).toContain(icon);

    const index = zip.read("index.html").toString("utf8");
    // An inline script needs 'unsafe-inline'; without one (loader in js/start.js) the CSP should not grant it.
    const inline = [...index.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].filter((m) => m[1].trim());
    facts.inlineScripts = inline.length;
    if (inline.length) expect(scriptSrc, "inline loader needs 'unsafe-inline'").toContain("'unsafe-inline'");
    else expect(scriptSrc, "no inline scripts, so no 'unsafe-inline'").not.toContain("'unsafe-inline'");
    expect(names, "the wasm the loader fetches").toContain("streamhub-tizen_bg.wasm");
    expect(index, "no module scripts").not.toMatch(/type="module"/);
    expect(index, "no modulepreload").not.toMatch(/modulepreload/);
    expect(index, "no root-absolute URLs").not.toMatch(/(href|src)="\/[^/]/);
    const refs = [...index.matchAll(/(?:src|href)="([^"]+)"/g), ...index.matchAll(/module_or_path:\s*"([^"]+)"/g)]
      .map((m) => m[1]).filter((u) => !/^(https?:|data:|\$WEBAPIS)/.test(u)).map((u) => u.replace(/^\.\//, ""));
    facts.references = refs;
    for (const r of refs) expect(names, `index.html references ${r}`).toContain(r);
    // Scripts the classic loaders pull in at run time.
    for (const js of ["js/boot.js", "js/tizen-keys.js", "js/player-shim.js", "js/vendor/hls.min.js"]) expect(names).toContain(js);

    const wasm = zip.entries.get("streamhub-tizen_bg.wasm");
    facts.wasmBytes = wasm.size;
    facts.wasmCompressed = wasm.csize;
    const head = zip.read("streamhub-tizen_bg.wasm").subarray(0, 4);
    expect([...head]).toEqual([0x00, 0x61, 0x73, 0x6d]);
    expect(wasm.size, "release wasm, not a debug build").toBeLessThan(WASM_BUDGET);
    await testInfo.attach("package-facts.json", { body: JSON.stringify(facts, null, 2), contentType: "application/json" });
    console.log(`[package] ${JSON.stringify({ ...facts, csp: undefined, references: undefined })}`);
  });

  test.describe("extracted package over file://", () => {
    test("mounts and reaches Login with no console errors other than webapis", async ({ page, shot }) => {
      const zip = readZip(WGT);
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), "wgt-"));
      for (const name of zip.entries.keys()) {
        if (name.endsWith("/")) continue;
        const out = path.join(dir, name);
        fs.mkdirSync(path.dirname(out), { recursive: true });
        fs.writeFileSync(out, zip.read(name));
      }
      await fakePairing(page);
      const errors = [];
      page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
      const t0 = Date.now();
      await page.goto(`file://${dir}/index.html`);
      await expect(screen(page, "login")).toBeVisible({ timeout: 30_000 });
      console.log(`[package file://] login after ${Date.now() - t0} ms`);
      await shot("wgt-file-url");
      // webapis.js only exists on a set; everything else must load.
      expect(errors.filter((e) => !/webapis|ERR_FILE_NOT_FOUND|net::ERR/.test(e))).toEqual([]);
      fs.rmSync(dir, { recursive: true, force: true });
    });
  });
});
