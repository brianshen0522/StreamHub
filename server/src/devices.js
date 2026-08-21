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
 * fingerprint nobody wants to read, so those are reduced to the browser name.
 */
export function describeDevice(session) {
  const agent = String(session.userAgent || "");

  const native = /^StreamHub-\w+\/\S+ \(([^)]+)\)/.exec(agent);
  if (native) return native[1];

  for (const [pattern, name] of [
    [/\bEdg\//, "Microsoft Edge"],
    [/\bOPR\//, "Opera"],
    [/\bFirefox\//, "Firefox"],
    [/\bChrome\//, "Chrome"],
    [/\bSafari\//, "Safari"],
  ]) {
    if (pattern.test(agent)) return name;
  }

  return session.clientKind ? `StreamHub (${session.clientKind})` : "Unknown device";
}
