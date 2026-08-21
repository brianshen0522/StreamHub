package com.streamhub.core

/**
 * The parts of the server contract that every request depends on.
 *
 * See shared/api/README.md in the repository root for the behaviour behind
 * these — in particular why the client header matters and why refresh has to be
 * single-flight.
 */
object ApiConfig {
    /**
     * Clients pin to the versioned prefix so the unversioned surface the web app
     * uses stays free to change. Nothing pushes updates to a sideloaded build,
     * so an old client has to keep working against a newer server.
     */
    const val BASE_PATH = "/api/v1"

    /**
     * Identifies this build as a playback client. Sending it is what makes the
     * server refuse an admin account at the login screen, rather than handing
     * back a session that fails on every content screen afterwards.
     */
    const val CLIENT_HEADER = "X-StreamHub-Client"

    /** Only accounts with this role can reach any content route. */
    const val REQUIRED_ROLE = "USER"
}

/** Which build is talking, sent as [ApiConfig.CLIENT_HEADER]. */
enum class ClientKind(val header: String) {
    PHONE("android"),
    TV("tv"),
}
