/**
 * Naming a session in a way the account holder recognises.
 *
 * Shared by the REST device list and the realtime receiver list: a television
 * offered as a cast target has to carry the same name it has in Settings, or
 * the two lists read as two different devices.
 */

/**
 * Something a person would recognise as one of their own devices.
 *
 * The native clients send a user agent naming the hardware; browsers send a
 * fingerprint nobody wants to read, so those are reduced to the browser name
 * plus the platform it runs on. The platform is the part that matters: two
 * signed-in Safaris are indistinguishable as "Safari", but "Safari on
 * iPhone" and "Safari on Mac" are two devices a person can tell apart.
 */
export function describeDevice(session) {
  const agent = String(session.userAgent || "");

  const native = /^StreamHub-\w+\/\S+ \(([^)]+)\)/.exec(agent);
  if (native) return native[1];

  let browser = null;
  for (const [pattern, name] of [
    [/\bEdg\//, "Microsoft Edge"],
    [/\bOPR\//, "Opera"],
    [/\bFirefox\//, "Firefox"],
    [/\bChrome\//, "Chrome"],
    [/\bSafari\//, "Safari"],
  ]) {
    if (pattern.test(agent)) {
      browser = name;
      break;
    }
  }
  if (!browser) {
    return session.clientKind ? `StreamHub (${session.clientKind})` : "Unknown device";
  }

  // Televisions first: their user agents also say "Android", and the model
  // string is often the only television in the sentence (measured on real
  // devices — BRAVIA, Fire TV's AFT, atv emulator builds, Chromecast, MiBOX).
  const platform = (() => {
    if (/android[^)]*\btv\b|googletv|bravia|aft[a-z]|shield|mi\s*tv|mibox|smart-?tv|chromecast|\batv\b|_atv|atv\d/i.test(agent)) return "TV";
    if (/iphone/i.test(agent)) return "iPhone";
    if (/ipad/i.test(agent)) return "iPad";
    if (/android/i.test(agent)) return "Android";
    // iPadOS Safari masquerades as a Mac; from the user agent alone the two
    // are one platform, and "Mac" is the honest name for what it claims.
    if (/macintosh|mac os x/i.test(agent)) return "Mac";
    if (/windows/i.test(agent)) return "Windows";
    if (/cros/i.test(agent)) return "ChromeOS";
    if (/linux/i.test(agent)) return "Linux";
    return null;
  })();

  return platform ? `${browser} on ${platform}` : browser;
}
