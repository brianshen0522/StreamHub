/**
 * Guards the three endpoints that fetch a URL chosen by the caller —
 * /api/stream, /api/manifest and /api/poster.
 *
 * Those exist to reach provider CDNs, but nothing in them says so: any signed-in
 * account can point them at anything the server can route to. On a machine that
 * only ever served a home network that was survivable. On a public domain it is
 * a way to read internal services, sweep the LAN, and on a cloud host reach the
 * instance metadata endpoint, using the server's own network position.
 *
 * So every hop is resolved and rejected unless it lands on a public address.
 * Redirects are followed by hand for the same reason: a target that passes the
 * check and then 302s to 127.0.0.1 would otherwise walk straight through.
 *
 * Known limit: between the check and the fetch, DNS could answer differently
 * (rebinding). Closing that needs the connection pinned to the address that was
 * checked, which Node's fetch does not expose. The window is small and the
 * caller is authenticated, so it is left open deliberately rather than
 * overlooked.
 */

import dns from "node:dns/promises";
import net from "node:net";

export class BlockedTargetError extends Error {
  constructor(message) {
    super(message);
    this.statusCode = 400;
  }
}

function isPrivateIpv4(ip) {
  const parts = ip.split(".").map(Number);
  if (parts.length !== 4 || parts.some((n) => !Number.isInteger(n))) return true;
  const [a, b] = parts;

  if (a === 0) return true;                        // "this network"
  if (a === 10) return true;                       // private
  if (a === 127) return true;                      // loopback
  if (a === 169 && b === 254) return true;         // link-local, incl. cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true; // private
  if (a === 192 && b === 168) return true;         // private
  if (a === 100 && b >= 64 && b <= 127) return true; // carrier NAT
  if (a >= 224) return true;                       // multicast and reserved
  return false;
}

function isPrivateIpv6(ip) {
  const address = ip.toLowerCase().split("%")[0];
  if (address === "::" || address === "::1") return true;

  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/.exec(address);
  if (mapped) return isPrivateIpv4(mapped[1]);

  if (address.startsWith("fe80")) return true;              // link-local
  if (/^f[cd]/.test(address)) return true;                  // unique local
  if (address.startsWith("ff")) return true;                // multicast
  return false;
}

/** Anything not clearly a public unicast address is treated as private. */
export function isPrivateAddress(ip) {
  if (net.isIPv4(ip)) return isPrivateIpv4(ip);
  if (net.isIPv6(ip)) return isPrivateIpv6(ip);
  return true;
}

/**
 * @throws {BlockedTargetError} when the URL is malformed, is not http(s), or
 *   resolves to anything that is not a public address.
 */
export async function assertPublicUrl(rawUrl) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new BlockedTargetError("Invalid target URL.");
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new BlockedTargetError("Invalid target URL.");
  }

  let addresses;
  try {
    addresses = await dns.lookup(url.hostname, { all: true });
  } catch {
    throw new BlockedTargetError("Target host could not be resolved.");
  }

  // Every answer has to be public: one private address among them is enough for
  // the connection to land there.
  if (!addresses.length || addresses.some((entry) => isPrivateAddress(entry.address))) {
    throw new BlockedTargetError("Target address is not permitted.");
  }

  return url;
}

/**
 * fetch, with every hop of the redirect chain checked.
 *
 * @returns {Promise<{response: Response, finalUrl: string}>} `finalUrl` is where
 *   the chain ended, which is what relative URLs inside a playlist resolve
 *   against — not the URL that was asked for.
 */
export async function safeFetch(rawUrl, options = {}, maxRedirects = 5) {
  let current = rawUrl;

  for (let hop = 0; hop <= maxRedirects; hop += 1) {
    await assertPublicUrl(current);

    const response = await fetch(current, { ...options, redirect: "manual" });

    if (![301, 302, 303, 307, 308].includes(response.status)) {
      return { response, finalUrl: current };
    }

    const location = response.headers.get("location");
    if (!location) return { response, finalUrl: current };

    response.body?.cancel?.().catch(() => {});
    current = new URL(location, current).toString();
  }

  throw new BlockedTargetError("Too many redirects.");
}
