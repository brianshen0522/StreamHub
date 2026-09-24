/**
 * Whether a provider still carries an item.
 *
 * Providers drop titles. A drama that played last month answers with an empty
 * episode list today — dramasq prints "共8集" on a detail page whose episode
 * list is empty, and every guessable play-API slug 404s. The library row
 * pointing at it stays on the shelf and becomes a dead end that only reveals
 * itself when somebody taps it.
 *
 * So the answer is recorded where the shelves can read it. Two things use this:
 * `/api/item` writes what it just found, and the library endpoints read it to
 * mark rows. Nothing here ever fetches a provider — it only remembers what a
 * fetch already discovered, which is why a shelf costs no upstream requests.
 */

import { prisma } from "./db.js";

/**
 * Reasons an item is unavailable. Kept apart because they are not equally
 * trustworthy: a provider that was merely unreachable must not leave a title
 * looking deleted, so ERROR is recorded but never marks a row.
 */
export const UNAVAILABLE_NO_EPISODES = "no-episodes";
export const UNAVAILABLE_NOT_FOUND = "not-found";
export const UNAVAILABLE_ERROR = "error";

/** Reasons that mean the provider answered and the title was not there. */
const CONFIRMED_GONE = new Set([UNAVAILABLE_NO_EPISODES, UNAVAILABLE_NOT_FOUND]);

/**
 * How long a recorded answer is trusted.
 *
 * Providers re-list titles as often as they drop them, so a stale "gone" is
 * worse than no answer: it would leave a row marked unavailable long after the
 * episodes came back. A week is short enough that a re-listed title clears
 * itself, and long enough that the record survives normal use.
 */
const FRESH_FOR_MS = 1000 * 60 * 60 * 24 * 7;

/**
 * Record what a fetch discovered. Never throws: this is bookkeeping alongside a
 * request that has already succeeded or failed on its own terms, and a write
 * failing here must not turn a working page into an error.
 */
export async function recordAvailability(providerKey, itemUrl, isAvailable, reason = null) {
  if (!providerKey || !itemUrl) return;
  try {
    await prisma.itemAvailability.upsert({
      where: { providerKey_itemUrl: { providerKey, itemUrl } },
      update: { isAvailable, reason, checkedAt: new Date() },
      create: { providerKey, itemUrl, isAvailable, reason },
    });
  } catch {
    // Bookkeeping only — the caller's answer stands.
  }
}

/**
 * Look up recorded answers for a set of rows in one query.
 *
 * @param {Array<{providerKey: string, itemUrl: string}>} rows
 * @returns {Promise<Map<string, {reason: string}>>} keyed `providerKey::itemUrl`,
 *   holding only rows confirmed gone and recently enough to still be believed.
 *   A title with no record, one that was available, and one whose provider was
 *   merely unreachable are all absent — callers mark what is present and leave
 *   everything else alone.
 */
export async function findUnavailable(rows) {
  const unavailable = new Map();
  if (!rows?.length) return unavailable;

  const pairs = new Map();
  for (const row of rows) {
    if (row?.providerKey && row?.itemUrl) {
      pairs.set(`${row.providerKey}::${row.itemUrl}`, row);
    }
  }
  if (!pairs.size) return unavailable;

  try {
    const records = await prisma.itemAvailability.findMany({
      where: {
        isAvailable: false,
        reason: { in: [...CONFIRMED_GONE] },
        checkedAt: { gte: new Date(Date.now() - FRESH_FOR_MS) },
        OR: [...pairs.values()].map((row) => ({
          providerKey: row.providerKey,
          itemUrl: row.itemUrl,
        })),
      },
      select: { providerKey: true, itemUrl: true, reason: true },
    });
    for (const record of records) {
      unavailable.set(`${record.providerKey}::${record.itemUrl}`, { reason: record.reason });
    }
  } catch {
    // A shelf that cannot read this still renders; it just marks nothing.
  }

  return unavailable;
}

/**
 * Stamp `isUnavailable` onto rows that are confirmed gone.
 *
 * Returns new objects rather than mutating, and leaves rows with no record
 * untouched — absence of an answer is not an answer.
 */
export async function markUnavailable(rows) {
  if (!rows?.length) return rows;
  const unavailable = await findUnavailable(rows);
  if (!unavailable.size) return rows;
  return rows.map((row) => {
    const hit = unavailable.get(`${row.providerKey}::${row.itemUrl}`);
    return hit ? { ...row, isUnavailable: true, unavailableReason: hit.reason } : row;
  });
}
