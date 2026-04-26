import { Suspense, lazy, useEffect, useMemo, useState } from "react";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import { apiJson, getAccessToken, setStoredSession } from "./api.js";

const App = lazy(() => import("./App.jsx"));

function encodeViewState({ providerKey, itemUrl, title, mediaType, posterUrl, seasonUrl, episodeLabel }) {
  try {
    const obj = { p: providerKey, u: itemUrl, t: title, m: mediaType };
    if (posterUrl) obj.ps = posterUrl;
    if (seasonUrl) obj.s = seasonUrl;
    if (episodeLabel) obj.ep = episodeLabel;
    const latin1 = encodeURIComponent(JSON.stringify(obj)).replace(/%([0-9A-F]{2})/gi, (_, hex) => String.fromCharCode(Number.parseInt(hex, 16)));
    return btoa(latin1).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  } catch {
    return "";
  }
}

function playbackHref(item) {
  const state = encodeViewState({
    providerKey: item.providerKey,
    itemUrl: item.itemUrl,
    title: item.title,
    mediaType: item.mediaType,
    posterUrl: item.posterUrl || "",
    seasonUrl: item.seasonUrl || "",
    episodeLabel: item.episodeLabel || "",
  });
  return state ? `/?v=${state}` : "/";
}

function withCurrentOrigin(url) {
  if (!url) return url;
  try {
    return new URL(url, window.location.origin).toString();
  } catch {
    return url;
  }
}

function posterProxyUrl(url) {
  if (!url) return "";
  return withCurrentOrigin(`/api/poster?target=${encodeURIComponent(url)}&accessToken=${encodeURIComponent(getAccessToken())}`);
}

function Shell({ title, links, onLogout, children }) {
  const location = useLocation();
  return (
    <div className="portal-shell">
      <aside className="portal-sidebar">
        <div>
          <div className="portal-mark">StreamHub</div>
          <div className="portal-title">{title}</div>
        </div>
        <nav className="portal-nav">
          {links.map((link) => (
            <Link key={link.to} to={link.to} className={location.pathname === link.to ? "active" : ""}>{link.label}</Link>
          ))}
        </nav>
        <button type="button" className="portal-logout" onClick={onLogout}>Logout</button>
      </aside>
      <div className="portal-content">{children}</div>
    </div>
  );
}

function RecordCard({ item, extraMeta }) {
  return (
    <Link to={playbackHref(item)} className="record-card record-link">
      <strong>{item.title}</strong>
      <div>{item.providerKey}</div>
      <div className="record-meta">{item.episodeLabel || item.mediaType}</div>
      {extraMeta ? <div className="record-meta">{extraMeta}</div> : null}
    </Link>
  );
}

function MediaPosterCard({ item, meta }) {
  return (
    <Link to={playbackHref(item)} className="poster-card media-library-card">
      {item.posterUrl ? (
        <img
          src={posterProxyUrl(item.posterUrl)}
          alt={item.title}
          className="poster-img"
          loading="lazy"
        />
      ) : (
        <div className="poster-fallback">No Image</div>
      )}
      <div className="poster-overlay">
        <div className="overlay-chips">
          <span className="chip chip-accent">{item.providerKey}</span>
          <span className="chip">{item.episodeLabel || item.mediaType}</span>
        </div>
        <p className="overlay-title">{item.title}</p>
        {meta ? <p className="overlay-meta">{meta}</p> : null}
      </div>
    </Link>
  );
}

function FavoritesPage() {
  const [favorites, setFavorites] = useState([]);
  useEffect(() => { apiJson("/api/me/favorites").then((payload) => setFavorites(payload.favorites)).catch(() => {}); }, []);
  return (
    <div className="library-page">
      <div className="library-header">
        <h1>Favorites</h1>
        <p>Your saved movies and series, kept in the same visual flow as browsing.</p>
      </div>
      <div className="poster-grid">
        {favorites.map((item) => <MediaPosterCard key={item.id} item={item} />)}
      </div>
    </div>
  );
}

function HistoryPage() {
  const [history, setHistory] = useState([]);
  useEffect(() => { apiJson("/api/me/history").then((payload) => setHistory(payload.history)).catch(() => {}); }, []);
  return (
    <div className="library-page">
      <div className="library-header">
        <h1>Watch History</h1>
        <p>Jump back into titles you opened recently, with the last watch time visible on the card.</p>
      </div>
      <div className="poster-grid">
        {history.map((item) => (
          <MediaPosterCard
            key={item.id}
            item={item}
            meta={new Date(item.watchedAt).toLocaleString()}
          />
        ))}
      </div>
    </div>
  );
}

function ContinuePage() {
  const [items, setItems] = useState([]);
  useEffect(() => { apiJson("/api/me/continue-watching").then((payload) => setItems(payload.items)).catch(() => {}); }, []);
  return (
    <div className="library-page">
      <div className="library-header">
        <h1>Continue Watching</h1>
        <p>Resume from your saved playback progress without switching to a different layout style.</p>
      </div>
      <div className="poster-grid">
        {items.map((item) => (
          <MediaPosterCard
            key={item.id}
            item={item}
            meta={`${Math.round(item.progressPercent)}% complete`}
          />
        ))}
      </div>
    </div>
  );
}

function ProfilePage({ session, setSession }) {
  const [form, setForm] = useState({ username: session.user.username, email: session.user.email, displayName: session.user.displayName });
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [nextPassword, setNextPassword] = useState("");

  async function handleProfileSubmit(event) {
    event.preventDefault();
    setMessage("");
    setError("");
    try {
      const payload = await apiJson("/api/auth/me/profile", { method: "PATCH", body: JSON.stringify(form) });
      const nextSession = { ...session, user: payload.user };
      setStoredSession(nextSession);
      setSession(nextSession);
      setMessage("Profile updated.");
    } catch (submitError) { setError(submitError.message); }
  }

  async function handlePasswordSubmit(event) {
    event.preventDefault();
    setMessage("");
    setError("");
    try {
      await apiJson("/api/auth/me/password", { method: "PATCH", body: JSON.stringify({ currentPassword, nextPassword }) });
      setCurrentPassword("");
      setNextPassword("");
      setMessage("Password updated.");
    } catch (submitError) { setError(submitError.message); }
  }

  return (
    <div className="panel-grid">
      <div className="panel-card">
        <h2>Profile</h2>
        <form className="stack-form" onSubmit={handleProfileSubmit}>
          <input value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} />
          <input value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} />
          <input value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} />
          <button type="submit">Save Profile</button>
        </form>
      </div>
      <div className="panel-card">
        <h2>Change Password</h2>
        <form className="stack-form" onSubmit={handlePasswordSubmit}>
          <input type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} placeholder="Current password" />
          <input type="password" value={nextPassword} onChange={(event) => setNextPassword(event.target.value)} placeholder="New password" />
          <button type="submit">Update Password</button>
        </form>
      </div>
      {message ? <div className="success-banner">{message}</div> : null}
      {error ? <div className="auth-error">{error}</div> : null}
    </div>
  );
}

export default function UserPortal({ session, setSession, onLogout }) {
  const links = useMemo(() => [
    { to: "/", label: "Browse" },
    { to: "/favorites", label: "Favorites" },
    { to: "/history", label: "History" },
    { to: "/continue", label: "Continue" },
    { to: "/profile", label: "Profile" },
  ], []);

  return (
    <Shell title={`User · ${session.user.displayName}`} links={links} onLogout={onLogout}>
      <Routes>
        <Route index element={<Suspense fallback={<div className="panel-card">Loading player...</div>}><App /></Suspense>} />
        <Route path="favorites" element={<FavoritesPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="continue" element={<ContinuePage />} />
        <Route path="profile" element={<ProfilePage session={session} setSession={setSession} />} />
      </Routes>
    </Shell>
  );
}
