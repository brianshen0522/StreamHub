const SESSION_STORAGE_KEY = "streamhub.session";

const sessionListeners = new Set();
const authFailureListeners = new Set();

let refreshPromise = null;

function notifySession(session) {
  sessionListeners.forEach((listener) => {
    try {
      listener(session);
    } catch {}
  });
}

function notifyAuthFailure() {
  authFailureListeners.forEach((listener) => {
    try {
      listener();
    } catch {}
  });
}

export function getStoredSession() {
  try {
    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setStoredSession(session) {
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  notifySession(session);
}

export function clearStoredSession({ notify = true } = {}) {
  window.localStorage.removeItem(SESSION_STORAGE_KEY);
  if (notify) {
    notifySession(null);
  }
}

export function subscribeToSession(listener) {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

export function onAuthFailure(listener) {
  authFailureListeners.add(listener);
  return () => {
    authFailureListeners.delete(listener);
  };
}

export function getAccessToken() {
  return getStoredSession()?.accessToken || "";
}

function shouldAttemptRefresh(path) {
  return !path.includes("/api/auth/login") && !path.includes("/api/auth/refresh");
}

async function performRefresh() {
  const session = getStoredSession();
  const refreshToken = session?.refreshToken;
  if (!refreshToken) {
    clearStoredSession();
    notifyAuthFailure();
    throw new Error("Session expired. Please sign in again.");
  }

  const response = await fetch("/api/auth/refresh", {
    method: "POST",
    headers: {
      "content-type": "application/json",
    },
    body: JSON.stringify({ refreshToken }),
  });

  const isJson = (response.headers.get("content-type") || "").includes("application/json");
  const payload = isJson ? await response.json() : null;
  if (!response.ok) {
    clearStoredSession();
    notifyAuthFailure();
    throw new Error(payload?.error || "Session expired. Please sign in again.");
  }

  const nextSession = {
    ...session,
    ...payload,
  };
  setStoredSession(nextSession);
  return nextSession;
}

async function ensureFreshSession() {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

export async function apiFetch(path, options = {}, attempt = 0) {
  const headers = new Headers(options.headers || {});
  const token = getAccessToken();
  if (token) {
    headers.set("authorization", `Bearer ${token}`);
  }
  if (options.body && !headers.has("content-type")) {
    headers.set("content-type", "application/json");
  }

  const response = await fetch(path, {
    ...options,
    headers,
  });

  if (response.status === 401 && attempt === 0 && shouldAttemptRefresh(path)) {
    await ensureFreshSession();
    return apiFetch(path, options, 1);
  }

  return response;
}

export async function apiJson(path, options = {}) {
  const response = await apiFetch(path, options);
  const isJson = (response.headers.get("content-type") || "").includes("application/json");
  const payload = isJson ? await response.json() : null;
  if (!response.ok) {
    throw new Error(payload?.error || "Request failed.");
  }
  return payload;
}

export async function apiNdjsonStream(path, options = {}, onItem) {
  const response = await apiFetch(path, options);
  if (!response.ok) {
    const isJson = (response.headers.get("content-type") || "").includes("application/json");
    const payload = isJson ? await response.json() : null;
    throw new Error(payload?.error || "Request failed.");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop();
      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed) onItem(JSON.parse(trimmed));
      }
    }
    const trimmed = buffer.trim();
    if (trimmed) onItem(JSON.parse(trimmed));
  } catch (error) {
    if (error.name === "AbortError") return;
    throw error;
  } finally {
    reader.cancel().catch(() => {});
  }
}
