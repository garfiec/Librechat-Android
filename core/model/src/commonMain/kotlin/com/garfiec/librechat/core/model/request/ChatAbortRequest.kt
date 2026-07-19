package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

@Serializable
data class ChatAbortRequest(
    val abortKey: String,
    val endpoint: String,
    /**
     * SECURITY: temp-chat data-at-rest guard, server side.
     *
     * The abort route persists the stopped partial and reads `isTemporary` straight off this
     * body to decide whether to stamp the row's expiry. Omitting the field is not the same as
     * sending `false`: neither branch runs, so the row is written with no temporary flag and
     * **no expiry** — a temporary chat's partial kept indefinitely on the server. (The original
     * chat request carries its own copy, so the abort route's save and the controller's save
     * otherwise disagree, and whichever lands last decides whether the TTL exists.) The local
     * guard in `SendCompletionDelegate` only keeps temp chats out of Room; this is what keeps
     * them expiring server-side.
     */
    val isTemporary: Boolean = false,
)
