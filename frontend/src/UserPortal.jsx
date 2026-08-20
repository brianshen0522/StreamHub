import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from "react";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import { apiJson, getAccessToken, setStoredSession } from "./api.js";
import { PortalChromeContext } from "./portal-chrome.js";
import { resolveLanguage, translations } from "./i18n.js";
import "./portal.css";

const App = lazy(() => import("./App.jsx"));

/* ── icons ─────────────────────────────────────────────────── */

const svgProps = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round",
  strokeLinejoin: "round",
};

const IconCompass = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="m15.2 8.8-2 5.4-5.4 2 2-5.4z" /></svg>;
const IconHeart = () => <svg {...svgProps}><path d="M12 20s-7-4.4-7-9.4A3.9 3.9 0 0 1 12 8a3.9 3.9 0 0 1 7 2.6c0 5-7 9.4-7 9.4Z" /></svg>;
const IconPlayCircle = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="m10.2 8.8 5 3.2-5 3.2z" /></svg>;
const IconClock = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="M12 7.2V12l3 1.8" /></svg>;
const IconUser = () => <svg {...svgProps}><circle cx="12" cy="8" r="3.6" /><path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></svg>;
const IconLogout = () => <svg {...svgProps}><path d="M15 17v1.5A2.5 2.5 0 0 1 12.5 21h-6A2.5 2.5 0 0 1 4 18.5v-13A2.5 2.5 0 0 1 6.5 3h6A2.5 2.5 0 0 1 15 5.5V7" /><path d="M10 12h11M18 9l3 3-3 3" /></svg>;
const IconClose = () => <svg {...svgProps}><path d="M18 6 6 18M6 6l12 12" /></svg>;
const IconFilm = () => <svg {...svgProps}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9.5h18M3 14.5h18M8 4v16M16 4v16" /></svg>;
const IconCheck = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="m8.2 12.2 2.6 2.6 5-5.2" /></svg>;
const IconAlert = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5M12 16.2h.01" /></svg>;

/* ── helpers ───────────────────────────────────────────────── */

function initials(name = "") {
  const parts = String(name).trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

const AVATAR_HUES = [352, 22, 45, 142, 190, 216, 260, 300];

function avatarStyle(seed = "") {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  const hue = AVATAR_HUES[hash % AVATAR_HUES.length];
  return { background: `linear-gradient(150deg, hsl(${hue} 62% 46%), hsl(${(hue + 24) % 360} 58% 32%))` };
}

function formatRelative(value) {
  if (!value) return "";
  const diff = Date.now() - new Date(value).getTime();
  if (diff < 60_000) return "just now";
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(value).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function dayBucket(value) {
  const date = new Date(value);
  const today = new Date();
  const startOfDay = (input) => new Date(input.getFullYear(), input.getMonth(), input.getDate()).getTime();
  const dayDiff = Math.round((startOfDay(today) - startOfDay(date)) / 86_400_000);
  if (dayDiff <= 0) return "Today";
  if (dayDiff === 1) return "Yesterday";
  if (dayDiff < 7) return `${dayDiff} days ago`;
  return date.toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });
}

function formatClock(value) {
  return new Date(value).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

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

function posterProxyUrl(url) {
  if (!url) return "";
  try {
    return new URL(
      `/api/poster?target=${encodeURIComponent(url)}&accessToken=${encodeURIComponent(getAccessToken())}`,
      window.location.origin,
    ).toString();
  } catch {
    return "";
  }
}

/* ── primitives ────────────────────────────────────────────── */

function Poster({ src, alt }) {
  const [failed, setFailed] = useState(false);
  const resolved = src ? posterProxyUrl(src) : "";
  if (!resolved || failed) {
    return (
      <div className="usr-card-fallback">
        <IconFilm />
        <span>No art</span>
      </div>
    );
  }
  return <img className="usr-card-img" src={resolved} alt={alt} loading="lazy" onError={() => setFailed(true)} />;
}

function Thumb({ src, alt }) {
  const [failed, setFailed] = useState(false);
  const resolved = src ? posterProxyUrl(src) : "";
  if (!resolved || failed) {
    return <div className="usr-row-thumb-fallback"><IconFilm /></div>;
  }
  return <img src={resolved} alt={alt} loading="lazy" onError={() => setFailed(true)} />;
}

function Empty({ title, description, action }) {
  return (
    <div className="usr-empty">
      <span className="usr-empty-icon"><IconFilm /></span>
      <strong>{title}</strong>
      <span>{description}</span>
      {action ? <div style={{ marginTop: 10 }}>{action}</div> : null}
    </div>
  );
}

function Alert({ tone = "bad", children }) {
  if (!children) return null;
  return (
    <div className={`usr-alert usr-alert-${tone}`}>
      {tone === "ok" ? <IconCheck /> : <IconAlert />}
      <span>{children}</span>
    </div>
  );
}

function Toast({ message, tone, onDismiss }) {
  useEffect(() => {
    if (!message) return undefined;
    const timer = window.setTimeout(onDismiss, 3500);
    return () => window.clearTimeout(timer);
  }, [message, onDismiss]);

  if (!message) return null;
  return (
    <div className={`usr-toast usr-alert usr-alert-${tone || "ok"}`}>
      {tone === "bad" ? <IconAlert /> : <IconCheck />}
      <span>{message}</span>
    </div>
  );
}

function GridSkeleton({ count = 12 }) {
  return (
    <div className="usr-grid">
      {Array.from({ length: count }, (_, index) => <div key={index} className="usr-skeleton-card" />)}
    </div>
  );
}

/**
 * One poster tile. The title lives *below* the artwork so long
 * Chinese titles can wrap without covering the poster.
 */
function MediaCard({ item, meta, chips, progressPercent, isCompleted, onRemove, removeLabel }) {
  return (
    <div className="usr-card">
      <Link to={playbackHref(item)} className="usr-card" style={{ gap: 8 }} aria-label={item.title}>
        <div className="usr-card-art">
          <Poster src={item.posterUrl} alt={item.title} />
          {chips?.length ? (
            <div className="usr-card-chips">
              {chips.map((chip) => (
                <span key={chip.label} className={`usr-chip${chip.tone ? ` usr-chip-${chip.tone}` : ""}`}>{chip.label}</span>
              ))}
            </div>
          ) : null}
          {progressPercent !== undefined ? (
            <div className="usr-card-progress">
              <div
                className={`usr-card-progress-fill${isCompleted ? " is-done" : ""}`}
                style={{ width: `${Math.min(100, Math.max(2, progressPercent))}%` }}
              />
            </div>
          ) : null}
        </div>
        <div className="usr-card-body">
          <div className="usr-card-title">{item.title}</div>
          {meta ? <div className="usr-card-meta">{meta}</div> : null}
        </div>
      </Link>
      {onRemove ? (
        <button
          type="button"
          className="usr-card-remove"
          aria-label={removeLabel}
          title={removeLabel}
          onClick={(event) => { event.preventDefault(); event.stopPropagation(); onRemove(); }}
        >
          <IconClose />
        </button>
      ) : null}
    </div>
  );
}

/* ── pages ─────────────────────────────────────────────────── */

function FavoritesPage({ setTopbar, toast, onCountsChanged }) {
  const [favorites, setFavorites] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const payload = await apiJson("/api/me/favorites");
      setFavorites(payload.favorites || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
      setFavorites([]);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    setTopbar({
      title: "Favorites",
      count: favorites?.length,
      sub: "Everything you starred, across all providers · hover a poster to remove it",
    });
  }, [setTopbar, favorites]);

  async function remove(entry) {
    const previous = favorites;
    setFavorites((current) => current.filter((item) => item.id !== entry.id));
    try {
      await apiJson(`/api/me/favorites/${entry.id}`, { method: "DELETE" });
      toast(`Removed “${entry.title}”.`);
      onCountsChanged();
    } catch (removeError) {
      setFavorites(previous);
      setError(removeError.message);
    }
  }

  if (favorites === null) return <GridSkeleton />;

  return (
    <>
      <Alert>{error}</Alert>
      {favorites.length ? (
        <div className="usr-grid">
          {favorites.map((item) => (
            <MediaCard
              key={item.id}
              item={item}
              chips={[{ label: item.providerKey, tone: "accent" }]}
              meta={item.episodeLabel || (item.mediaType === "movie" ? "Movie" : item.mediaType === "tv" ? "Series" : "")}
              onRemove={() => remove(item)}
              removeLabel={`Remove ${item.title} from favorites`}
            />
          ))}
        </div>
      ) : (
        <Empty
          title="No favorites yet"
          description="Star a title while watching and it will show up here for quick access."
          action={<Link to="/" className="usr-btn usr-btn-primary">Browse titles</Link>}
        />
      )}
    </>
  );
}

function ContinuePage({ setTopbar, toast, onCountsChanged }) {
  const [items, setItems] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const payload = await apiJson("/api/me/continue-watching");
      setItems(payload.items || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
      setItems([]);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    setTopbar({
      title: "Continue watching",
      count: items?.length,
      sub: "Resume points save automatically · hover a poster to drop one",
    });
  }, [setTopbar, items]);

  async function remove(entry) {
    const previous = items;
    setItems((current) => current.filter((item) => item.id !== entry.id));
    try {
      await apiJson("/api/me/progress", {
        method: "DELETE",
        body: JSON.stringify({
          providerKey: entry.providerKey,
          itemUrl: entry.itemUrl,
          seasonUrl: entry.seasonUrl || "",
          episodeLabel: entry.episodeLabel || "",
        }),
      });
      toast(`Removed “${entry.title}” from your list.`);
      onCountsChanged();
    } catch (removeError) {
      setItems(previous);
      setError(removeError.message);
    }
  }

  if (items === null) return <GridSkeleton count={8} />;

  return (
    <>
      <Alert>{error}</Alert>
      {items.length ? (
        <div className="usr-grid">
          {items.map((item) => {
            const percent = Math.round(item.progressPercent || 0);
            const remaining = Math.max(0, (item.durationSeconds || 0) - (item.positionSeconds || 0));
            const remainingLabel = remaining > 0 ? `${Math.ceil(remaining / 60)} min left` : "";
            return (
              <MediaCard
                key={item.id}
                item={item}
                chips={[
                  { label: item.providerKey, tone: "accent" },
                  ...(item.episodeLabel ? [{ label: item.episodeLabel }] : []),
                ]}
                meta={[`${percent}%`, remainingLabel].filter(Boolean).join(" · ")}
                progressPercent={percent}
                isCompleted={item.isCompleted}
                onRemove={() => remove(item)}
                removeLabel={`Remove ${item.title} from continue watching`}
              />
            );
          })}
        </div>
      ) : (
        <Empty
          title="Nothing in progress"
          description="Start playing something and StreamHub will remember your position here."
          action={<Link to="/" className="usr-btn usr-btn-primary">Browse titles</Link>}
        />
      )}
    </>
  );
}

function HistoryPage({ setTopbar }) {
  const [history, setHistory] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    apiJson("/api/me/history")
      .then((payload) => setHistory(payload.history || []))
      .catch((loadError) => { setError(loadError.message); setHistory([]); });
  }, []);

  useEffect(() => {
    setTopbar({
      title: "History",
      count: history?.length,
      sub: "Recorded on pause, source switch, and completion · newest first",
    });
  }, [setTopbar, history]);

  // History is chronological, so group it by day rather than dumping a poster
  // wall where the same series repeats once per episode.
  const days = useMemo(() => {
    if (!history) return [];
    const groups = new Map();
    for (const entry of history) {
      const label = dayBucket(entry.watchedAt);
      if (!groups.has(label)) groups.set(label, []);
      groups.get(label).push(entry);
    }
    return [...groups.entries()].map(([label, entries]) => ({ label, entries }));
  }, [history]);

  if (history === null) {
    return (
      <div className="usr-rows">
        {Array.from({ length: 8 }, (_, index) => (
          <div key={index} className="usr-row"><div className="usr-row-thumb" /><div className="usr-row-main" /></div>
        ))}
      </div>
    );
  }

  return (
    <>
      <Alert>{error}</Alert>
      {days.length ? days.map((day) => (
        <section key={day.label} className="usr-day">
          <div className="usr-day-label">{day.label}</div>
          <div className="usr-rows">
            {day.entries.map((entry) => (
              <Link key={entry.id} to={playbackHref(entry)} className="usr-row">
                <div className="usr-row-thumb"><Thumb src={entry.posterUrl} alt={entry.title} /></div>
                <div className="usr-row-main">
                  <div className="usr-row-title">{entry.title}</div>
                  <div className="usr-row-meta">
                    <span className="usr-chip usr-chip-accent">{entry.providerKey}</span>
                    {entry.episodeLabel ? <span>{entry.episodeLabel}</span> : null}
                    {entry.sourceLabel ? <><span className="usr-dot-sep">·</span><span>{entry.sourceLabel}</span></> : null}
                    {entry.durationSeconds > 0 ? (
                      <>
                        <span className="usr-dot-sep">·</span>
                        <span>{Math.round((entry.positionSeconds / entry.durationSeconds) * 100)}% watched</span>
                      </>
                    ) : null}
                  </div>
                </div>
                <div className="usr-row-time" title={new Date(entry.watchedAt).toLocaleString()}>
                  {formatClock(entry.watchedAt)}
                </div>
              </Link>
            ))}
          </div>
        </section>
      )) : (
        <Empty
          title="No watch history"
          description="Once you play something, every session shows up here grouped by day."
          action={<Link to="/" className="usr-btn usr-btn-primary">Browse titles</Link>}
        />
      )}
    </>
  );
}

function ProfilePage({ session, setSession, setTopbar, toast }) {
  const [form, setForm] = useState({
    username: session.user.username,
    email: session.user.email,
    displayName: session.user.displayName,
  });
  const [currentPassword, setCurrentPassword] = useState("");
  const [nextPassword, setNextPassword] = useState("");
  const [error, setError] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);
  const [providers, setProviders] = useState([]);

  useEffect(() => {
    setTopbar({ title: "Profile", sub: "Update your details and rotate your password" });
  }, [setTopbar]);

  useEffect(() => {
    apiJson("/api/me/providers").then((payload) => setProviders(payload.providers || [])).catch(() => {});
  }, []);

  async function saveProfile(event) {
    event.preventDefault();
    setSavingProfile(true);
    setError("");
    try {
      const payload = await apiJson("/api/auth/me/profile", { method: "PATCH", body: JSON.stringify(form) });
      const nextSession = { ...session, user: payload.user };
      setStoredSession(nextSession);
      setSession(nextSession);
      toast("Profile updated.");
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSavingProfile(false);
    }
  }

  async function savePassword(event) {
    event.preventDefault();
    setSavingPassword(true);
    setError("");
    try {
      await apiJson("/api/auth/me/password", { method: "PATCH", body: JSON.stringify({ currentPassword, nextPassword }) });
      setCurrentPassword("");
      setNextPassword("");
      toast("Password updated.");
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSavingPassword(false);
    }
  }

  return (
    <>
      <Alert>{error}</Alert>
      <div className="usr-cols-2">
        <section className="usr-panel">
          <header className="usr-panel-head">
            <div className="usr-panel-title">Account</div>
            <div className="usr-panel-desc">Your name, username, and email</div>
          </header>
          <div className="usr-panel-body">
            <form className="usr-form" onSubmit={saveProfile}>
              <label className="usr-field">
                <span className="usr-label">Display name</span>
                <input className="usr-input" name="displayName" value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} />
              </label>
              <label className="usr-field">
                <span className="usr-label">Username</span>
                <input className="usr-input" name="username" value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} />
              </label>
              <label className="usr-field">
                <span className="usr-label">Email</span>
                <input className="usr-input" name="email" type="email" value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} />
              </label>
              <div className="usr-form-actions">
                <button type="submit" className="usr-btn usr-btn-primary" disabled={savingProfile}>
                  {savingProfile ? "Saving…" : "Save profile"}
                </button>
              </div>
            </form>
          </div>
        </section>

        <div style={{ display: "grid", gap: 18 }}>
          <section className="usr-panel">
            <header className="usr-panel-head">
              <div className="usr-panel-title">Password</div>
              <div className="usr-panel-desc">Choose something at least 6 characters long</div>
            </header>
            <div className="usr-panel-body">
              <form className="usr-form" onSubmit={savePassword}>
                <label className="usr-field">
                  <span className="usr-label">Current password</span>
                  <input className="usr-input" name="currentPassword" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} placeholder="••••••••" />
                </label>
                <label className="usr-field">
                  <span className="usr-label">New password</span>
                  <input className="usr-input" name="nextPassword" type="password" autoComplete="new-password" value={nextPassword} onChange={(event) => setNextPassword(event.target.value)} placeholder="••••••••" />
                </label>
                <div className="usr-form-actions">
                  <button type="submit" className="usr-btn usr-btn-ghost" disabled={savingPassword || !currentPassword || !nextPassword}>
                    {savingPassword ? "Saving…" : "Update password"}
                  </button>
                </div>
              </form>
            </div>
          </section>

          <section className="usr-panel">
            <header className="usr-panel-head">
              <div className="usr-panel-title">Access</div>
              <div className="usr-panel-desc">Providers your account can search — managed by an admin</div>
            </header>
            <div className="usr-panel-body">
              <div className="usr-kv">
                <div className="usr-kv-row"><span>Member since</span><span>{new Date(session.user.createdAt).toLocaleDateString()}</span></div>
                <div className="usr-kv-row"><span>Last sign-in</span><span>{formatRelative(session.user.lastLoginAt) || "—"}</span></div>
                <div className="usr-kv-row">
                  <span>Providers</span>
                  <span>
                    {providers.length
                      ? providers.map((provider) => provider.name || provider.key).join(", ")
                      : "None enabled"}
                  </span>
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </>
  );
}

/* ── shell ─────────────────────────────────────────────────── */

export default function UserPortal({ session, setSession, onLogout }) {
  const location = useLocation();
  const [chrome, setChrome] = useState(null);
  const [topbar, setTopbar] = useState({ title: "", sub: "" });
  const [toastState, setToastState] = useState(null);
  const [counts, setCounts] = useState({ favorites: null, continueWatching: null });
  const [language] = useState(resolveLanguage());

  const t = translations[language] || translations["zh-TW"];
  const isBrowse = location.pathname === "/";

  const toast = useCallback((message, tone = "ok") => setToastState({ message, tone }), []);
  const dismissToast = useCallback(() => setToastState(null), []);

  const loadCounts = useCallback(async () => {
    try {
      const [favorites, continueWatching] = await Promise.all([
        apiJson("/api/me/favorites").catch(() => ({ favorites: [] })),
        apiJson("/api/me/continue-watching").catch(() => ({ items: [] })),
      ]);
      setCounts({
        favorites: (favorites.favorites || []).length,
        continueWatching: (continueWatching.items || []).length,
      });
    } catch {
      /* sidebar badges are decorative — a failure here should not surface */
    }
  }, []);

  useEffect(() => { loadCounts(); }, [loadCounts]);

  const chromeValue = useMemo(() => ({ setChrome }), []);

  const links = useMemo(() => [
    { to: "/", label: t.navBrowse || "Browse", icon: <IconCompass />, end: true },
    { to: "/continue", label: t.navContinue || "Continue", icon: <IconPlayCircle />, count: counts.continueWatching },
    { to: "/favorites", label: t.navFavorites || "Favorites", icon: <IconHeart />, count: counts.favorites },
    { to: "/history", label: t.navHistory || "History", icon: <IconClock /> },
    { to: "/profile", label: t.navProfile || "Profile", icon: <IconUser /> },
  ], [t, counts]);

  return (
    <div className="usr">
      <div className="usr-shell">
        <aside className="usr-side">
          <div className="usr-brand">
            <span className="usr-brand-dot">S</span>
            <div className="usr-brand-name">StreamHub</div>
          </div>

          <nav className="usr-nav">
            {links.map((link) => {
              const active = link.end ? location.pathname === link.to : location.pathname.startsWith(link.to);
              return (
                <Link key={link.to} to={link.to} className={`usr-nav-item${active ? " is-active" : ""}`}>
                  {link.icon}
                  {link.label}
                  {link.count ? <span className="usr-nav-count">{link.count}</span> : null}
                </Link>
              );
            })}
          </nav>

          <div className="usr-side-foot">
            <div className="usr-side-user">
              <span className="usr-avatar" style={avatarStyle(session.user.displayName)} aria-hidden="true">
                {initials(session.user.displayName)}
              </span>
              <div className="usr-side-user-text">
                <div className="usr-side-user-name">{session.user.displayName}</div>
                <div className="usr-side-user-sub">{session.user.username}</div>
              </div>
            </div>
            <button type="button" className="usr-btn usr-btn-ghost" onClick={onLogout}>
              <IconLogout />
              {t.logout || "Sign out"}
            </button>
          </div>
        </aside>

        <main className="usr-main">
          <header className="usr-topbar">
            {isBrowse && chrome ? chrome : (
              <div>
                <div className="usr-topbar-title">
                  {topbar.title}
                  {topbar.count !== undefined && topbar.count !== null
                    ? <span className="usr-page-count">{topbar.count}</span>
                    : null}
                </div>
                {topbar.sub ? <div className="usr-topbar-sub">{topbar.sub}</div> : null}
              </div>
            )}
          </header>

          <div className={isBrowse ? "usr-body usr-body-flush" : "usr-body"}>
            <PortalChromeContext.Provider value={chromeValue}>
              <Routes>
                <Route index element={<Suspense fallback={<GridSkeleton />}><App /></Suspense>} />
                <Route path="favorites" element={<FavoritesPage setTopbar={setTopbar} toast={toast} onCountsChanged={loadCounts} />} />
                <Route path="continue" element={<ContinuePage setTopbar={setTopbar} toast={toast} onCountsChanged={loadCounts} />} />
                <Route path="history" element={<HistoryPage setTopbar={setTopbar} />} />
                <Route path="profile" element={<ProfilePage session={session} setSession={setSession} setTopbar={setTopbar} toast={toast} />} />
              </Routes>
            </PortalChromeContext.Provider>
          </div>
        </main>
      </div>

      <Toast message={toastState?.message} tone={toastState?.tone} onDismiss={dismissToast} />
    </div>
  );
}
