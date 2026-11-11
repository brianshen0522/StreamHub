// Undo what a run created on the QA account, by diffing against the snapshot
// global-setup took. Progress for a title that had none before is removed by
// title (`scope: "title"`, the continue-shelf dismiss); a title that already
// had rows only loses the episode rows the run added. Rows the run merely
// *updated* cannot be restored, and watch history / source preferences have
// no delete route for a user — they remain (see README).
import { progressId } from "./api.js";

export async function cleanup(api, before) {
  const report = { progress: 0, favorites: 0, sessions: 0 };
  const now = await api.snapshot();
  const { progress } = await api.json("GET", "/me/progress");

  const oldTitles = new Set(before.titles);
  const oldRows = new Set(before.progress);
  const doneTitles = new Set();
  for (const row of progress) {
    const titleKey = `${row.providerKey}\u0000${row.title}`;
    if (!oldTitles.has(titleKey)) {
      if (doneTitles.has(titleKey)) continue;
      doneTitles.add(titleKey);
      await api.json("DELETE", "/me/progress", {
        providerKey: row.providerKey, itemUrl: row.itemUrl, title: row.title, scope: "title",
      });
      report.progress += 1;
    } else if (!oldRows.has(progressId(row))) {
      await api.json("DELETE", "/me/progress", {
        providerKey: row.providerKey, itemUrl: row.itemUrl,
        seasonUrl: row.seasonUrl || "", episodeLabel: row.episodeLabel || "",
      });
      report.progress += 1;
    }
  }

  const oldFavs = new Set(before.favorites);
  for (const id of now.favorites) {
    if (!oldFavs.has(id)) { await api.json("DELETE", `/me/favorites/${id}`); report.favorites += 1; }
  }

  // Sessions: everything the run minted (seeded app sessions, pairing) except
  // the one doing the cleaning, which logs itself out last.
  const oldSessions = new Set(before.sessions);
  const { sessions } = await api.json("GET", "/me/sessions");
  for (const s of sessions) {
    if (!oldSessions.has(s.id) && !s.current) {
      await api.json("DELETE", `/me/sessions/${s.id}`);
      report.sessions += 1;
    }
  }
  return report;
}
