// Serves tizen/dist on :5190, signs the QA user in once, and snapshots the
// account so the returned teardown can remove what the run creates.
import fs from "node:fs";
import { serve } from "./tools/serve.mjs";
import { QaApi } from "./lib/api.js";
import { cleanup } from "./lib/cleanup.js";
import { APP_PORT, DIST_DIR } from "./lib/env.js";

export default async function globalSetup() {
  if (!fs.existsSync(`${DIST_DIR}/index.html`)) {
    throw new Error(`${DIST_DIR}/index.html missing — run ../build.sh dev first (run.sh does)`);
  }
  const server = await serve(DIST_DIR, APP_PORT);
  const api = await QaApi.login();
  const before = await api.snapshot();

  return async () => {
    try {
      const report = await cleanup(api, before);
      console.log(`[qa cleanup] progress titles/rows ${report.progress}, favourites ${report.favorites}, sessions ${report.sessions}`);
    } catch (error) {
      console.error(`[qa cleanup] FAILED: ${error.message}`);
    } finally {
      await api.logout();
      server.close();
    }
  };
}
