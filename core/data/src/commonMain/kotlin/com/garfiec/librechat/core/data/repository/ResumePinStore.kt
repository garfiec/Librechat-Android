package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.request.EphemeralAgent

/**
 * The turn config a run was STARTED with, kept per conversation for the lifetime of the process.
 *
 * Resuming a human-review pause replays a fingerprint the server recomputes over endpoint /
 * endpointType / agent_id / model / promptPrefix / ephemeralAgent, and 403s anything that does not
 * match the run being resumed. The ViewModel that started the run holds those values — but the
 * ViewModel that RESOLVES the pause is frequently a different one:
 *
 * - a chat started from the landing page hands off to a fresh `Chat(id)` ViewModel, and the first
 *   tool approval of a brand-new chat is the single most common way to meet a pause;
 * - reopening a conversation with a live pause builds a ViewModel from scratch.
 *
 * Neither can rebuild the config from the conversation record: it carries endpoint, model and
 * agent id, but not the enabled tools, MCP servers or custom instructions that
 * `ephemeralAgent`/`promptPrefix` are built from. Re-deriving from the live selection instead
 * pins whatever the composer happens to hold — which loses a race against the conversation load
 * on a cold open, and is simply wrong once the user changes a tool mid-pause.
 *
 * Deliberately in-memory. The pin only matters while the server-side run is alive (a pause
 * expires in minutes), so surviving process death would buy little for the cost of a schema
 * migration; a resume after process death falls back to the live-config guess exactly as before.
 */
class ResumePinStore {

    private val pins = LinkedHashMap<String, ResumeTurnPin>()

    fun put(conversationId: String, pin: ResumeTurnPin) {
        if (conversationId.isBlank()) return
        pins.remove(conversationId)
        pins[conversationId] = pin
        while (pins.size > MAX_TRACKED_CONVERSATIONS) {
            pins.remove(pins.keys.first())
        }
    }

    fun get(conversationId: String?): ResumeTurnPin? = conversationId?.let { pins[it] }

    fun remove(conversationId: String?) {
        conversationId?.let { pins.remove(it) }
    }

    /** Dropped wholesale on logout / account switch — a pin names another account's run. */
    fun clear() = pins.clear()

    private companion object {
        /** Enough for the handful of conversations a session realistically juggles. */
        const val MAX_TRACKED_CONVERSATIONS = 8
    }
}

/** The fingerprinted fields of the turn a run was started with. See [ResumePinStore]. */
data class ResumeTurnPin(
    val endpoint: String,
    val endpointType: String?,
    val agentId: String?,
    val model: String?,
    val promptPrefix: String?,
    val ephemeralAgent: EphemeralAgent?,
    val isTemporary: Boolean,
)
