/**
 * CDN hosts that only answer with a specific Referer.
 *
 * VidSrc's CDN 403s any master, variant or segment request whose Referer is
 * not the player page that issued the playback token. A client cannot send
 * that header (hls.js cross-origin, AVPlay, a cast receiver handed a bare
 * URL), so the provider registers each CDN host it hands out together with
 * the referer it needs, and the media endpoints (/api/stream, /api/manifest)
 * look the target up here when the request did not carry one.
 *
 * In memory only: a restart forgets every host, and with it every VidSrc
 * session, so the next /api/item or /api/episodes call re-registers both.
 */

const TTL_MS = 1000 * 60 * 60 * 6; // the playback JWT lives ~4 h
const MAX_HOSTS = 500;

const hosts = new Map(); // hostname → { referer, at }

export function registerUpstreamReferer(targetUrl, referer) {
  if (!referer) return;
  let hostname;
  try {
    hostname = new URL(targetUrl).hostname;
  } catch {
    return;
  }
  hosts.delete(hostname);
  hosts.set(hostname, { referer, at: Date.now() });
  if (hosts.size > MAX_HOSTS) hosts.delete(hosts.keys().next().value);
}

/** The referer a target's host is known to need, or null. */
export function lookupUpstreamReferer(targetUrl) {
  let hostname;
  try {
    hostname = new URL(targetUrl).hostname;
  } catch {
    return null;
  }
  const entry = hosts.get(hostname);
  if (!entry) return null;
  if (Date.now() - entry.at > TTL_MS) {
    hosts.delete(hostname);
    return null;
  }
  return entry.referer;
}
