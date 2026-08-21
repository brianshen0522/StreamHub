import crypto from "node:crypto";

/**
 * Signing a television in from a device that has a keyboard.
 *
 * Typing a password on a remote control is bad enough that people pick worse
 * passwords for it. So the set never sees one: it asks for a code, shows the
 * code, and waits. Somebody signed in elsewhere says yes, and the set is handed
 * its own session.
 *
 * The shape is OAuth's device authorisation grant (RFC 8628) with the parts
 * this instance has no use for left out — there is one client and one server,
 * so there is no client_id and no scope.
 *
 * Two secrets doing different jobs:
 *
 *   - the **device code** is long, random, and never displayed. The television
 *     holds it and polls with it, and it is the thing that actually collects
 *     the session, so it is stored hashed exactly as refresh tokens are: a
 *     leaked database must not hand out sessions.
 *   - the **user code** is short, because a person reads it off a screen across
 *     the room and types it on a phone. That is also what makes it weak, and
 *     everything below is arranged around that: it lives ten minutes, it works
 *     once, and approving is rate limited so the space cannot be walked.
 *
 * The residual risk of any device flow is social: somebody talks a person into
 * approving a code that is not their television's. Nothing cryptographic fixes
 * that, so the approval screen shows what is asking and says plainly what
 * happens — see the web client.
 */

/**
 * No I, O, 0 or 1. The code is read off a television across a room and typed
 * on a phone, so glyphs that look like other glyphs cost real attempts.
 */
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

const USER_CODE_LENGTH = 8;

/** Long enough to be unguessable; it is never typed, so length is free. */
const DEVICE_CODE_BYTES = 32;

/**
 * Ten minutes. Long enough to walk to another room and find a phone, short
 * enough that an unattended code on a screen in a shared house stops working
 * before it becomes an invitation.
 */
export const DEVICE_CODE_TTL_SECONDS = 10 * 60;

/**
 * How often the television should ask. Fast enough that approving feels
 * immediate, slow enough that a set left on this screen overnight is not a
 * meaningful load — at three seconds a full expiry is 200 requests.
 */
export const DEVICE_POLL_INTERVAL_SECONDS = 3;

/**
 * Rejected without hedging: a code is either one this server issued and is
 * still waiting on, or it is nothing. Distinguishing "expired" from "never
 * existed" to an unauthenticated caller would confirm which codes had been
 * real.
 */
export function createUserCode() {
  const bytes = crypto.randomBytes(USER_CODE_LENGTH);
  let code = "";
  for (let index = 0; index < USER_CODE_LENGTH; index += 1) {
    // Modulo is unbiased here only because the alphabet's length divides 256.
    code += ALPHABET[bytes[index] % ALPHABET.length];
  }
  return code;
}

export function createDeviceCode() {
  return crypto.randomBytes(DEVICE_CODE_BYTES).toString("base64url");
}

export function hashDeviceCode(code) {
  return crypto.createHash("sha256").update(code).digest("hex");
}

/**
 * What the viewer types is not what was displayed: people add the separator
 * back, or leave it out, or hold shift. Only the characters matter.
 */
export function normaliseUserCode(input) {
  return String(input || "")
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "");
}

/** `ABCD-EFGH` — one break, because eight unbroken characters get miscounted. */
export function formatUserCode(code) {
  const clean = normaliseUserCode(code);
  if (clean.length !== USER_CODE_LENGTH) return clean;
  return `${clean.slice(0, 4)}-${clean.slice(4)}`;
}

export function isValidUserCodeShape(code) {
  const clean = normaliseUserCode(code);
  if (clean.length !== USER_CODE_LENGTH) return false;
  return [...clean].every((character) => ALPHABET.includes(character));
}

export function getDeviceCodeExpiresAt() {
  return new Date(Date.now() + DEVICE_CODE_TTL_SECONDS * 1000);
}

/**
 * Where the person is sent to approve.
 *
 * Built from the request rather than configured, because the television is
 * already talking to this server and the address it reached is by definition
 * one that resolves. `trust proxy` is what makes the forwarded protocol and
 * host honest here; without it this would advertise the container's own port.
 */
export function verificationUrls(request, userCode) {
  const origin = `${request.protocol}://${request.get("host")}`;
  const verificationUrl = `${origin}/link`;
  return {
    verificationUrl,
    // Carrying the code in the URL is what makes scanning the QR a single
    // action; the plain URL is the fallback for typing it by hand.
    verificationUrlComplete: `${verificationUrl}?code=${encodeURIComponent(userCode)}`,
  };
}
