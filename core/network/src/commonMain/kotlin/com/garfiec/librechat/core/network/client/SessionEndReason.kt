package com.garfiec.librechat.core.network.client

/**
 * Why the app is being routed back to the auth flow.
 *
 * All three ride the same signal because they need the same navigation, but only [EXPIRED] arrives
 * unannounced, so it is the only one that may be reported to the user. A ban is already surfaced by
 * `ApiException.isBanned`, and a deliberate sign-out needs no explanation at all.
 */
enum class SessionEndReason {
    /** The server rejected the session: a refresh answered 401/403, or there was no token to send. */
    EXPIRED,

    /** The user signed out, or removed their last account. */
    SIGNED_OUT,

    /** The server banned the account mid-session. */
    BANNED,
}
