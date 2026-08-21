package com.streamhub.core.model

import kotlinx.serialization.Serializable

/**
 * Signing a television in from a phone.
 *
 * Typing a password on a remote control is slow enough that people pick worse
 * passwords for it, so the set never asks for one. It asks the server for a
 * code, shows the code, and waits for somebody signed in elsewhere to say yes.
 *
 * Two codes, doing different jobs. [deviceCode] is long, random and never
 * displayed — it is what the set polls with and what actually collects the
 * session, so it must not appear on screen or in a photograph of the screen.
 * [userCode] is the short one meant to be read across a room and typed.
 */
@Serializable
data class DevicePairing(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    /** The same page with the code already in it — this is what the QR encodes. */
    val verificationUrlComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

/** What the server says when the television asks whether anyone has answered. */
sealed interface DevicePairingStatus {
    /** Nobody has answered yet. Keep waiting. */
    data object Pending : DevicePairingStatus

    /** Somebody said this was not their television. Start over with a new code. */
    data object Denied : DevicePairingStatus

    /**
     * The code ran out, or was never one this server issued. Both mean the same
     * thing to the set — show a fresh code — and the server deliberately does
     * not distinguish them.
     */
    data object Expired : DevicePairingStatus

    /** Signed in. The session is already stored by the time this is returned. */
    data class Approved(val session: Session) : DevicePairingStatus
}

/**
 * What is asking to be signed in, shown to the person holding the phone.
 *
 * The point of it is [deviceName]: a device flow cannot stop somebody being
 * talked into approving a code that is not theirs, so the only defence is that
 * the person can recognise whether the thing asking is their own television.
 * Approving without showing this would be a button that grants an account to
 * whoever asked last.
 */
@Serializable
data class PendingDevice(
    val userCode: String,
    val deviceName: String,
    val clientKind: String? = null,
    val requestedAt: String? = null,
    val expiresAt: String? = null,
)

/**
 * The short code, as a person handles it rather than as the server stores it.
 *
 * What gets typed is never quite what was displayed: people put the separator
 * back, or leave it out, or the keyboard capitalises for them. Only the
 * characters carry meaning, so everything is reduced to those before it goes
 * anywhere near the server, and the break is put back only for display.
 */
object UserCode {
    const val LENGTH = 8

    fun normalise(input: String): String =
        input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isComplete(input: String): Boolean = normalise(input).length == LENGTH

    /** `ABCD-EFGH` — one break, because eight unbroken characters get miscounted. */
    fun forDisplay(input: String): String {
        val clean = normalise(input).take(LENGTH)
        return if (clean.length > 4) "${clean.take(4)}-${clean.drop(4)}" else clean
    }

    /**
     * The code out of whatever a camera read, or null if it was not one of ours.
     *
     * The television encodes a link, so the usual case is a URL — but only its
     * `code` parameter is taken and **the URL itself is never opened**. A QR is
     * something a stranger can print and leave on a wall; treating one as a
     * place to navigate would be handing a signed-in session to whoever printed
     * it. Anything unrecognised returns null rather than an error, because a
     * camera pointed at a room finds wifi codes and packaging, and stopping on
     * each of them would make scanning unusable.
     */
    fun fromScan(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val fromQuery = Regex("""[?&]code=([^&#\s]+)""").find(text)?.groupValues?.get(1)
        if (fromQuery != null) {
            val candidate = normalise(fromQuery)
            if (candidate.length == LENGTH) return candidate
        }

        val bare = normalise(text)
        return if (bare.length == LENGTH) bare else null
    }
}

@Serializable
internal data class DevicePollRequest(val deviceCode: String)

@Serializable
internal data class DeviceCodeRequest(val code: String)

/**
 * The poll response, flat because approval carries a whole session alongside
 * the status rather than nested under it.
 */
@Serializable
internal data class DevicePollResponse(
    val status: String,
    val user: User? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)
