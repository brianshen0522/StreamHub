package com.streamhub.core.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String? = null,
    val role: String,
    val status: String,
    val lastLoginAt: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    /** Only USER accounts can reach any content route; admins 403 everywhere. */
    val canPlay: Boolean get() = role == "USER"
}

/**
 * What login and refresh both return. The refresh token is single-use: the
 * server rotates it on every refresh, so the newest one always replaces the
 * stored one and two concurrent refreshes would kill the session.
 */
@Serializable
data class Session(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
)

/**
 * A device signed in to this account. A refresh token lasts thirty days, so
 * being able to see these and end one is what makes a stray sign-in actionable.
 */
@Serializable
data class DeviceSession(
    val id: String,
    val deviceName: String,
    /** "android", "tv", "ios", or null for the web app. */
    val clientKind: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    /** The device asking. Ending it would sign the asker out. */
    val current: Boolean = false,
) {
    val isTelevision: Boolean get() = clientKind == "tv"
}

@Serializable
internal data class SessionsResponse(val sessions: List<DeviceSession> = emptyList())

@Serializable
internal data class LoginRequest(val login: String, val password: String)

@Serializable
internal data class RefreshRequest(val refreshToken: String)

@Serializable
internal data class ErrorEnvelope(val error: String? = null)
