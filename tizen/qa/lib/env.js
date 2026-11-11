// Settings for the run. Credentials come from the environment or from an env
// file (STREAMHUB_QA_ENV, default tizen/qa/.env — gitignored). They are never
// logged: nothing here prints a value.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
export const QA_DIR = path.resolve(here, "..");
// STREAMHUB_QA_DIST serves a snapshot instead (a copy taken before the run, so
// another build landing in dist/ mid-run cannot tear the page under the specs).
export const DIST_DIR = path.resolve(process.env.STREAMHUB_QA_DIST || path.resolve(QA_DIR, "..", "dist"));
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

/** `{ username, password }` from `<prefix>_USER` / `<prefix>_PASSWORD`, or null. */
export function credentialsFor(prefix) {
  const file = readEnvFile(process.env.STREAMHUB_QA_ENV || path.join(QA_DIR, ".env"));
  const username = process.env[`${prefix}_USER`] || file[`${prefix}_USER`];
  const password = process.env[`${prefix}_PASSWORD`] || file[`${prefix}_PASSWORD`];
  return username && password ? { username, password } : null;
}

export function credentials() {
  const c = credentialsFor("STREAMHUB_QA");
  if (!c) {
    throw new Error("QA credentials missing: set STREAMHUB_QA_USER / STREAMHUB_QA_PASSWORD or STREAMHUB_QA_ENV=<file>");
  }
  return c;
}

/** The account whose library must stay empty (STREAMHUB_QA_EMPTY_USER / _PASSWORD), or null. */
export const emptyCredentials = () => credentialsFor("STREAMHUB_QA_EMPTY");
