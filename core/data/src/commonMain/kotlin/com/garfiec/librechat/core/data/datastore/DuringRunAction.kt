package com.garfiec.librechat.core.data.datastore

/**
 * What the composer's send control does while a reply is still generating (v0.8.8 steering).
 *
 * - [QUEUE] — hold the message and auto-send it once the run finishes. The behaviour mobile has
 *   always had, and the default: it works against every supported server.
 * - [STEER] — inject the message into the *running* turn at its next tool-batch boundary, so the
 *   model changes course without the reply being restarted.
 *
 * [STEER] is a preference, not a guarantee: it needs a server with the steer route and a run
 * that is actually reachable. When either is missing the send falls back to [QUEUE] rather than
 * failing, so choosing it can never cost the user their text.
 */
enum class DuringRunAction {
    QUEUE, STEER;

    companion object {
        fun fromString(value: String?): DuringRunAction = when (value) {
            "steer" -> STEER
            // Unset / unrecognized → queueing, which needs no server support.
            else -> QUEUE
        }
    }

    fun toStorageString(): String = when (this) {
        QUEUE -> "queue"
        STEER -> "steer"
    }
}
