import { useEffect, useMemo, useState } from "react";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import { apiJson, setStoredSession } from "./api.js";

function formatDate(value) {
  if (!value) return "Never";
  return new Date(value).toLocaleString();
}

function formatDuration(seconds) {
  const safe = Math.max(0, Math.floor(Number(seconds) || 0));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const secs = safe % 60;
  if (hours > 0) return `${hours}h ${minutes}m ${secs}s`;
  return `${minutes}m ${secs}s`;
}

function getProviderHealthTone(status) {
  if (status === "HEALTHY") return "healthy";
  if (status === "DEGRADED") return "warning";
  if (status === "DOWN") return "danger";
  return "muted";
}

function getProviderAccessTone(enabled, globalEnabled) {
  if (!globalEnabled) return "danger";
  return enabled ? "healthy" : "warning";
}

function getUserStatusTone(status) {
  return status === "ACTIVE" ? "healthy" : "danger";
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
            <Link key={link.to} to={link.to} className={location.pathname === link.to ? "active" : ""}>
              {link.label}
            </Link>
          ))}
        </nav>
        <button type="button" className="portal-logout" onClick={onLogout}>Logout</button>
      </aside>
      <div className="portal-content">{children}</div>
    </div>
  );
}

function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="page-header">
      <div>
        {eyebrow ? <div className="page-eyebrow">{eyebrow}</div> : null}
        <h1 className="page-title">{title}</h1>
        {description ? <p className="page-description">{description}</p> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  );
}

function SummaryCard({ label, value, subvalue, tone = "default" }) {
  return (
    <div className={`summary-card tone-${tone}`}>
      <div className="summary-label">{label}</div>
      <div className="summary-value">{value}</div>
      {subvalue ? <div className="summary-subvalue">{subvalue}</div> : null}
    </div>
  );
}

function StatusPill({ label, tone = "default" }) {
  return <span className={`status-pill tone-${tone}`}>{label}</span>;
}

function EmptyState({ title, description }) {
  return (
    <div className="empty-state">
      <strong>{title}</strong>
      <span>{description}</span>
    </div>
  );
}

function PasswordCard({ title, endpoint, bodyBuilder }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [nextPassword, setNextPassword] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSubmitting(true);
    setMessage("");
    setError("");
    try {
      await apiJson(endpoint, {
        method: "PATCH",
        body: JSON.stringify(bodyBuilder({ currentPassword, nextPassword })),
      });
      setCurrentPassword("");
      setNextPassword("");
      setMessage("Password updated.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  const expectsCurrent = bodyBuilder({ currentPassword: "x", nextPassword: "y" }).currentPassword !== undefined;

  return (
    <div className="panel-card">
      <h2>{title}</h2>
      <form className="stack-form" onSubmit={handleSubmit}>
        {expectsCurrent ? (
          <input type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} placeholder="Current password" />
        ) : null}
        <input type="password" value={nextPassword} onChange={(event) => setNextPassword(event.target.value)} placeholder="New password" />
        <button type="submit" disabled={submitting}>{submitting ? "Saving..." : "Update Password"}</button>
      </form>
      {message ? <div className="success-banner">{message}</div> : null}
      {error ? <div className="auth-error">{error}</div> : null}
    </div>
  );
}

function DashboardPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);

  async function loadDashboard({ silent = false } = {}) {
    if (!silent) setRefreshing(true);
    try {
      const payload = await apiJson("/api/admin/dashboard");
      setData(payload);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      if (!silent) setRefreshing(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    loadDashboard().catch(() => {});
    const timer = window.setInterval(async () => {
      if (cancelled) return;
      try {
        const payload = await apiJson("/api/admin/dashboard");
        if (!cancelled) {
          setData(payload);
          setError("");
        }
      } catch (loadError) {
        if (!cancelled) setError(loadError.message);
      }
    }, 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const activeProviders = data?.providers?.filter((provider) => provider.isEnabled).length || 0;
  const providerHealthCounts = useMemo(() => {
    if (!data?.providers?.length) return { healthy: 0, degraded: 0, down: 0, disabled: 0 };
    return data.providers.reduce((accumulator, provider) => {
      if (!provider.isEnabled) {
        accumulator.disabled += 1;
        return accumulator;
      }
      const status = provider.latestHealth?.status || "UNKNOWN";
      if (status === "HEALTHY") accumulator.healthy += 1;
      else if (status === "DEGRADED") accumulator.degraded += 1;
      else accumulator.down += 1;
      return accumulator;
    }, { healthy: 0, degraded: 0, down: 0, disabled: 0 });
  }, [data]);
  const providerTimeline = useMemo(() => {
    const checks = data?.recentProviderChecks || [];
    const total = checks.length || 1;
    return [
      { label: "Healthy", value: checks.filter((item) => item.status === "HEALTHY").length, tone: "healthy", total },
      { label: "Degraded", value: checks.filter((item) => item.status === "DEGRADED").length, tone: "warning", total },
      { label: "Down", value: checks.filter((item) => item.status === "DOWN").length, tone: "danger", total },
      { label: "Disabled", value: checks.filter((item) => item.status === "DISABLED").length, tone: "muted", total },
    ];
  }, [data]);
  const userMix = useMemo(() => {
    const active = data?.users?.active || 0;
    const disabled = data?.users?.disabled || 0;
    const online = data?.users?.online || 0;
    const total = Math.max(1, active + disabled);
    return { active, disabled, online, activeWidth: `${(active / total) * 100}%`, disabledWidth: `${(disabled / total) * 100}%` };
  }, [data]);

  return (
    <div className="panel-grid">
      <PageHeader eyebrow="Admin Dashboard" title="System Overview" description="Monitor provider health, user activity, and the latest playback behavior." actions={<button type="button" className="secondary-button" onClick={() => loadDashboard()}>{refreshing ? "Refreshing..." : "Refresh"}</button>} />
      {error ? <div className="auth-error">{error}</div> : null}
      {!data ? <div className="panel-card">Loading dashboard...</div> : (
        <>
          <div className="summary-grid">
            <SummaryCard label="Users" value={data.users.total} subvalue={`${data.users.online} online now`} />
            <SummaryCard label="Active Sessions" value={data.users.activeSessions} subvalue="Heartbeat-based session activity" tone="healthy" />
            <SummaryCard label="Providers Enabled" value={activeProviders} subvalue={`${data.providers.length - activeProviders} disabled`} tone="warning" />
            <SummaryCard label="Disabled Users" value={data.users.disabled} subvalue={`${data.users.active} active accounts`} tone="danger" />
          </div>
          <div className="admin-overview-grid">
            <div className="panel-card">
              <h2>Provider Health Mix</h2>
              <div className="chart-stack">
                {providerTimeline.map((item) => (
                  <div key={item.label} className="chart-row">
                    <div className="chart-label"><span>{item.label}</span><strong>{item.value}</strong></div>
                    <div className="chart-bar"><div className={`chart-bar-fill tone-${item.tone}`} style={{ width: `${(item.value / item.total) * 100}%` }} /></div>
                  </div>
                ))}
              </div>
              <div className="metric-inline-grid">
                <div className="metric-box"><span>Healthy</span><strong>{providerHealthCounts.healthy}</strong></div>
                <div className="metric-box"><span>Degraded</span><strong>{providerHealthCounts.degraded}</strong></div>
                <div className="metric-box"><span>Down</span><strong>{providerHealthCounts.down}</strong></div>
                <div className="metric-box"><span>Disabled</span><strong>{providerHealthCounts.disabled}</strong></div>
              </div>
            </div>
            <div className="panel-card">
              <h2>User Availability</h2>
              <div className="availability-meter">
                <div className="availability-track">
                  <div className="availability-fill tone-healthy" style={{ width: userMix.activeWidth }} />
                  <div className="availability-fill tone-danger" style={{ width: userMix.disabledWidth }} />
                </div>
                <div className="availability-legend"><span>Active {userMix.active}</span><span>Disabled {userMix.disabled}</span><span>Online {userMix.online}</span></div>
              </div>
              <div className="quick-links-grid">
                <Link to="/admin/users" className="quick-link-card"><strong>User Management</strong><span>Inspect accounts, sessions, provider access, and playback history.</span></Link>
                <Link to="/admin/providers" className="quick-link-card"><strong>Provider Control</strong><span>Enable or disable scraping sources and inspect recent health checks.</span></Link>
                <Link to="/admin/audit" className="quick-link-card"><strong>Audit Trail</strong><span>Review administrative changes and sensitive account operations.</span></Link>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function AuditLogsPage() {
  const [logs, setLogs] = useState([]);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [actionFilter, setActionFilter] = useState("all");
  const [query, setQuery] = useState("");

  async function loadLogs() {
    setRefreshing(true);
    try {
      const payload = await apiJson("/api/admin/audit-logs");
      setLogs(payload.logs || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setRefreshing(false);
    }
  }

  useEffect(() => { loadLogs().catch(() => {}); }, []);

  const actionOptions = useMemo(() => ["all", ...Array.from(new Set(logs.map((log) => log.action))).sort()], [logs]);
  const filteredLogs = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return logs.filter((log) => {
      if (actionFilter !== "all" && log.action !== actionFilter) return false;
      if (!keyword) return true;
      const haystack = [log.action, log.actorUser?.displayName, log.actorUser?.username, log.actorUser?.email, log.targetUserId, log.payload ? JSON.stringify(log.payload) : ""].filter(Boolean).join(" ").toLowerCase();
      return haystack.includes(keyword);
    });
  }, [logs, actionFilter, query]);

  return (
    <div className="panel-grid">
      <PageHeader eyebrow="Audit Trail" title="Administrative Activity" description="Review account changes, provider toggles, password resets, and destructive actions performed in the admin panel." actions={<button type="button" className="secondary-button" onClick={() => loadLogs()}>{refreshing ? "Refreshing..." : "Refresh"}</button>} />
      {error ? <div className="auth-error">{error}</div> : null}
      <div className="audit-toolbar">
        <input className="table-filter" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search actor, action, target, or payload" />
        <select className="table-filter audit-select" value={actionFilter} onChange={(event) => setActionFilter(event.target.value)}>
          {actionOptions.map((option) => <option key={option} value={option}>{option === "all" ? "All actions" : option}</option>)}
        </select>
      </div>
      <div className="panel-card">
        <div className="table-list">
          {filteredLogs.length ? filteredLogs.map((log) => (
            <div key={log.id} className="table-row">
              <div>
                <strong>{log.action}</strong>
                <span>{log.actorUser?.displayName || log.actorUser?.username} · target {log.targetUserId || "system"}</span>
              </div>
              <div>
                <span>{formatDate(log.createdAt)}</span>
                <span>{log.payload ? JSON.stringify(log.payload) : "{}"}</span>
              </div>
            </div>
          )) : <EmptyState title="No matching audit entries" description="Adjust the filters or wait for administrative actions to be recorded." />}
        </div>
      </div>
    </div>
  );
}

function ProvidersPage() {
  const [providers, setProviders] = useState([]);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  async function loadProviders() {
    setRefreshing(true);
    try {
      const payload = await apiJson("/api/admin/providers");
      setProviders(payload.providers || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setRefreshing(false);
    }
  }
  useEffect(() => { loadProviders().catch(() => {}); }, []);
  async function toggleProvider(provider) {
    await apiJson(`/api/admin/providers/${provider.key}`, { method: "PATCH", body: JSON.stringify({ isEnabled: !provider.isEnabled }) });
    await loadProviders();
  }
  const latestStatuses = providers.map((provider) => provider.healthChecks?.[0]?.status).filter(Boolean);
  const healthyCount = latestStatuses.filter((status) => status === "HEALTHY").length;
  const degradedCount = latestStatuses.filter((status) => status === "DEGRADED").length;
  const downCount = latestStatuses.filter((status) => status === "DOWN").length;
  return (
    <div className="panel-grid">
      <PageHeader eyebrow="Provider Control" title="Source Management" description="Enable or disable global providers and inspect recent health checks for each source." actions={<button type="button" className="secondary-button" onClick={() => loadProviders()}>{refreshing ? "Refreshing..." : "Refresh"}</button>} />
      {error ? <div className="auth-error">{error}</div> : null}
      <div className="summary-grid">
        <SummaryCard label="Providers" value={providers.length} subvalue="Configured scraping sources" />
        <SummaryCard label="Healthy" value={healthyCount} subvalue="Latest checks reported healthy" tone="healthy" />
        <SummaryCard label="Degraded" value={degradedCount} subvalue="High latency or partial errors" tone="warning" />
        <SummaryCard label="Down" value={downCount} subvalue="Most recent checks failed" tone="danger" />
      </div>
      <div className="provider-admin-grid">
        {providers.map((provider) => {
          const latest = provider.healthChecks?.[0];
          return (
            <div key={provider.id} className="panel-card provider-admin-card">
              <div className="provider-admin-head">
                <div><h2>{provider.name}</h2><div className="provider-meta">{provider.key}</div></div>
                <StatusPill label={provider.isEnabled ? (latest?.status || "UNKNOWN") : "DISABLED"} tone={provider.isEnabled ? getProviderHealthTone(latest?.status) : "danger"} />
              </div>
              <div className="provider-stats-row">
                <div className="metric-box"><span>Global Access</span><strong>{provider.isEnabled ? "Enabled" : "Disabled"}</strong></div>
                <div className="metric-box"><span>Last Checked</span><strong>{formatDate(provider.lastCheckedAt)}</strong></div>
                <div className="metric-box"><span>Latest Latency</span><strong>{latest?.responseTimeMs ? `${latest.responseTimeMs} ms` : "n/a"}</strong></div>
              </div>
              <div className="provider-history-list">
                {(provider.healthChecks || []).slice(0, 5).map((check) => (
                  <div key={check.id} className="provider-history-item">
                    <div><StatusPill label={check.status} tone={getProviderHealthTone(check.status)} /></div>
                    <div className="provider-history-meta"><span>{formatDate(check.checkedAt)}</span><span>{check.responseTimeMs ? `${check.responseTimeMs} ms` : check.errorMessage || "n/a"}</span></div>
                  </div>
                ))}
              </div>
              <div className="provider-card-actions"><button type="button" onClick={() => toggleProvider(provider)}>{provider.isEnabled ? "Disable Provider" : "Enable Provider"}</button></div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function UserManagementPage() {
  const emptyCreateForm = { username: "", email: "", displayName: "", password: "" };
  const emptyEditForm = { username: "", email: "", displayName: "", status: "ACTIVE" };
  const [users, setUsers] = useState([]);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [createForm, setCreateForm] = useState(emptyCreateForm);
  const [editForm, setEditForm] = useState(emptyEditForm);
  const [resetPassword, setResetPassword] = useState("");
  const [userSearch, setUserSearch] = useState("");
  const [refreshing, setRefreshing] = useState(false);

  async function loadUsers() {
    setRefreshing(true);
    try {
      const payload = await apiJson("/api/admin/users");
      setUsers(payload.users || []);
      setError("");
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setRefreshing(false);
    }
  }
  async function loadUserDetail(userId) {
    const payload = await apiJson(`/api/admin/users/${userId}`);
    setSelected(payload);
    setEditForm({ username: payload.user.username, email: payload.user.email, displayName: payload.user.displayName, status: payload.user.status });
  }
  useEffect(() => { loadUsers().catch(() => {}); }, []);
  const filteredUsers = useMemo(() => {
    const keyword = userSearch.trim().toLowerCase();
    if (!keyword) return users;
    return users.filter((user) => user.username.toLowerCase().includes(keyword) || user.email.toLowerCase().includes(keyword) || user.displayName.toLowerCase().includes(keyword));
  }, [users, userSearch]);
  async function handleCreate(event) {
    event.preventDefault();
    setError("");
    setMessage("");
    try {
      const payload = await apiJson("/api/admin/users", { method: "POST", body: JSON.stringify(createForm) });
      setCreateForm(emptyCreateForm);
      setMessage(`User ${payload.user.username} created.`);
      await loadUsers();
    } catch (submitError) { setError(submitError.message); }
  }
  async function handleUpdateUser(event) {
    event.preventDefault();
    if (!selected?.user?.id) return;
    setError("");
    setMessage("");
    try {
      const payload = await apiJson(`/api/admin/users/${selected.user.id}`, { method: "PATCH", body: JSON.stringify(editForm) });
      setMessage(`User ${payload.user.username} updated.`);
      await loadUsers();
      await loadUserDetail(selected.user.id);
    } catch (submitError) { setError(submitError.message); }
  }
  async function handleResetPassword(event) {
    event.preventDefault();
    if (!selected?.user?.id || !resetPassword) return;
    setError("");
    setMessage("");
    try {
      await apiJson(`/api/admin/users/${selected.user.id}/password`, { method: "PATCH", body: JSON.stringify({ nextPassword: resetPassword }) });
      setResetPassword("");
      setMessage(`Password reset for ${selected.user.username}.`);
    } catch (submitError) { setError(submitError.message); }
  }
  async function handleDeleteUser() {
    if (!selected?.user?.id) return;
    const confirmed = window.confirm(`Delete user ${selected.user.username}? This is permanent.`);
    if (!confirmed) return;
    setError("");
    setMessage("");
    try {
      await apiJson(`/api/admin/users/${selected.user.id}`, { method: "DELETE" });
      setMessage(`User ${selected.user.username} deleted.`);
      setSelected(null);
      setEditForm(emptyEditForm);
      setResetPassword("");
      await loadUsers();
    } catch (deleteError) { setError(deleteError.message); }
  }
  async function handleToggleProvider(providerKey, isEnabled) {
    if (!selected?.user?.id) return;
    setError("");
    try {
      await apiJson(`/api/admin/users/${selected.user.id}/providers/${providerKey}`, { method: "PUT", body: JSON.stringify({ isEnabled: !isEnabled }) });
      await loadUserDetail(selected.user.id);
      await loadUsers();
    } catch (toggleError) { setError(toggleError.message); }
  }

  return (
    <div className="panel-grid">
      <PageHeader eyebrow="User Management" title="Accounts, Access, and Activity" description="Create users, edit account state, reset passwords, inspect sessions, and review watch behavior." actions={<button type="button" className="secondary-button" onClick={() => loadUsers()}>{refreshing ? "Refreshing..." : "Refresh"}</button>} />
      {message ? <div className="success-banner">{message}</div> : null}
      {error ? <div className="auth-error">{error}</div> : null}
      <div className="admin-users-layout">
        <div className="admin-sidebar-stack">
          <div className="panel-card">
            <h2>Create User</h2>
            <form className="stack-form" onSubmit={handleCreate}>
              <input value={createForm.username} onChange={(event) => setCreateForm((current) => ({ ...current, username: event.target.value }))} placeholder="Username" />
              <input value={createForm.email} onChange={(event) => setCreateForm((current) => ({ ...current, email: event.target.value }))} placeholder="Email" />
              <input value={createForm.displayName} onChange={(event) => setCreateForm((current) => ({ ...current, displayName: event.target.value }))} placeholder="Display name" />
              <input type="password" value={createForm.password} onChange={(event) => setCreateForm((current) => ({ ...current, password: event.target.value }))} placeholder="Temporary password" />
              <button type="submit">Create User</button>
            </form>
          </div>
          <div className="panel-card">
            <div className="section-head"><h2>Users</h2><div className="section-head-meta">{filteredUsers.length} shown</div></div>
            <input className="table-filter" value={userSearch} onChange={(event) => setUserSearch(event.target.value)} placeholder="Search by username, email, or name" />
            <div className="table-list">
              {filteredUsers.length ? filteredUsers.map((user) => (
                <button type="button" key={user.id} className={`table-row table-row-button user-list-item ${selected?.user?.id === user.id ? "is-selected" : ""}`} onClick={() => loadUserDetail(user.id).catch((loadError) => setError(loadError.message))}>
                  <div><strong>{user.displayName}</strong><span>{user.username} · {user.email}</span></div>
                  <div><StatusPill label={user.status} tone={getUserStatusTone(user.status)} /><span>{user.lastSeenAt ? formatDate(user.lastSeenAt) : "Never seen"}</span></div>
                </button>
              )) : <EmptyState title="No matching users" description="Adjust the filter or create a new account." />}
            </div>
          </div>
        </div>
        <div className="admin-detail-stack">
          {!selected ? <div className="panel-card"><EmptyState title="No user selected" description="Choose a user from the list to inspect account details, provider access, sessions, and watch activity." /></div> : (
            <>
              <div className="panel-card">
                <div className="user-detail-top">
                  <div><h2>{selected.user.displayName}</h2><div className="provider-meta">{selected.user.username} · {selected.user.email}</div></div>
                  <div className="user-detail-statuses"><StatusPill label={selected.user.status} tone={getUserStatusTone(selected.user.status)} /><StatusPill label={selected.user.lastSeenAt ? "Online Recently" : "Never Seen"} tone={selected.user.lastSeenAt ? "healthy" : "muted"} /></div>
                </div>
                <div className="summary-grid">
                  <SummaryCard label="Favorites" value={selected.favorites.length} subvalue="Saved media items" />
                  <SummaryCard label="Watch Events" value={selected.history.length} subvalue="Recent playback history" />
                  <SummaryCard label="Active Progress" value={selected.progress.length} subvalue="Resume checkpoints" />
                  <SummaryCard label="Sessions" value={selected.sessions.length} subvalue={`Last seen ${formatDate(selected.user.lastSeenAt)}`} />
                </div>
              </div>
              <div className="admin-detail-grid">
                <div className="panel-card">
                  <h2>Account Settings</h2>
                  <form className="stack-form" onSubmit={handleUpdateUser}>
                    <input value={editForm.username} onChange={(event) => setEditForm((current) => ({ ...current, username: event.target.value }))} />
                    <input value={editForm.email} onChange={(event) => setEditForm((current) => ({ ...current, email: event.target.value }))} />
                    <input value={editForm.displayName} onChange={(event) => setEditForm((current) => ({ ...current, displayName: event.target.value }))} />
                    <select value={editForm.status} onChange={(event) => setEditForm((current) => ({ ...current, status: event.target.value }))}><option value="ACTIVE">ACTIVE</option><option value="DISABLED">DISABLED</option></select>
                    <button type="submit">Save User</button>
                  </form>
                </div>
                <div className="panel-card">
                  <h2>Security Actions</h2>
                  <form className="stack-form" onSubmit={handleResetPassword}>
                    <input type="password" value={resetPassword} onChange={(event) => setResetPassword(event.target.value)} placeholder="New password" />
                    <button type="submit">Reset Password</button>
                  </form>
                  <div className="danger-zone">
                    <div className="danger-zone-copy"><strong>Delete this account</strong><span>Removes the user and all associated sessions, favorites, progress, and watch history.</span></div>
                    <button type="button" className="danger-button" onClick={handleDeleteUser}>Delete User</button>
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function AdminAccountPage({ session, setSession }) {
  const [form, setForm] = useState({ username: session.user.username, email: session.user.email, displayName: session.user.displayName });
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  async function handleSubmit(event) {
    event.preventDefault();
    setMessage("");
    setError("");
    try {
      const payload = await apiJson("/api/auth/me/profile", { method: "PATCH", body: JSON.stringify(form) });
      const nextSession = { ...session, user: payload.user };
      setStoredSession(nextSession);
      setSession(nextSession);
      setMessage("Account updated.");
    } catch (submitError) { setError(submitError.message); }
  }
  return (
    <div className="panel-grid">
      <PageHeader eyebrow="Admin Account" title="Your Admin Identity" description="Manage the administrator profile and rotate the local admin password from one place." />
      <div className="admin-detail-grid">
        <div className="panel-card">
          <h2>Profile</h2>
          <form className="stack-form" onSubmit={handleSubmit}>
            <input value={form.username} onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))} />
            <input value={form.email} onChange={(event) => setForm((current) => ({ ...current, email: event.target.value }))} />
            <input value={form.displayName} onChange={(event) => setForm((current) => ({ ...current, displayName: event.target.value }))} />
            <button type="submit">Save Account</button>
          </form>
          {message ? <div className="success-banner">{message}</div> : null}
          {error ? <div className="auth-error">{error}</div> : null}
        </div>
        <PasswordCard title="Admin Password" endpoint="/api/auth/me/password" bodyBuilder={({ currentPassword, nextPassword }) => ({ currentPassword, nextPassword })} />
      </div>
    </div>
  );
}

export default function AdminPortal({ session, setSession, onLogout }) {
  const links = useMemo(() => [
    { to: "/admin", label: "Dashboard" },
    { to: "/admin/providers", label: "Providers" },
    { to: "/admin/users", label: "Users" },
    { to: "/admin/audit", label: "Audit" },
    { to: "/admin/account", label: "Account" },
  ], []);

  return (
    <Shell title={`Admin · ${session.user.displayName}`} links={links} onLogout={onLogout}>
      <Routes>
        <Route index element={<DashboardPage />} />
        <Route path="providers" element={<ProvidersPage />} />
        <Route path="users" element={<UserManagementPage />} />
        <Route path="audit" element={<AuditLogsPage />} />
        <Route path="account" element={<AdminAccountPage session={session} setSession={setSession} />} />
      </Routes>
    </Shell>
  );
}
