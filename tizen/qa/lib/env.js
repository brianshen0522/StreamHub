// Settings for the run. Credentials come from the environment or from an env
// file (STREAMHUB_QA_ENV, default tizen/qa/.env — gitignored). They are never
// logged: nothing here prints a value.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const QA_DIR = path.resolve(here, "..");
export const DIST_DIR = path.resolve(QA_DIR, "..", "dist");
export const APP_PORT = Number(process.env.STREAMHUB_QA_PORT || 5190);
export const APP_URL = `http://localhost:${APP_PORT}`;
export const API_BASE = (process.env.STREAMHUB_QA_API || "http://localhost:8080").replace(/\/$/, "");

function readEnvFile(file) {
  const out = {};
  if (!file || !fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (!m) continue;
    let v = m[2];
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    out[m[1]] = v;
  }
  return out;
}

export function credentials() {
  const file = readEnvFile(process.env.STREAMHUB_QA_ENV || path.join(QA_DIR, ".env"));
  const username = process.env.STREAMHUB_QA_USER || file.STREAMHUB_QA_USER;
  const password = process.env.STREAMHUB_QA_PASSWORD || file.STREAMHUB_QA_PASSWORD;
  if (!username || !password) {
    throw new Error("QA credentials missing: set STREAMHUB_QA_USER / STREAMHUB_QA_PASSWORD or STREAMHUB_QA_ENV=<file>");
  }
  return { username, password };
}
