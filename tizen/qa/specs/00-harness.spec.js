// Not the app: checks that the harness itself can run (server reachable,
// credentials good, dist served with the right types, CORS for this origin).
// If these fail, every other failure is noise.
import { test, expect } from "../lib/fixtures.js";
import { API_BASE, APP_URL } from "../lib/env.js";

test("harness: server, credentials, dist and CORS are usable", async ({ qaApi, request }) => {
  const health = await request.get(`${API_BASE}/api/v1/health`);
  expect(health.ok()).toBeTruthy();

  const me = await qaApi.json("GET", "/auth/me");
  expect(me.user?.role, "the QA account must be a viewer, not an admin").toBe("USER");

  const index = await request.get(`${APP_URL}/`);
  expect(index.ok()).toBeTruthy();
  const html = await index.text();
  // The loader lives in js/start.js (the widget CSP forbids inline scripts),
  // so the .wasm is named there, not in index.html.
  expect(html, "index.html loads the start script").toContain('src="js/start.js"');
  const start = await (await request.get(`${APP_URL}/js/start.js`)).text();
  const wasm = start.match(/['"]([^'"]+_bg\.wasm)['"]/)?.[1];
  expect(wasm, "js/start.js references a .wasm").toBeTruthy();
  const w = await request.get(new URL(wasm, APP_URL).toString());
  expect(w.headers()["content-type"]).toBe("application/wasm");

  const cors = await request.get(`${API_BASE}/api/v1/health`, { headers: { Origin: APP_URL } });
  expect(cors.headers()["access-control-allow-origin"]).toBe(APP_URL);
});
