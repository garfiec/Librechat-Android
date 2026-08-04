package com.garfiec.librechat.core.network.client

/**
 * Why the app is being routed back to the auth flow.
 *
 * All three ride the same signal because they need the same navigation, but they are not the same
 * event to a user: only [EXPIRED] arrives unannounced, so it is the only one that owes an
 * explanation. Telling someone who just tapped "Log out" that their session expired is noise, and
 * saying it to someone the server blocked is wrong — [ApiException.isBanned] already reports that on
 * the screen they were looking at.
 */
enum class SessionEndReason {
    /** The server rejected the session: a refresh answered 401/403, or there was no token to send. */
    EXPIRED,

    /** The user signed out, or removed their last account. */
    SIGNED_OUT,

    /** The server banned the account mid-session. */
    BANNED,
}
