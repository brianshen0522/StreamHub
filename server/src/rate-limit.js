/**
 * A small fixed-window limiter, kept in memory.
 *
 * The credential endpoints are the only unauthenticated way in, and on a public
 * domain they will be found and hammered. There is one server process and one
 * person's instance, so a shared store would be machinery without a purpose;
 * this resets when the process restarts, which is acceptable for what it
 * protects against.
 */

import net from "node:net";

const buckets = new Map();

/** Requests let through but not yet answered, per key, for limiters that count outcomes. */
const inFlight = new Map();

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
 * One subscriber is handed a whole IPv6 /64, so counting by full address would
 * give a single caller more buckets than it could ever use up.
 */
function clientKey(ip) {
  if (!net.isIPv6(ip) || ip.toLowerCase().startsWith("::ffff:")) return ip;
  const [head, tail = ""] = ip.split("::");
  const front = head ? head.split(":") : [];
  const back = tail ? tail.split(":") : [];
  const zeros = Array(Math.max(0, 8 - front.length - back.length)).fill("0");
  return [...front, ...zeros, ...back].slice(0, 4).join(":");
}

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
    const key = `${name}:${clientKey(request.ip)}`;
    const now = Date.now();
    const bucket = buckets.get(key);

    const used = bucket && bucket.resetAt > now ? bucket.count : 0;
    // Outcomes are only known once a request finishes, so a burst sent at once
    // would otherwise all pass while the count still read zero.
    if (used + (inFlight.get(key) ?? 0) >= max) {
      const resetAt = bucket && bucket.resetAt > now ? bucket.resetAt : now + 1000;
      const seconds = Math.max(1, Math.ceil((resetAt - now) / 1000));
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
      inFlight.set(key, (inFlight.get(key) ?? 0) + 1);
      // "close" rather than "finish": it also fires for a caller that hung up.
      response.once("close", () => {
        const remaining = inFlight.get(key) - 1;
        if (remaining > 0) inFlight.set(key, remaining);
        else inFlight.delete(key);
        if (countWhen(response.statusCode)) record();
      });
    } else {
      record();
    }

    next();
  };
}
