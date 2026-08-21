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

@Serializable
internal data class DevicePollRequest(val deviceCode: String)

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
