/**
 * A small fixed-window limiter, kept in memory.
 *
 * The credential endpoints are the only unauthenticated way in, and on a public
 * domain they will be found and hammered. There is one server process and one
 * person's instance, so a shared store would be machinery without a purpose;
 * this resets when the process restarts, which is acceptable for what it
 * protects against.
 */

const buckets = new Map();

const SWEEP_INTERVAL_MS = 5 * 60_000;

// Expired buckets would otherwise accumulate one entry per address seen.
const sweeper = setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of buckets) {
    if (bucket.resetAt <= now) buckets.delete(key);
  }
}, SWEEP_INTERVAL_MS);
sweeper.unref?.();

/**
 * @param {object} options
 * @param {number} options.windowMs
 * @param {number} options.max
 * @param {string} options.name
 * @param {(statusCode: number) => boolean} [options.countWhen]
 *   Which responses count towards the limit. Defaults to all of them. Pass a
 *   predicate to count only failures: a household shares one public address, so
 *   counting successful sign-ins too would let one person's typo lock out
 *   everybody else for the rest of the window.
 */
export function rateLimit({ windowMs, max, name, countWhen }) {
  return function limiter(request, response, next) {
    // request.ip is only meaningful because the app trusts its reverse proxy;
    // without that every caller would share the proxy's address and one noisy
    // client would lock out everyone.
    const key = `${name}:${request.ip}`;
    const now = Date.now();
    const bucket = buckets.get(key);

    if (bucket && bucket.resetAt > now && bucket.count >= max) {
      const seconds = Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
      response.setHeader("retry-after", String(seconds));
      response.status(429).json({
        error: `Too many attempts. Try again in ${seconds} seconds.`,
      });
      return;
    }

    const record = () => {
      const current = buckets.get(key);
      if (!current || current.resetAt <= Date.now()) {
        buckets.set(key, { count: 1, resetAt: Date.now() + windowMs });
      } else {
        current.count += 1;
      }
    };

    if (countWhen) {
      response.on("finish", () => {
        if (countWhen(response.statusCode)) record();
      });
    } else {
      record();
    }

    next();
  };
}
