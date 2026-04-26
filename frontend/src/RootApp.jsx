import { Suspense, lazy, useEffect, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from "react-router-dom";
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

function LoginPage({ onLogin, title, subtitle }) {
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onLogin(login, password);
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-panel">
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
    </div>
  );
}

function ProtectedRoute({ session, role, children }) {
  if (!session?.user) {
    return <Navigate to={role === "ADMIN" ? "/admin/login" : "/login"} replace />;
  }
  if (role && session.user.role !== role) {
    return <Navigate to={session.user.role === "ADMIN" ? "/admin" : "/"} replace />;
  }
  return children;
}

function RootRouter() {
  const navigate = useNavigate();
  const [session, setSession] = useState(() => getStoredSession());
  const [authNotice, setAuthNotice] = useState("");

  useEffect(() => subscribeToSession(setSession), []);
  useEffect(() => onAuthFailure(() => {
    setAuthNotice("Session expired. Please sign in again.");
    navigate("/login");
  }), [navigate]);

  useEffect(() => {
    if (!session?.accessToken) return undefined;
    const timer = window.setInterval(() => {
      apiJson("/api/auth/heartbeat", { method: "POST" }).catch(() => {});
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [session?.accessToken]);

  async function loginAs(login, password) {
    const payload = await apiJson("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ login, password }),
    });
    setAuthNotice("");
    setStoredSession(payload);
    navigate(payload.user.role === "ADMIN" ? "/admin" : "/");
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
      <Route path="/login" element={session?.user ? <Navigate to={session.user.role === "ADMIN" ? "/admin" : "/"} replace /> : <div>{authNotice ? <div className="session-banner">{authNotice}</div> : null}<LoginPage onLogin={loginAs} title="User Sign In" subtitle="Search, watch, resume, and manage your profile." /></div>} />
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
