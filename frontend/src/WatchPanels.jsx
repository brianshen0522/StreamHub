import { useEffect, useRef, useState } from "react";

/**
 * Season picker, episode rail and source picker for the watch page.
 *
 * These take pre-derived rows rather than raw provider payloads. App owns the
 * progress bookkeeping and the two provider-specific TV shapes (movieffm's
 * per-season /drama/ URLs vs 777tv's single detail page); keeping that out of
 * here means the panels stay presentational.
 */

/**
 * A native <select> rather than a custom popover: eight seasons of "Season 3
 * 2006全24集" wrap into an unreadable pill grid, and on touch the platform
 * picker beats anything we would draw.
 */
export function SeasonSelect({ label, options, value, onChange }) {
  if (!options.length) return null;
  return (
    <label className="rail-season">
      <span className="rail-label">{label}</span>
      <select
        className="rail-select"
        value={value || ""}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option) => (
          <option key={option.url} value={option.url}>
            {option.optionLabel}
          </option>
        ))}
      </select>
    </label>
  );
}

function IconPlaying() {
  return (
    <svg className="ep-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M8 5v14l11-7z" />
    </svg>
  );
}

function IconCheck() {
  return (
    <svg className="ep-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 12.5 9.5 18 20 7" />
    </svg>
  );
}

export function EpisodeRail({ heading, rows, onSelect, watchedLabel, nowPlayingLabel }) {
  const activeRef = useRef(null);
  const listRef = useRef(null);
  const activeLabel = rows.find((row) => row.isActive)?.label || "";

  // Keep the episode being watched visible when the user arrives deep into a
  // season from a resume link. Scoped to the rail's own scroll area: in the
  // narrow layout the list flows with the page, and scrollIntoView there would
  // drag the whole document off the player.
  useEffect(() => {
    const el = activeRef.current;
    const list = listRef.current;
    if (!el || !list || list.scrollHeight <= list.clientHeight) return;
    const listRect = list.getBoundingClientRect();
    const elRect = el.getBoundingClientRect();
    list.scrollTop += elRect.top - listRect.top - (list.clientHeight - elRect.height) / 2;
  }, [activeLabel]);

  if (!rows.length) return null;

  return (
    <div className="rail-block">
      <div className="rail-heading">
        <span className="rail-label">{heading}</span>
        <span className="rail-count">{rows.length}</span>
      </div>
      <ul className="ep-list" ref={listRef}>
        {rows.map((row) => (
          <li key={row.label}>
            <button
              type="button"
              ref={row.isActive ? activeRef : null}
              className={`ep-row${row.isActive ? " is-active" : ""}${row.isCompleted ? " is-done" : ""}${row.percent > 0 && !row.isCompleted ? " is-progress" : ""}`}
              style={row.percent > 0 && !row.isCompleted ? { "--ep-progress": `${row.percent}%` } : undefined}
              aria-current={row.isActive ? "true" : undefined}
              onClick={() => onSelect(row.label)}
            >
              <span className="ep-num">{row.label}</span>
              <span className="ep-body">
                <span className="ep-title">{row.title}</span>
                {row.percent > 0 && !row.isCompleted ? (
                  <span className="ep-bar" aria-hidden="true">
                    <span className="ep-bar-fill" />
                  </span>
                ) : null}
              </span>
              <span className="ep-state">
                {row.isActive ? (
                  <>
                    <IconPlaying />
                    <span className="sr-only">{nowPlayingLabel}</span>
                  </>
                ) : row.isCompleted ? (
                  <>
                    <IconCheck />
                    <span className="sr-only">{watchedLabel}</span>
                  </>
                ) : row.percent > 0 ? (
                  <span className="ep-pct">{Math.round(row.percent)}%</span>
                ) : null}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Mirror sources are an implementation detail of this app, not something the
 * mainstream players expose, so it collapses to one line showing what is
 * playing and opens on demand instead of spilling 21 pills onto the page.
 */
/**
 * Marks a source whose ads the filter has already found and will remove.
 *
 * Green, not red: the ads being there is not the news — every provider splices
 * them — the news is that this source is one the filter measured and can clean,
 * which is a reason to pick it. Red on this list would read as a fault in the
 * source and steer people away from exactly the ones that work best.
 *
 * The runtime shown beside it is already the *content* length with the ads
 * taken out, so the two read together.
 */
function AdTag({ text, title, seconds }) {
  if (!text) return null;
  return (
    <span className="src-adtag" title={title ? title.replace("{s}", seconds) : undefined}>
      {text}
    </span>
  );
}

export function SourceSelect({
  label,
  rows,
  activeKey,
  onSelect,
  loading,
  loadingText,
  emptyText,
  note,
  adNote,
  adTag,
  adTagTitle,
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event) => {
      if (!wrapRef.current?.contains(event.target)) setOpen(false);
    };
    const onKeyDown = (event) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  // A source list that reloads under an open menu (episode switch) would leave
  // the popover pointing at rows that no longer exist.
  useEffect(() => {
    if (loading) setOpen(false);
  }, [loading]);

  const active = rows.find((row) => row.key === activeKey) || null;
  const menuRef = useRef(null);

  // Sources are ordered by runtime, so the one playing is rarely at the top of
  // a 21-entry list; open the menu already showing it.
  useEffect(() => {
    if (!open) return;
    menuRef.current?.querySelector(".src-option.is-selected")?.scrollIntoView({ block: "nearest" });
  }, [open]);

  return (
    <div className="src-select" ref={wrapRef}>
      <span className="rail-label">{label}</span>
      <div className="src-anchor">
        <button
          type="button"
          className={`src-trigger${open ? " is-open" : ""}`}
          onClick={() => setOpen((current) => !current)}
          disabled={!rows.length}
          aria-haspopup="listbox"
          aria-expanded={open}
        >
          {active ? (
            <>
              <span className={`mode-dot ${active.mode === "proxy" ? "proxy" : "direct"}`} />
              <span className="src-trigger-label">{active.label}</span>
              {active.adSeconds > 0 ? <AdTag text={adTag} title={adTagTitle} seconds={active.adSeconds} /> : null}
              <span className="src-trigger-duration">{active.duration}</span>
            </>
          ) : (
            <span className="src-trigger-label is-muted">
              {loading ? loadingText : emptyText}
            </span>
          )}
          <span className="src-caret" aria-hidden="true">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 6l4 4 4-4" />
            </svg>
          </span>
          {rows.length ? <span className="src-count">{rows.length}</span> : null}
        </button>

        {open && rows.length ? (
          <ul className="src-menu" role="listbox" ref={menuRef}>
            {rows.map((row) => (
              <li key={row.key}>
                <button
                  type="button"
                  role="option"
                  aria-selected={row.key === activeKey}
                  className={`src-option${row.key === activeKey ? " is-selected" : ""}`}
                  onClick={() => {
                    onSelect(row.source);
                    setOpen(false);
                  }}
                >
                  <span className={`mode-dot ${row.mode === "proxy" ? "proxy" : "direct"}`} />
                  <span className="src-option-label">{row.label}</span>
                  {row.adSeconds > 0 ? <AdTag text={adTag} title={adTagTitle} seconds={row.adSeconds} /> : null}
                  <span className="src-option-duration">{row.duration}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      {note ? <span className="src-note">{note}</span> : null}
      {adNote ? <span className="src-note is-ad">{adNote}</span> : null}
    </div>
  );
}
