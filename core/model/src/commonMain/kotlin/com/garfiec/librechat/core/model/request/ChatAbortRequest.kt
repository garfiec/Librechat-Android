package com.garfiec.librechat.core.model.request

import kotlinx.serialization.Serializable

@Serializable
data class ChatAbortRequest(
    /**
     * The job key, when one is known. **Never the empty string.**
     *
     * The abort route validates every target field before resolving anything: a value that is
     * `!= null` but zero-length (or over 512 chars) is rejected outright with 400
     * `INVALID_ABORT_TARGET`. Mobile used to send `streamId.orEmpty()` here and relied on the
     * empty string being falsy server-side, which is exactly the input that now hard-fails —
     * so an unknown id must OMIT this field and say so through [conversationId] instead.
     */
    val abortKey: String? = null,
    /**
     * The conversation being aborted, or the literal `"new"` when the `created` event has not
     * assigned an id yet.
     *
     * `"new"` is the only way to reach the route's user-scoped fallback: it is gated on
     * `streamId === 'new' || conversationId === 'new'`, so an omitted or unknown-but-concrete id
     * resolves no job and aborts nothing. Safe against older servers too — they skipped `"new"`
     * when picking a job id and then fell into the same fallback unconditionally.
     */
    val conversationId: String? = null,
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
