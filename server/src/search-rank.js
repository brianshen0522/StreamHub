/**
 * Orders one provider's search results by how well each title matches what
 * was typed.
 *
 * The sites rank loosely, and two of them now split the query into words and
 * match any of them: movieffm answers 「黑暗榮耀」 with 240 titles, most of
 * which hold only 「榮耀」 or only 「黑暗」, and 777tv put 「瑞克和莫蒂第九季」
 * twenty-second for 「瑞克」. The right row was in the list, just buried.
 *
 * This only reorders. Nothing is dropped, because a title with no overlap at
 * all can still be a real answer — the sites match on cast and on alternate
 * names — and hiding it would look the same as the provider not carrying it.
 * The sort is stable, so equally good rows keep the site's own order.
 */

// Whitespace and punctuation (ASCII and full-width) carry no meaning for a
// match: 「蘭香如故·花勝鞦宴」 and 「蘭香如故花勝鞦宴」 are one title.
const NOISE = /[\s\p{P}\p{S}]+/gu;

function normalize(text) {
  return String(text || "").normalize("NFKC").toLowerCase().replace(NOISE, "");
}

/** Length of the longest run of characters the two strings share. */
function longestCommonRun(a, b) {
  if (!a || !b) return 0;
  let best = 0;
  let previous = new Array(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i += 1) {
    const current = new Array(b.length + 1).fill(0);
    for (let j = 1; j <= b.length; j += 1) {
      if (a[i - 1] === b[j - 1]) {
        current[j] = previous[j - 1] + 1;
        if (current[j] > best) best = current[j];
      }
    }
    previous = current;
  }
  return best;
}

export function scoreTitle(query, title) {
  const q = normalize(query);
  const t = normalize(title);
  if (!q || !t) return 0;
  if (t === q) return 1000;
  // A season or a dubbing of the title: 「黑暗榮耀第二季」, 「甄嬛傳粵語」.
  // Shorter extras rank first, so the plain title leads its own sequels.
  if (t.startsWith(q)) return 800 - Math.min(t.length - q.length, 100);
  if (t.includes(q)) return 600 - Math.min(t.length - q.length, 100);
  // Partial: how much of the query appears as one unbroken run, so
  // 「瑞克與莫蒂」 still finds 「瑞克和莫蒂」 ahead of a title sharing one word.
  const run = longestCommonRun(q, t);
  if (run < Math.min(2, q.length)) return 0;
  return Math.round((run / q.length) * 400);
}

export function rankSearchResults(query, items) {
  if (!Array.isArray(items) || items.length < 2) return items;
  return items
    .map((item, index) => ({ item, index, score: scoreTitle(query, item?.title) }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((entry) => entry.item);
}
