import { Suspense, lazy, useEffect, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import {
  apiJson,
  clearStoredSession,
  getStoredSession,
  onAuthFailure,
  setStoredSession,
  subscribeToSession,
} from "./api.js";

const AdminPortal = lazy(() => import("./AdminPortal.jsx"));
const UserPortal = lazy(() => import("./UserPortal.jsx"));
const TvSignIn = lazy(() => import("./TvSignIn.jsx"));

/**
 * Keeps the API's own vocabulary off the sign-in screen.
 *
 * Everything else the server says here is already written for a person — wrong
 * credentials, too many attempts, an admin account on a client — and is passed
 * through unchanged. Only the request-schema failure is machine talk, and it
 * only appears when a field is empty, which the form now catches first; this is
 * the belt to that pair of braces.
 */
function readableAuthError(message) {
  if (/^validation failed/i.test(message || "")) {
    return "Enter your username and password.";
  }
  return message;
}

function LoginPage({ onLogin, onSession, title, subtitle, allowTv = false }) {
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();

    // Caught here rather than sent: an empty form fails the request body
    // schema, and the server's answer to that is "Validation failed." — which
    // is true, and tells a person nothing about what to do next. It was
    // reaching the sign-in screen verbatim.
    if (!login.trim() || !password) {
      setError("Enter your username and password.");
      return;
    }

    setSubmitting(true);
    setError("");
    try {
      await onLogin(login, password);
    } catch (submitError) {
      setError(readableAuthError(submitError.message));
    } finally {
      setSubmitting(false);
    }
  }

  // Both ways in, on one screen — the layout the native television app
  // already uses. The form and the QR are peers: side by side where the
  // screen is wide enough, stacked where it is not, and nobody has to find
  // a toggle to reach the one they wanted.
  return (
    <div className="auth-shell">
      <div className={`auth-panel${allowTv ? " auth-panel-split" : ""}`}>
        <div className="auth-half">
          <div className="auth-mark">StreamHub</div>
          <h1>{title}</h1>
          <p>{subtitle}</p>
          <form className="auth-form" onSubmit={handleSubmit}>
            <input value={login} onChange={(event) => setLogin(event.target.value)} placeholder="Username or email" />
            <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Password" />
            <button type="submit" disabled={submitting}>{submitting ? "Signing in..." : "Sign In"}</button>
          </form>
          {error ? <div className="auth-error">{error}</div> : null}
        </div>
        {allowTv ? (
          <div className="auth-half auth-half-qr">
            <h2 className="auth-qr-title">Sign in with your phone</h2>
            <Suspense fallback={<div className="auth-tv-code">········</div>}>
              <TvSignIn onSession={onSession} />
            </Suspense>
          </div>
        ) : null}
      </div>
    </div>
  );
}

function ProtectedRoute({ session, role, children }) {
  const location = useLocation();
  if (!session?.user) {
    // Where they were going travels with the redirect. Scanning the QR on a
    // television lands on /link with the pairing code in the address, and
    // someone who happens to be signed out would otherwise sign in and arrive
    // at Browse with the code gone and nothing explaining why.
    return (
      <Navigate
        to={role === "ADMIN" ? "/admin/login" : "/login"}
        state={{ from: location.pathname + location.search }}
        replace
      />
    );
  }
  if (role && session.user.role !== role) {
    return <Navigate to={session.user.role === "ADMIN" ? "/admin" : "/"} replace />;
  }
  return children;
}

function RootRouter() {
  const navigate = useNavigate();
  const location = useLocation();
  const [session, setSession] = useState(() => getStoredSession());
  const [authNotice, setAuthNotice] = useState("");

  useEffect(() => subscribeToSession(setSession), []);
  useEffect(() => onAuthFailure(() => {
    setAuthNotice("Session expired. Please sign in again.");
    // Read at the moment of failure rather than closed over, so this does not
    // have to re-subscribe on every navigation to know where the person was.
    navigate("/login", { state: { from: window.location.pathname + window.location.search } });
  }), [navigate]);

  useEffect(() => {
    if (!session?.accessToken) return undefined;
    const timer = window.setInterval(() => {
      apiJson("/api/auth/heartbeat", { method: "POST" }).catch(() => {});
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [session?.accessToken]);

  /**
   * Where signing in lands.
   *
   * Storing the session re-renders the sign-in route, which redirects on its
   * own the moment a session exists — so this has to be the answer in *both*
   * places or whichever fires first decides, and the one that fired first was
   * throwing the destination away.
   *
   * `from` arrives in navigation state and is treated as untrusted: an in-app
   * path only, never protocol-relative, and never back to sign-in.
   */
  function landingFor(role) {
    if (role === "ADMIN") return "/admin";
    const from = location.state?.from;
    const safe = typeof from === "string" && from.startsWith("/")
      && !from.startsWith("//") && !from.startsWith("/login");
    return safe ? from : "/";
  }

  async function loginAs(login, password) {
    const payload = await apiJson("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ login, password }),
    });
    adoptSession(payload);
  }

  // A session that arrived whole — the pairing flow's approval — lands the
  // same way a password sign-in does.
  function adoptSession(payload) {
    setAuthNotice("");
    setStoredSession(payload);
    navigate(landingFor(payload.user.role));
  }

  async function logout() {
    try {
      await apiJson("/api/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken: session?.refreshToken }),
      });
    } catch {}
    clearStoredSession();
    setAuthNotice("");
    navigate("/login");
  }

  const loadingFallback = <div className="panel-card">Loading portal...</div>;

  return (
    <Routes>
      <Route path="/login" element={session?.user ? <Navigate to={landingFor(session.user.role)} replace /> : <div>{authNotice ? <div className="session-banner">{authNotice}</div> : null}<LoginPage onLogin={loginAs} onSession={adoptSession} allowTv title="User Sign In" subtitle="Search, watch, resume, and manage your profile." /></div>} />
      <Route path="/admin/login" element={session?.user ? <Navigate to={session.user.role === "ADMIN" ? "/admin" : "/"} replace /> : <div>{authNotice ? <div className="session-banner">{authNotice}</div> : null}<LoginPage onLogin={loginAs} title="Admin Sign In" subtitle="Monitor providers, users, sessions, and system activity." /></div>} />
      <Route path="/admin/*" element={<ProtectedRoute session={session} role="ADMIN"><Suspense fallback={loadingFallback}><AdminPortal session={session} setSession={setSession} onLogout={logout} /></Suspense></ProtectedRoute>} />
      <Route path="/*" element={<ProtectedRoute session={session} role="USER"><Suspense fallback={loadingFallback}><UserPortal session={session} setSession={setSession} onLogout={logout} /></Suspense></ProtectedRoute>} />
    </Routes>
  );
}

export default function RootApp() {
  return (
    <BrowserRouter>
      <RootRouter />
    </BrowserRouter>
  );
}
