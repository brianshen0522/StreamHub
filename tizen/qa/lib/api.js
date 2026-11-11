// A small client for the live server, signed in as the QA user. Used to seed
// sessions, approve pairing codes, check progress, and clean up afterwards.
import { API_BASE, credentials } from "./env.js";

export class QaApi {
  constructor() { this.session = null; }

  async raw(method, path, { body, token, headers = {} } = {}) {
    const res = await fetch(`${API_BASE}/api/v1${path}`, {
      method,
      headers: {
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: res.status, ok: res.ok, json };
  }

  /** A fresh session as the QA user. `client` sets X-StreamHub-Client (the TV sends "tv"). */
  static async login({ client = "tv" } = {}) {
    const api = new QaApi();
    const r = await api.raw("POST", "/auth/login", {
      body: (({ username, password }) => ({ login: username, password }))(credentials()),
      headers: client ? { "X-StreamHub-Client": client } : {},
    });
    if (!r.ok) throw new Error(`QA login failed: HTTP ${r.status} ${r.json?.error || ""}`);
    api.session = r.json;
    return api;
  }

  async call(method, path, body) {
    let r = await this.raw(method, path, { body, token: this.session.accessToken });
    if (r.status === 401) {
      const ref = await this.raw("POST", "/auth/refresh", { body: { refreshToken: this.session.refreshToken } });
      if (!ref.ok) throw new Error(`QA refresh failed: HTTP ${ref.status}`);
      this.session = { ...this.session, ...ref.json };
      r = await this.raw(method, path, { body, token: this.session.accessToken });
    }
    return r;
  }

  async json(method, path, body) {
    const r = await this.call(method, path, body);
    if (!r.ok) throw new Error(`${method} ${path}: HTTP ${r.status} ${r.json?.error || ""}`);
    return r.json;
  }

  async logout() {
    if (!this.session) return;
    await this.raw("POST", "/auth/logout", { body: { refreshToken: this.session.refreshToken } });
    this.session = null;
  }

  // What the specs create, so teardown can remove exactly that.
  async snapshot() {
    const [p, f, s] = await Promise.all([
      this.json("GET", "/me/progress"),
      this.json("GET", "/me/favorites"),
      this.json("GET", "/me/sessions"),
    ]);
    return {
      progress: p.progress.map(progressId),
      titles: [...new Set(p.progress.map((r) => `${r.providerKey}\u0000${r.title}`))],
      favorites: f.favorites.map((r) => r.id),
      sessions: s.sessions.map((r) => r.id),
    };
  }
}

export const progressId = (r) => [r.providerKey, r.itemUrl, r.seasonUrl || "", r.episodeLabel || ""].join("\u0000");
