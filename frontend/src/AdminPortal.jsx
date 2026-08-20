import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import { apiJson, setStoredSession } from "./api.js";
import "./admin.css";

/* ── icons ─────────────────────────────────────────────────── */

const svgProps = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round",
  strokeLinejoin: "round",
};

const IconGrid = () => <svg {...svgProps}><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></svg>;
const IconServer = () => <svg {...svgProps}><rect x="3" y="4" width="18" height="7" rx="2" /><rect x="3" y="13" width="18" height="7" rx="2" /><path d="M7 7.5h.01M7 16.5h.01" /></svg>;
const IconUsers = () => <svg {...svgProps}><path d="M16 20v-1.5a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4V20" /><circle cx="9" cy="7" r="3.2" /><path d="M22 20v-1.5a4 4 0 0 0-3-3.87M16.5 3.9a4 4 0 0 1 0 6.2" /></svg>;
const IconShield = () => <svg {...svgProps}><path d="M12 21s7-3.5 7-9V5.5L12 3 5 5.5V12c0 5.5 7 9 7 9Z" /><path d="M9.5 12.2l1.8 1.8 3.4-3.6" /></svg>;
const IconUser = () => <svg {...svgProps}><circle cx="12" cy="8" r="3.6" /><path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></svg>;
const IconSearch = () => <svg {...svgProps}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-3.6-3.6" /></svg>;
const IconPlus = () => <svg {...svgProps}><path d="M12 5v14M5 12h14" /></svg>;
const IconRefresh = () => <svg {...svgProps}><path d="M20 11a8 8 0 0 0-13.7-5.3L3 9" /><path d="M3 4v5h5" /><path d="M4 13a8 8 0 0 0 13.7 5.3L21 15" /><path d="M21 20v-5h-5" /></svg>;
const IconClose = () => <svg {...svgProps}><path d="M18 6 6 18M6 6l12 12" /></svg>;
const IconChevron = () => <svg {...svgProps}><path d="m9 5 7 7-7 7" /></svg>;
const IconTrash = () => <svg {...svgProps}><path d="M3.5 6h17M9 6V4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V6" /><path d="M18.5 6 18 19.5A1.5 1.5 0 0 1 16.5 21h-9A1.5 1.5 0 0 1 6 19.5L5.5 6" /></svg>;
const IconKey = () => <svg {...svgProps}><circle cx="8" cy="15" r="4" /><path d="m10.8 12.2 8.2-8.2M17 6l2.5 2.5M14.5 8.5 17 11" /></svg>;
const IconAlert = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5M12 16.2h.01" /></svg>;
const IconCheck = () => <svg {...svgProps}><circle cx="12" cy="12" r="9" /><path d="m8.2 12.2 2.6 2.6 5-5.2" /></svg>;
const IconInbox = () => <svg {...svgProps}><path d="M3 12h5l1.5 3h5L16 12h5" /><path d="M4.5 5.5h15L21 12v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-5Z" /></svg>;
const IconLogout = () => <svg {...svgProps}><path d="M15 17v1.5A2.5 2.5 0 0 1 12.5 21h-6A2.5 2.5 0 0 1 4 18.5v-13A2.5 2.5 0 0 1 6.5 3h6A2.5 2.5 0 0 1 15 5.5V7" /><path d="M10 12h11M18 9l3 3-3 3" /></svg>;

/* ── formatting ────────────────────────────────────────────── */

const ONLINE_WINDOW_MS = 120_000;

function formatDate(value) {
  if (!value) return "Never";
  return new Date(value).toLocaleString(undefined, {
    year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
  });
}

function formatRelative(value) {
  if (!value) return "Never";
  const diff = Date.now() - new Date(value).getTime();
  const future = diff < 0;
  const span = Math.abs(diff);
  const suffix = (text) => (future ? `in ${text}` : `${text} ago`);
  if (span < 60_000) return future ? "in a moment" : "just now";
  const minutes = Math.floor(span / 60_000);
  if (minutes < 60) return suffix(`${minutes}m`);
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return suffix(`${hours}h`);
  const days = Math.floor(hours / 24);
  if (days < 30) return suffix(`${days}d`);
  return new Date(value).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function formatDuration(seconds) {
  const safe = Math.max(0, Math.floor(Number(seconds) || 0));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m ${safe % 60}s`;
}

function isOnline(lastSeenAt) {
  return !!lastSeenAt && Date.now() - new Date(lastSeenAt).getTime() < ONLINE_WINDOW_MS;
}

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

function healthTone(status) {
  if (status === "HEALTHY") return "ok";
  if (status === "DEGRADED") return "warn";
  if (status === "DOWN") return "bad";
  return "muted";
}

/* ── primitives ────────────────────────────────────────────── */

function Btn({ variant = "ghost", size, busy, icon, block, type = "button", disabled, children, ...rest }) {
  return (
    <button
      {...rest}
      type={type}
      className={`adm-btn adm-btn-${variant}${size === "sm" ? " adm-btn-sm" : ""}${block ? " adm-btn-block" : ""}`}
      disabled={disabled || busy}
    >
      {busy ? <span className="adm-spin" /> : icon}
      {children}
    </button>
  );
}

function Card({ title, desc, actions, flush, children }) {
  return (
    <section className="adm-card">
      {title ? (
        <header className="adm-card-head">
          <div>
            <div className="adm-card-title">{title}</div>
            {desc ? <div className="adm-card-desc">{desc}</div> : null}
          </div>
          {actions ? <div className="adm-topbar-actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className={flush ? "adm-card-body adm-card-body-flush" : "adm-card-body"}>{children}</div>
    </section>
  );
}

function Stat({ label, value, sub, tone }) {
  return (
    <div className={`adm-stat${tone ? ` tone-${tone}` : ""}`}>
      <div className="adm-stat-label">{label}</div>
      <div className="adm-stat-value">{value}</div>
      {sub ? <div className="adm-stat-sub">{sub}</div> : null}
    </div>
  );
}

function Badge({ tone, dot, children }) {
  return (
    <span className={`adm-badge${tone ? ` tone-${tone}` : ""}`}>
      {dot ? <span className="adm-badge-dot" /> : null}
      {children}
    </span>
  );
}

function Avatar({ name, size }) {
  return (
    <span
      className={`adm-avatar${size ? ` adm-avatar-${size}` : ""}`}
      style={avatarStyle(name)}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  );
}

function Field({ label, hint, children }) {
  return (
    <label className="adm-field">
      <span className="adm-label">{label}</span>
      {children}
      {hint ? <span className="adm-hint">{hint}</span> : null}
    </label>
  );
}

function SearchInput({ value, onChange, placeholder }) {
  return (
    <div className="adm-search">
      <IconSearch />
      <input className="adm-input" name="search" type="search" value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
    </div>
  );
}

function Switch({ checked, onChange, disabled, label }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={onChange}
      className={`adm-switch${checked ? " is-on" : ""}`}
    />
  );
}

function Empty({ title, description }) {
  return (
    <div className="adm-empty">
      <span className="adm-empty-icon"><IconInbox /></span>
      <strong>{title}</strong>
      <span>{description}</span>
    </div>
  );
}

function Alert({ tone = "bad", children }) {
  if (!children) return null;
  return (
    <div className={`adm-alert adm-alert-${tone}`}>
      {tone === "ok" ? <IconCheck /> : <IconAlert />}
      <span>{children}</span>
    </div>
  );
}

function Toast({ message, tone, onDismiss }) {
  useEffect(() => {
    if (!message) return undefined;
    const timer = window.setTimeout(onDismiss, 4000);
    return () => window.clearTimeout(timer);
  }, [message, onDismiss]);

  if (!message) return null;
  return <div className={`adm-toast adm-alert adm-alert-${tone || "ok"}`}>{tone === "bad" ? <IconAlert /> : <IconCheck />}<span>{message}</span></div>;
}

function Modal({ open, title, description, onClose, children }) {
  useEffect(() => {
    if (!open) return undefined;
    function onKey(event) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <>
      <div className="adm-scrim" onClick={onClose} />
      <div className="adm-modal-wrap">
        <div className="adm-modal" role="dialog" aria-modal="true" aria-label={title}>
          <div className="adm-modal-head">
            <div>
              <div className="adm-modal-title">{title}</div>
              {description ? <div className="adm-modal-desc">{description}</div> : null}
            </div>
            <button type="button" className="adm-icon-btn" onClick={onClose} aria-label="Close"><IconClose /></button>
          </div>
          <div className="adm-modal-body">{children}</div>
        </div>
      </div>
    </>
  );
}

function ConfirmDialog({ open, title, description, confirmLabel, busy, onConfirm, onCancel }) {
  return (
    <Modal open={open} title={title} description={description} onClose={onCancel}>
      <div className="adm-form-actions">
        <Btn onClick={onCancel}>Cancel</Btn>
        <Btn variant="danger" icon={<IconTrash />} busy={busy} onClick={onConfirm}>{confirmLabel}</Btn>
      </div>
    </Modal>
  );
}

function usePolling(loader, intervalMs) {
  const savedLoader = useRef(loader);
  savedLoader.current = loader;
  useEffect(() => {
    let cancelled = false;
    const tick = () => { if (!cancelled) savedLoader.current({ silent: true }); };
    const timer = window.setInterval(tick, intervalMs);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [intervalMs]);
}

/* ── page: dashboard ───────────────────────────────────────── */

function DashboardPage({ setTopbar }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setBusy(true);
    try {
      setData(await apiJson("/api/admin/dashboard"));
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      if (!silent) setBusy(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  usePolling(load, 10_000);

  useEffect(() => {
    setTopbar({
      title: "Dashboard",
      sub: "Live provider health and account activity",
      actions: <Btn icon={<IconRefresh />} busy={busy} onClick={() => load()}>Refresh</Btn>,
    });
  }, [setTopbar, busy, load]);

  const checkMix = useMemo(() => {
    const checks = data?.recentProviderChecks || [];
    const total = Math.max(1, checks.length);
    return [
      { label: "Healthy", tone: "ok", value: checks.filter((item) => item.status === "HEALTHY").length, total },
      { label: "Degraded", tone: "warn", value: checks.filter((item) => item.status === "DEGRADED").length, total },
      { label: "Down", tone: "bad", value: checks.filter((item) => item.status === "DOWN").length, total },
      { label: "Disabled", tone: "muted", value: checks.filter((item) => item.status === "DISABLED").length, total },
    ];
  }, [data]);

  if (error && !data) return <Alert>{error}</Alert>;

  if (!data) {
    return (
      <div className="adm-stats">
        {[0, 1, 2, 3].map((key) => (
          <div key={key} className="adm-stat">
            <div className="adm-skeleton" style={{ width: "45%" }} />
            <div className="adm-skeleton" style={{ width: "30%", height: 24, marginTop: 10 }} />
          </div>
        ))}
      </div>
    );
  }

  const enabled = data.providers.filter((provider) => provider.isEnabled).length;

  return (
    <>
      <Alert>{error}</Alert>
      <div className="adm-stats">
        <Stat label="Total users" value={data.users.total} sub={`${data.users.active} active · ${data.users.disabled} disabled`} tone="info" />
        <Stat label="Online now" value={data.users.online} sub="Seen in the last 2 minutes" tone="ok" />
        <Stat label="Active sessions" value={data.users.activeSessions} sub="Refresh tokens with a recent heartbeat" />
        <Stat
          label="Providers enabled"
          value={`${enabled}/${data.providers.length}`}
          sub={enabled === data.providers.length ? "All sources reachable" : `${data.providers.length - enabled} turned off`}
          tone={enabled === data.providers.length ? "ok" : "warn"}
        />
      </div>

      <div className="adm-cols-2">
        <Card title="Provider status" desc="Latest health check per source">
          <div className="adm-list">
            {data.providers.map((provider) => {
              const status = provider.isEnabled ? (provider.latestHealth?.status || "UNKNOWN") : "DISABLED";
              return (
                <div key={provider.key} className="adm-list-item">
                  <div className="adm-cell-user">
                    <span className="adm-provider-logo">{provider.name.slice(0, 2).toUpperCase()}</span>
                    <div className="adm-cell-user-text">
                      <div className="adm-cell-name">{provider.name}</div>
                      <div className="adm-cell-meta">Checked {formatRelative(provider.lastCheckedAt)}</div>
                    </div>
                  </div>
                  <div className="adm-list-side">
                    <Badge tone={provider.isEnabled ? healthTone(provider.latestHealth?.status) : "muted"} dot>{status}</Badge>
                    <div style={{ marginTop: 4 }}>
                      {provider.latestHealth?.responseTimeMs ? `${provider.latestHealth.responseTimeMs} ms` : "—"}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Card>

        <Card title="Recent check outcomes" desc={`Last ${data.recentProviderChecks?.length || 0} health checks across all providers`}>
          <div>
            {checkMix.map((item) => (
              <div key={item.label} className="adm-bar-row">
                <span>{item.label}</span>
                <div className="adm-bar">
                  <div className={`adm-bar-fill tone-${item.tone}`} style={{ width: `${(item.value / item.total) * 100}%` }} />
                </div>
                <strong>{item.value}</strong>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <div className="adm-cols-2">
        <Card title="Recent sign-ins" desc="Accounts ordered by their most recent login">
          {data.recentLogins?.length ? (
            <div className="adm-list">
              {(data.recentLogins || []).slice(0, 6).map((user) => (
                <div key={user.id} className="adm-list-item">
                  <div className="adm-cell-user">
                    <Avatar name={user.displayName} size="sm" />
                    <div className="adm-cell-user-text">
                      <div className="adm-list-title">{user.displayName}</div>
                      <div className="adm-list-sub">{user.username}</div>
                    </div>
                  </div>
                  <div className="adm-list-side">
                    {isOnline(user.lastSeenAt) ? <Badge tone="ok" dot>Online</Badge> : formatRelative(user.lastLoginAt)}
                  </div>
                </div>
              ))}
            </div>
          ) : <Empty title="No sign-ins yet" description="User accounts will appear here once somebody signs in." />}
        </Card>

        <Card title="Latest playback" desc="Most recent watch events across all accounts">
          {data.recentWatching?.length ? (
            <div className="adm-list">
              {data.recentWatching.slice(0, 6).map((entry) => (
                <div key={entry.id} className="adm-list-item">
                  <div className="adm-list-main">
                    <div className="adm-list-title">{entry.title}</div>
                    <div className="adm-list-sub">
                      {entry.user?.displayName} · {entry.providerKey}{entry.episodeLabel ? ` · ${entry.episodeLabel}` : ""}
                    </div>
                  </div>
                  <div className="adm-list-side">{formatRelative(entry.watchedAt)}</div>
                </div>
              ))}
            </div>
          ) : <Empty title="Nothing watched yet" description="Playback events show up here as soon as a user starts a stream." />}
        </Card>
      </div>

      <div className="adm-cols-3">
        <Link to="/admin/users" className="adm-quick">
          <span className="adm-quick-icon"><IconUsers /></span>
          <span className="adm-quick-text"><strong>Manage users</strong><span>Accounts, access, activity</span></span>
          <span className="adm-quick-arrow"><IconChevron /></span>
        </Link>
        <Link to="/admin/providers" className="adm-quick">
          <span className="adm-quick-icon"><IconServer /></span>
          <span className="adm-quick-text"><strong>Providers</strong><span>Toggle sources, view health</span></span>
          <span className="adm-quick-arrow"><IconChevron /></span>
        </Link>
        <Link to="/admin/audit" className="adm-quick">
          <span className="adm-quick-icon"><IconShield /></span>
          <span className="adm-quick-text"><strong>Audit trail</strong><span>Administrative history</span></span>
          <span className="adm-quick-arrow"><IconChevron /></span>
        </Link>
      </div>
    </>
  );
}

/* ── page: providers ───────────────────────────────────────── */

function ProvidersPage({ setTopbar, toast }) {
  const [providers, setProviders] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [pendingKey, setPendingKey] = useState("");

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setBusy(true);
    try {
      const payload = await apiJson("/api/admin/providers");
      setProviders(payload.providers || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      if (!silent) setBusy(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);
  usePolling(load, 15_000);

  useEffect(() => {
    setTopbar({
      title: "Providers",
      sub: "Scraping sources and their health",
      actions: <Btn icon={<IconRefresh />} busy={busy} onClick={() => load()}>Refresh</Btn>,
    });
  }, [setTopbar, busy, load]);

  async function toggle(provider) {
    setPendingKey(provider.key);
    try {
      await apiJson(`/api/admin/providers/${provider.key}`, {
        method: "PATCH",
        body: JSON.stringify({ isEnabled: !provider.isEnabled }),
      });
      toast(`${provider.name} ${provider.isEnabled ? "disabled" : "enabled"}.`);
      await load({ silent: true });
    } catch (toggleError) {
      setError(toggleError.message);
    } finally {
      setPendingKey("");
    }
  }

  const latest = providers.map((provider) => provider.healthChecks?.[0]?.status).filter(Boolean);

  return (
    <>
      <Alert>{error}</Alert>
      <div className="adm-stats">
        <Stat label="Configured" value={providers.length} sub="Registered scraping sources" tone="info" />
        <Stat label="Healthy" value={latest.filter((status) => status === "HEALTHY").length} sub="Latest check succeeded" tone="ok" />
        <Stat label="Degraded" value={latest.filter((status) => status === "DEGRADED").length} sub="Responded slower than 8s" tone="warn" />
        <Stat label="Down" value={latest.filter((status) => status === "DOWN").length} sub="Latest check failed" tone="bad" />
      </div>

      <Card title="Sources" desc="Health is polled automatically every 30 seconds" flush>
        {providers.length ? providers.map((provider) => {
          const checks = (provider.healthChecks || []).slice(0, 10).reverse();
          const latestCheck = provider.healthChecks?.[0];
          const maxMs = Math.max(1, ...checks.map((check) => check.responseTimeMs || 0));
          return (
            <div key={provider.id} className="adm-provider-row">
              <div className="adm-provider-ident">
                <span className="adm-provider-logo">{provider.name.slice(0, 2).toUpperCase()}</span>
                <div className="adm-cell-user-text">
                  <div className="adm-provider-name">{provider.name}</div>
                  <div className="adm-provider-key">{provider.key}</div>
                </div>
              </div>

              <div>
                <Badge tone={provider.isEnabled ? healthTone(latestCheck?.status) : "muted"} dot>
                  {provider.isEnabled ? (latestCheck?.status || "UNKNOWN") : "DISABLED"}
                </Badge>
              </div>

              <div className="adm-cell-meta adm-tnum">
                {latestCheck?.responseTimeMs ? `${latestCheck.responseTimeMs} ms` : "—"}
                <div>{formatRelative(provider.lastCheckedAt)}</div>
              </div>

              <div>
                <div className="adm-spark" title="Last 10 health checks">
                  {checks.length ? checks.map((check) => (
                    <span
                      key={check.id}
                      className={`adm-spark-bar tone-${healthTone(check.status)}`}
                      style={{ height: `${Math.max(18, ((check.responseTimeMs || 0) / maxMs) * 100)}%` }}
                      title={`${check.status} · ${check.responseTimeMs ? `${check.responseTimeMs} ms` : check.errorMessage || "n/a"} · ${formatDate(check.checkedAt)}`}
                    />
                  )) : <span className="adm-cell-meta">No checks recorded</span>}
                </div>
              </div>

              <div className="adm-provider-toggle">
                <span className="adm-cell-meta adm-nowrap">{provider.isEnabled ? "Enabled" : "Disabled"}</span>
                <Switch
                  checked={provider.isEnabled}
                  disabled={pendingKey === provider.key}
                  onChange={() => toggle(provider)}
                  label={`Toggle ${provider.name}`}
                />
              </div>
            </div>
          );
        }) : <Empty title="No providers registered" description="Providers are seeded on server start-up from the provider registry." />}
      </Card>
    </>
  );
}

/* ── page: users ───────────────────────────────────────────── */

const EMPTY_CREATE = { username: "", email: "", displayName: "", password: "" };

function UserDrawer({ detail, onClose, onChanged, toast, setError }) {
  const [tab, setTab] = useState("overview");
  const [form, setForm] = useState({ username: "", email: "", displayName: "", status: "ACTIVE" });
  const [nextPassword, setNextPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [pendingProvider, setPendingProvider] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);

  const user = detail?.user;

  useEffect(() => {
    if (!user) return;
    setForm({ username: user.username, email: user.email, displayName: user.displayName, status: user.status });
    setNextPassword("");
  }, [user?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    function onKey(event) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!detail) return null;

  const isAdmin = user.role === "ADMIN";

  async function saveProfile(event) {
    event.preventDefault();
    setSaving(true);
    try {
      await apiJson(`/api/admin/users/${user.id}`, { method: "PATCH", body: JSON.stringify(form) });
      toast(`${form.displayName} updated.`);
      await onChanged(user.id);
    } catch (saveError) {
      setError(saveError.message);
    } finally {
      setSaving(false);
    }
  }

  async function resetPassword(event) {
    event.preventDefault();
    if (!nextPassword) return;
    setSaving(true);
    try {
      await apiJson(`/api/admin/users/${user.id}/password`, { method: "PATCH", body: JSON.stringify({ nextPassword }) });
      setNextPassword("");
      toast(`Password reset for ${user.username}.`);
    } catch (resetError) {
      setError(resetError.message);
    } finally {
      setSaving(false);
    }
  }

  async function toggleProvider(entry) {
    setPendingProvider(entry.providerKey);
    try {
      await apiJson(`/api/admin/users/${user.id}/providers/${entry.providerKey}`, {
        method: "PUT",
        body: JSON.stringify({ isEnabled: !entry.isEnabled }),
      });
      await onChanged(user.id);
    } catch (toggleError) {
      setError(toggleError.message);
    } finally {
      setPendingProvider("");
    }
  }

  async function deleteUser() {
    setSaving(true);
    try {
      await apiJson(`/api/admin/users/${user.id}`, { method: "DELETE" });
      toast(`${user.username} deleted.`);
      setConfirmDelete(false);
      onClose();
      await onChanged(null);
    } catch (deleteError) {
      setError(deleteError.message);
      setConfirmDelete(false);
    } finally {
      setSaving(false);
    }
  }

  const tabs = [
    { key: "overview", label: "Overview" },
    { key: "profile", label: "Profile" },
    { key: "access", label: "Access" },
    { key: "activity", label: "Activity" },
    { key: "security", label: "Security" },
  ];

  return (
    <>
      <div className="adm-scrim" onClick={onClose} />
      <aside className="adm-drawer" role="dialog" aria-modal="true" aria-label={`User ${user.username}`}>
        <header className="adm-drawer-head">
          <Avatar name={user.displayName} size="lg" />
          <div className="adm-drawer-ident">
            <div className="adm-drawer-name">{user.displayName}</div>
            <div className="adm-drawer-meta">{user.username} · {user.email}</div>
            <div className="adm-drawer-badges">
              <Badge tone={user.status === "ACTIVE" ? "ok" : "bad"} dot>{user.status}</Badge>
              <Badge tone={isAdmin ? "accent" : undefined}>{user.role}</Badge>
              {isOnline(user.lastSeenAt) ? <Badge tone="info" dot>Online</Badge> : null}
            </div>
          </div>
          <button type="button" className="adm-icon-btn" onClick={onClose} aria-label="Close"><IconClose /></button>
        </header>

        <nav className="adm-tabs">
          {tabs.map((entry) => (
            <button
              key={entry.key}
              type="button"
              className={`adm-tab${tab === entry.key ? " is-active" : ""}`}
              onClick={() => setTab(entry.key)}
            >
              {entry.label}
            </button>
          ))}
        </nav>

        <div className="adm-drawer-body">
          {tab === "overview" ? (
            <>
              <div className="adm-mini-stats">
                <div className="adm-mini-stat"><strong>{detail.favorites.length}</strong><span>Favorites</span></div>
                <div className="adm-mini-stat"><strong>{detail.progress.length}</strong><span>In progress</span></div>
                <div className="adm-mini-stat"><strong>{detail.history.length}</strong><span>Watch events</span></div>
                <div className="adm-mini-stat"><strong>{detail.sessions.length}</strong><span>Sessions</span></div>
              </div>
              <div className="adm-section">
                <div className="adm-section-title">Account</div>
                <div className="adm-kv">
                  <div className="adm-kv-row"><span>User ID</span><span className="adm-code">{user.id}</span></div>
                  <div className="adm-kv-row"><span>Role</span><span>{user.role}</span></div>
                  <div className="adm-kv-row"><span>Status</span><span>{user.status}</span></div>
                  <div className="adm-kv-row"><span>Created</span><span>{formatDate(user.createdAt)}</span></div>
                  <div className="adm-kv-row"><span>Last login</span><span>{formatDate(user.lastLoginAt)}</span></div>
                  <div className="adm-kv-row"><span>Last seen</span><span>{formatRelative(user.lastSeenAt)}</span></div>
                </div>
              </div>
            </>
          ) : null}

          {tab === "profile" ? (
            <form className="adm-form" onSubmit={saveProfile}>
              <Field label="Display name">
                <input className="adm-input" name="displayName" value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} />
              </Field>
              <div className="adm-form-row">
                <Field label="Username">
                  <input className="adm-input" name="username" value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} />
                </Field>
                <Field label="Email">
                  <input className="adm-input" name="email" type="email" value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} />
                </Field>
              </div>
              <Field label="Account status" hint="Disabled accounts cannot sign in and existing sessions stop working.">
                <select className="adm-select" name="status" value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}>
                  <option value="ACTIVE">Active</option>
                  <option value="DISABLED">Disabled</option>
                </select>
              </Field>
              <div className="adm-form-actions">
                <Btn variant="primary" type="submit" busy={saving}>Save changes</Btn>
              </div>
            </form>
          ) : null}

          {tab === "access" ? (
            <div className="adm-section">
              <div className="adm-section-title">Provider access</div>
              {isAdmin ? (
                <Alert tone="bad">Admin accounts cannot use playback endpoints, so provider access does not apply.</Alert>
              ) : null}
              <div className="adm-list">
                {(detail.providerAccess || []).length ? detail.providerAccess.map((entry) => (
                  <div key={entry.providerKey} className="adm-list-item">
                    <div className="adm-cell-user">
                      <span className="adm-provider-logo">{entry.providerName.slice(0, 2).toUpperCase()}</span>
                      <div className="adm-cell-user-text">
                        <div className="adm-list-title">{entry.providerName}</div>
                        <div className="adm-list-sub">
                          {entry.globalEnabled ? entry.providerKey : `${entry.providerKey} · disabled globally`}
                        </div>
                      </div>
                    </div>
                    <Switch
                      checked={entry.isEnabled && entry.globalEnabled}
                      disabled={!entry.globalEnabled || pendingProvider === entry.providerKey || isAdmin}
                      onChange={() => toggleProvider(entry)}
                      label={`Toggle ${entry.providerName} for ${user.username}`}
                    />
                  </div>
                )) : <Empty title="No provider records" description="This account has no per-provider permission rows, so every globally enabled provider is allowed." />}
              </div>
            </div>
          ) : null}

          {tab === "activity" ? (
            <>
              <div className="adm-section">
                <div className="adm-section-title">Continue watching ({detail.progress.length})</div>
                {detail.progress.length ? (
                  <div className="adm-list">
                    {detail.progress.slice(0, 8).map((entry) => (
                      <div key={entry.id} className="adm-list-item" style={{ display: "block" }}>
                        <div className="adm-list-title">{entry.title}</div>
                        <div className="adm-list-sub">
                          {entry.providerKey}{entry.episodeLabel ? ` · ${entry.episodeLabel}` : ""} · {formatDuration(entry.positionSeconds)} / {formatDuration(entry.durationSeconds)} · {formatRelative(entry.lastWatchedAt)}
                        </div>
                        <div className="adm-progress">
                          <div
                            className={`adm-progress-fill${entry.isCompleted ? " is-done" : ""}`}
                            style={{ width: `${Math.min(100, entry.progressPercent || 0)}%` }}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : <Empty title="No playback progress" description="Resume checkpoints appear once this user starts watching something." />}
              </div>

              <div className="adm-section">
                <div className="adm-section-title">Favorites ({detail.favorites.length})</div>
                {detail.favorites.length ? (
                  <div className="adm-list">
                    {detail.favorites.slice(0, 8).map((entry) => (
                      <div key={entry.id} className="adm-list-item">
                        <div className="adm-list-main">
                          <div className="adm-list-title">{entry.title}</div>
                          <div className="adm-list-sub">{entry.providerKey} · {entry.mediaType}</div>
                        </div>
                        <div className="adm-list-side">{formatRelative(entry.createdAt)}</div>
                      </div>
                    ))}
                  </div>
                ) : <Empty title="No favorites saved" description="Titles this user stars will be listed here." />}
              </div>

              <div className="adm-section">
                <div className="adm-section-title">Recent history ({detail.history.length})</div>
                {detail.history.length ? (
                  <div className="adm-list">
                    {detail.history.slice(0, 10).map((entry) => (
                      <div key={entry.id} className="adm-list-item">
                        <div className="adm-list-main">
                          <div className="adm-list-title">{entry.title}</div>
                          <div className="adm-list-sub">
                            {entry.providerKey}{entry.episodeLabel ? ` · ${entry.episodeLabel}` : ""}{entry.sourceLabel ? ` · ${entry.sourceLabel}` : ""}
                          </div>
                        </div>
                        <div className="adm-list-side">{formatRelative(entry.watchedAt)}</div>
                      </div>
                    ))}
                  </div>
                ) : <Empty title="No watch history" description="Playback events are recorded on pause, switch, and completion." />}
              </div>
            </>
          ) : null}

          {tab === "security" ? (
            <>
              <div className="adm-section">
                <div className="adm-section-title">Reset password</div>
                {isAdmin ? (
                  <Alert tone="bad">Admin passwords must be changed from the Account page by the admin themselves.</Alert>
                ) : (
                  <form className="adm-form" onSubmit={resetPassword}>
                    <Field label="New password" hint="Minimum 6 characters. The user is not notified automatically.">
                      <input className="adm-input" name="nextPassword" type="password" autoComplete="new-password" value={nextPassword} onChange={(event) => setNextPassword(event.target.value)} placeholder="••••••••" />
                    </Field>
                    <div className="adm-form-actions">
                      <Btn type="submit" icon={<IconKey />} busy={saving} disabled={!nextPassword}>Reset password</Btn>
                    </div>
                  </form>
                )}
              </div>

              <div className="adm-section">
                <div className="adm-section-title">Sessions ({detail.sessions.length})</div>
                {detail.sessions.length ? (
                  <div className="adm-list">
                    {detail.sessions.slice(0, 6).map((session) => (
                      <div key={session.id} className="adm-list-item">
                        <div className="adm-list-main">
                          <div className="adm-list-title">{session.ip || "unknown IP"}</div>
                          <div className="adm-list-sub">{session.userAgent || "unknown client"}</div>
                        </div>
                        <div className="adm-list-side">
                          {formatRelative(session.lastSeenAt || session.createdAt)}
                          <div>expires {formatRelative(session.expiresAt)}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : <Empty title="No sessions" description="Sessions are created when the user signs in." />}
              </div>

              {!isAdmin ? (
                <div className="adm-danger-zone">
                  <div>
                    <strong>Delete this account</strong>
                    <p>Removes the user together with every session, favorite, progress row, and history entry.</p>
                  </div>
                  <Btn variant="danger" icon={<IconTrash />} onClick={() => setConfirmDelete(true)}>Delete</Btn>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      </aside>

      <ConfirmDialog
        open={confirmDelete}
        title={`Delete ${user.username}?`}
        description="This permanently removes the account along with every session, favorite, progress row, and history entry. It cannot be undone."
        confirmLabel="Delete account"
        busy={saving}
        onConfirm={deleteUser}
        onCancel={() => setConfirmDelete(false)}
      />
    </>
  );
}

function UsersPage({ setTopbar, toast }) {
  const [users, setUsers] = useState([]);
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState(EMPTY_CREATE);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setBusy(true);
    try {
      const payload = await apiJson("/api/admin/users");
      setUsers(payload.users || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      if (!silent) setBusy(false);
    }
  }, []);

  const openUser = useCallback(async (userId) => {
    try {
      setDetail(await apiJson(`/api/admin/users/${userId}`));
    } catch (openError) {
      setError(openError.message);
    }
  }, []);

  const refreshAfterChange = useCallback(async (userId) => {
    await load({ silent: true });
    if (userId) await openUser(userId);
    else setDetail(null);
  }, [load, openUser]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    setTopbar({
      title: "Users",
      sub: "Accounts, provider access, and activity",
      actions: (
        <>
          <Btn icon={<IconRefresh />} busy={busy} onClick={() => load()}>Refresh</Btn>
          <Btn variant="primary" icon={<IconPlus />} onClick={() => setCreateOpen(true)}>New user</Btn>
        </>
      ),
    });
  }, [setTopbar, busy, load]);

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return users.filter((user) => {
      if (statusFilter === "active" && user.status !== "ACTIVE") return false;
      if (statusFilter === "disabled" && user.status !== "DISABLED") return false;
      if (statusFilter === "online" && !isOnline(user.lastSeenAt)) return false;
      if (statusFilter === "admin" && user.role !== "ADMIN") return false;
      if (!keyword) return true;
      return [user.username, user.email, user.displayName].some((value) => value.toLowerCase().includes(keyword));
    });
  }, [users, query, statusFilter]);

  async function handleCreate(event) {
    event.preventDefault();
    setCreating(true);
    try {
      const payload = await apiJson("/api/admin/users", { method: "POST", body: JSON.stringify(createForm) });
      setCreateForm(EMPTY_CREATE);
      setCreateOpen(false);
      toast(`User ${payload.user.username} created.`);
      await load({ silent: true });
      await openUser(payload.user.id);
    } catch (createError) {
      setError(createError.message);
    } finally {
      setCreating(false);
    }
  }

  const onlineCount = users.filter((user) => isOnline(user.lastSeenAt)).length;

  return (
    <>
      <Alert>{error}</Alert>

      <div className="adm-stats">
        <Stat label="Accounts" value={users.length} sub={`${users.filter((user) => user.role === "ADMIN").length} admin · ${users.filter((user) => user.role === "USER").length} standard`} tone="info" />
        <Stat label="Active" value={users.filter((user) => user.status === "ACTIVE").length} sub="Able to sign in" tone="ok" />
        <Stat label="Disabled" value={users.filter((user) => user.status === "DISABLED").length} sub="Sign-in blocked" tone="bad" />
        <Stat label="Online" value={onlineCount} sub="Heartbeat in the last 2 minutes" tone="warn" />
      </div>

      <div className="adm-toolbar">
        <SearchInput value={query} onChange={setQuery} placeholder="Search by name, username, or email" />
        <select className="adm-select" name="statusFilter" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
          <option value="all">All accounts</option>
          <option value="active">Active only</option>
          <option value="disabled">Disabled only</option>
          <option value="online">Online now</option>
          <option value="admin">Admins</option>
        </select>
      </div>

      <Card
        title={`${filtered.length} ${filtered.length === 1 ? "account" : "accounts"}`}
        desc={query || statusFilter !== "all" ? `Filtered from ${users.length} total` : "Select a row to inspect and manage the account"}
        flush
      >
        {filtered.length ? (
          <div className="adm-table-wrap">
            <table className="adm-table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Role</th>
                  <th>Status</th>
                  <th>Provider access</th>
                  <th>Last seen</th>
                  <th className="adm-num">Joined</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((user) => {
                  const access = user.providerAccess || [];
                  const allowed = access.filter((entry) => entry.isEnabled).length;
                  return (
                    <tr
                      key={user.id}
                      className={detail?.user?.id === user.id ? "is-selected" : ""}
                      tabIndex={0}
                      role="button"
                      aria-label={`Open ${user.displayName}`}
                      onClick={() => openUser(user.id)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          openUser(user.id);
                        }
                      }}
                    >
                      <td>
                        <div className="adm-cell-user">
                          <Avatar name={user.displayName} />
                          <div className="adm-cell-user-text">
                            <div className="adm-cell-name">{user.displayName}</div>
                            <div className="adm-cell-meta">{user.username} · {user.email}</div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <Badge tone={user.role === "ADMIN" ? "accent" : undefined}>{user.role}</Badge>
                      </td>
                      <td>
                        <div className="adm-chip-row">
                          <Badge tone={user.status === "ACTIVE" ? "ok" : "bad"} dot>{user.status}</Badge>
                          {isOnline(user.lastSeenAt) ? <Badge tone="info" dot>Online</Badge> : null}
                        </div>
                      </td>
                      <td className="adm-cell-meta adm-nowrap">
                        {user.role === "ADMIN"
                          ? "—"
                          : access.length
                            ? `${allowed} of ${access.length} providers`
                            : "All providers"}
                      </td>
                      <td className="adm-cell-meta adm-nowrap">{formatRelative(user.lastSeenAt)}</td>
                      <td className="adm-cell-meta adm-nowrap adm-num">{formatRelative(user.createdAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title={users.length ? "No accounts match this filter" : "No accounts yet"}
            description={users.length ? "Try a different search term or clear the status filter." : "Create the first standard user with the New user button."}
          />
        )}
      </Card>

      <Modal
        open={createOpen}
        title="Create user"
        description="New accounts start active with access to every enabled provider."
        onClose={() => setCreateOpen(false)}
      >
        <form className="adm-form" onSubmit={handleCreate}>
          <Field label="Display name">
            <input className="adm-input" name="newDisplayName" value={createForm.displayName} onChange={(event) => setCreateForm((current) => ({ ...current, displayName: event.target.value }))} placeholder="Alice Chen" />
          </Field>
          <div className="adm-form-row">
            <Field label="Username">
              <input className="adm-input" name="newUsername" value={createForm.username} onChange={(event) => setCreateForm((current) => ({ ...current, username: event.target.value }))} placeholder="alice" />
            </Field>
            <Field label="Email">
              <input className="adm-input" name="newEmail" type="email" value={createForm.email} onChange={(event) => setCreateForm((current) => ({ ...current, email: event.target.value }))} placeholder="alice@example.com" />
            </Field>
          </div>
          <Field label="Temporary password" hint="At least 6 characters. Share it with the user out of band.">
            <input className="adm-input" name="newPassword" type="password" autoComplete="new-password" value={createForm.password} onChange={(event) => setCreateForm((current) => ({ ...current, password: event.target.value }))} placeholder="••••••••" />
          </Field>
          <div className="adm-form-actions">
            <Btn onClick={() => setCreateOpen(false)}>Cancel</Btn>
            <Btn variant="primary" type="submit" busy={creating}>Create user</Btn>
          </div>
        </form>
      </Modal>

      {detail ? (
        <UserDrawer
          detail={detail}
          onClose={() => setDetail(null)}
          onChanged={refreshAfterChange}
          toast={toast}
          setError={setError}
        />
      ) : null}
    </>
  );
}

/* ── page: audit ───────────────────────────────────────────── */

const AUDIT_LABELS = {
  "user.create": { verb: "created user", tone: "ok" },
  "user.update": { verb: "updated user", tone: "warn" },
  "user.delete": { verb: "deleted user", tone: "bad" },
  "user.password.reset": { verb: "reset the password for", tone: "warn" },
  "user.provider.toggle": { verb: "changed provider access for", tone: "warn" },
  "provider.toggle": { verb: "toggled a provider", tone: "warn" },
};

function AuditPage({ setTopbar }) {
  const [logs, setLogs] = useState([]);
  const [userMap, setUserMap] = useState({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState("");
  const [actionFilter, setActionFilter] = useState("all");

  const load = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setBusy(true);
    try {
      // Audit rows only carry a target user id; pull the roster so they can be
      // rendered as names instead of opaque cuids.
      const [payload, usersPayload] = await Promise.all([
        apiJson("/api/admin/audit-logs"),
        apiJson("/api/admin/users").catch(() => ({ users: [] })),
      ]);
      setLogs(payload.logs || []);
      setUserMap(Object.fromEntries((usersPayload.users || []).map((user) => [user.id, user])));
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      if (!silent) setBusy(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    setTopbar({
      title: "Audit trail",
      sub: "Administrative changes, newest first",
      actions: <Btn icon={<IconRefresh />} busy={busy} onClick={() => load()}>Refresh</Btn>,
    });
  }, [setTopbar, busy, load]);

  const actions = useMemo(() => ["all", ...Array.from(new Set(logs.map((log) => log.action))).sort()], [logs]);

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return logs.filter((log) => {
      if (actionFilter !== "all" && log.action !== actionFilter) return false;
      if (!keyword) return true;
      const haystack = [log.action, log.actorUser?.displayName, log.actorUser?.username, log.targetUserId, JSON.stringify(log.payload || {})]
        .filter(Boolean).join(" ").toLowerCase();
      return haystack.includes(keyword);
    });
  }, [logs, actionFilter, query]);

  return (
    <>
      <Alert>{error}</Alert>

      <div className="adm-toolbar">
        <SearchInput value={query} onChange={setQuery} placeholder="Search actor, action, target, or payload" />
        <select className="adm-select" name="actionFilter" value={actionFilter} onChange={(event) => setActionFilter(event.target.value)}>
          {actions.map((action) => (
            <option key={action} value={action}>{action === "all" ? "All actions" : action}</option>
          ))}
        </select>
      </div>

      <Card
        title={`${filtered.length} ${filtered.length === 1 ? "entry" : "entries"}`}
        desc={logs.length ? `Showing the most recent ${logs.length} recorded actions` : undefined}
        flush
      >
        {filtered.length ? filtered.map((log) => {
          const meta = AUDIT_LABELS[log.action] || { verb: log.action, tone: undefined };
          const payload = log.payload || {};
          const target = log.targetUserId ? userMap[log.targetUserId] : null;
          // Deleted accounts are gone from the roster, but their payload kept the username.
          const targetLabel = target?.displayName || payload.username || null;
          const payloadEntries = Object.entries(payload);
          return (
            <div key={log.id} className="adm-audit-row">
              <span className={`adm-audit-icon${meta.tone ? ` tone-${meta.tone}` : ""}`}>
                {meta.tone === "bad" ? <IconTrash /> : meta.tone === "ok" ? <IconPlus /> : <IconKey />}
              </span>
              <div className="adm-audit-main">
                <div className="adm-audit-line">
                  <strong>{log.actorUser?.displayName || log.actorUser?.username || "Unknown"}</strong>
                  {" "}{meta.verb}{" "}
                  {targetLabel
                    ? <strong>{targetLabel}</strong>
                    : log.targetUserId
                      ? <span className="adm-code" title={log.targetUserId}>{log.targetUserId.slice(-8)}</span>
                      : <span className="adm-muted">system-wide</span>}
                </div>
                {payloadEntries.length ? (
                  <div className="adm-audit-payload">
                    {payloadEntries.map(([key, value]) => (
                      <span key={key} className="adm-code">{key}: {typeof value === "object" ? JSON.stringify(value) : String(value)}</span>
                    ))}
                  </div>
                ) : null}
              </div>
              <div className="adm-audit-time" title={formatDate(log.createdAt)}>{formatRelative(log.createdAt)}</div>
            </div>
          );
        }) : (
          <Empty
            title={logs.length ? "No entries match this filter" : "No administrative activity yet"}
            description={logs.length ? "Try a different keyword or select another action type." : "Creating users, toggling providers, and resetting passwords are all recorded here."}
          />
        )}
      </Card>
    </>
  );
}

/* ── page: account ─────────────────────────────────────────── */

function AccountPage({ session, setSession, setTopbar, toast }) {
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

  useEffect(() => {
    setTopbar({ title: "Account", sub: "Your administrator identity", actions: null });
  }, [setTopbar]);

  async function saveProfile(event) {
    event.preventDefault();
    setSavingProfile(true);
    setError("");
    try {
      const payload = await apiJson("/api/auth/me/profile", { method: "PATCH", body: JSON.stringify(form) });
      const nextSession = { ...session, user: payload.user };
      setStoredSession(nextSession);
      setSession(nextSession);
      toast("Account updated.");
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
      <div className="adm-cols-2">
        <Card title="Profile" desc="How your account appears across the console">
          <form className="adm-form" onSubmit={saveProfile}>
            <Field label="Display name">
              <input className="adm-input" name="displayName" value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} />
            </Field>
            <Field label="Username">
              <input className="adm-input" name="username" value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} />
            </Field>
            <Field label="Email">
              <input className="adm-input" name="email" type="email" value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} />
            </Field>
            <div className="adm-form-actions">
              <Btn variant="primary" type="submit" busy={savingProfile}>Save profile</Btn>
            </div>
          </form>
        </Card>

        <Card title="Password" desc="Rotate the local administrator password">
          <form className="adm-form" onSubmit={savePassword}>
            <Field label="Current password">
              <input className="adm-input" name="currentPassword" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} placeholder="••••••••" />
            </Field>
            <Field label="New password" hint="At least 6 characters.">
              <input className="adm-input" name="nextPassword" type="password" autoComplete="new-password" value={nextPassword} onChange={(event) => setNextPassword(event.target.value)} placeholder="••••••••" />
            </Field>
            <div className="adm-form-actions">
              <Btn type="submit" icon={<IconKey />} busy={savingPassword} disabled={!currentPassword || !nextPassword}>Update password</Btn>
            </div>
          </form>
        </Card>
      </div>
    </>
  );
}

/* ── shell ─────────────────────────────────────────────────── */

const NAV = [
  { to: "/admin", label: "Dashboard", icon: <IconGrid />, end: true },
  { to: "/admin/providers", label: "Providers", icon: <IconServer /> },
  { to: "/admin/users", label: "Users", icon: <IconUsers /> },
  { to: "/admin/audit", label: "Audit", icon: <IconShield /> },
  { to: "/admin/account", label: "Account", icon: <IconUser /> },
];

export default function AdminPortal({ session, setSession, onLogout }) {
  const location = useLocation();
  const [topbar, setTopbar] = useState({ title: "", sub: "", actions: null });
  const [toastState, setToastState] = useState(null);

  const toast = useCallback((message, tone = "ok") => setToastState({ message, tone }), []);
  const dismissToast = useCallback(() => setToastState(null), []);

  return (
    <div className="adm">
      <div className="adm-shell">
        <aside className="adm-side">
          <div className="adm-brand">
            <span className="adm-brand-dot">S</span>
            <div>
              <div className="adm-brand-name">StreamHub</div>
              <div className="adm-brand-sub">Admin</div>
            </div>
          </div>

          <div className="adm-nav-label">Console</div>
          <nav className="adm-nav">
            {NAV.map((item) => {
              const active = item.end ? location.pathname === item.to : location.pathname.startsWith(item.to);
              return (
                <Link key={item.to} to={item.to} className={`adm-nav-item${active ? " is-active" : ""}`}>
                  {item.icon}
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="adm-side-foot">
            <div className="adm-side-user">
              <Avatar name={session.user.displayName} size="sm" />
              <div className="adm-side-user-text">
                <div className="adm-side-user-name">{session.user.displayName}</div>
                <div className="adm-side-user-role">{session.user.username}</div>
              </div>
            </div>
            <Btn icon={<IconLogout />} onClick={onLogout}>Sign out</Btn>
          </div>
        </aside>

        <main className="adm-main">
          <header className="adm-topbar">
            <div>
              <div className="adm-topbar-title">{topbar.title}</div>
              {topbar.sub ? <div className="adm-topbar-sub">{topbar.sub}</div> : null}
            </div>
            <div className="adm-topbar-actions">{topbar.actions}</div>
          </header>

          <div className="adm-body">
            <Routes>
              <Route index element={<DashboardPage setTopbar={setTopbar} />} />
              <Route path="providers" element={<ProvidersPage setTopbar={setTopbar} toast={toast} />} />
              <Route path="users" element={<UsersPage setTopbar={setTopbar} toast={toast} />} />
              <Route path="audit" element={<AuditPage setTopbar={setTopbar} />} />
              <Route path="account" element={<AccountPage session={session} setSession={setSession} setTopbar={setTopbar} toast={toast} />} />
            </Routes>
          </div>
        </main>
      </div>

      <Toast message={toastState?.message} tone={toastState?.tone} onDismiss={dismissToast} />
    </div>
  );
}
