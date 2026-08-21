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

@Serializable
internal data class LoginRequest(val login: String, val password: String)

@Serializable
internal data class RefreshRequest(val refreshToken: String)

@Serializable
internal data class ErrorEnvelope(val error: String? = null)
