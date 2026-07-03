package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart

/**
 * Helpers for Compare Models ("parallel") response messages.
 *
 * v0.8.7 persists a comparison as ONE assistant message whose content parts each
 * carry an `agentId`. The added (secondary) agent's parts are suffixed `____N`
 * (e.g. `agent_abc____1` or the ephemeral `openAI__gpt-4o___GPT-4o____1`); the
 * primary agent's parts are unsuffixed (or unattributed). These helpers split such
 * a message back into per-pane content and decode the ephemeral id format so the
 * client can restore the dual-pane comparison view on reopen.
 *
 * Ports the relevant pieces of upstream `packages/data-provider/src/parsers.ts`.
 */

/** Decoded components of an ephemeral (non-saved) agent id. */
data class ParsedEphemeralAgentId(
    val endpoint: String,
    val model: String,
    val sender: String? = null,
    val index: Int? = null,
)

private val INDEX_SUFFIX = Regex("""____\d+$""")

/**
 * True when [agentId] carries an `____N` index suffix — the marker the server adds
 * to the added/secondary agent in a parallel (Compare Models) run.
 */
fun isAddedAgentId(agentId: String?): Boolean =
    agentId != null && INDEX_SUFFIX.containsMatchIn(agentId)

/** Real agent ids always start with `agent_`; anything else is ephemeral. */
fun isEphemeralAgentId(agentId: String?): Boolean = agentId?.startsWith("agent_") != true

/** Strips a trailing `____N` index suffix if present (works for real and ephemeral ids). */
fun stripAgentIdSuffix(agentId: String): String = agentId.replace(INDEX_SUFFIX, "")

/**
 * Parses an ephemeral agent id back into its components, or null if it isn't the
 * ephemeral format. Format: `endpoint__model___sender____index`, where `__` escapes
 * `:` in endpoint/model, `___` separates the optional sender, and `____` separates
 * the optional index.
 */
fun parseEphemeralAgentId(agentId: String): ParsedEphemeralAgentId? {
    if (!agentId.contains("__")) return null

    // Peel off the optional ____index suffix first.
    var workingId = agentId
    var index: Int? = null
    if (agentId.contains("____")) {
        val sep = agentId.lastIndexOf("____")
        val parsed = agentId.substring(sep + 4).toIntOrNull()
        if (parsed != null) {
            index = parsed
            workingId = agentId.substring(0, sep)
        }
    }

    // Then the optional ___sender segment.
    var mainPart = workingId
    var sender: String? = null
    if (workingId.contains("___")) {
        val parts = workingId.split("___", limit = 2)
        mainPart = parts[0]
        sender = parts.getOrNull(1)?.replace("__", ":")
    }

    val segments = mainPart.split("__")
    val endpoint = segments.firstOrNull()
    if (endpoint.isNullOrEmpty() || segments.size < 2) return null
    // Model names may themselves contain `:` (escaped as `__`); rejoin the remainder.
    val model = segments.drop(1).joinToString(":")
    return ParsedEphemeralAgentId(endpoint = endpoint, model = model, sender = sender, index = index)
}

/**
 * True when [message] is a parallel (Compare Models) response: at least one of its
 * content parts is attributed to an added agent (`____N` suffix).
 */
fun hasParallelParts(message: Message): Boolean =
    message.content?.any { isAddedAgentId(it.agentId) } == true

/** The added (secondary) agent's id from a parallel message, if any. */
fun secondaryAgentId(message: Message): String? =
    message.content?.firstNotNullOfOrNull { part -> part.agentId?.takeIf { isAddedAgentId(it) } }

/** The primary agent's id from a parallel message, if any (unsuffixed, attributed). */
fun primaryAgentId(message: Message): String? =
    message.content?.firstNotNullOfOrNull { part ->
        part.agentId?.takeIf { it.isNotBlank() && !isAddedAgentId(it) }
    }

/**
 * Selects the content parts belonging to one pane of a parallel message. The
 * secondary pane keeps only the added-agent (`____N`) parts; the primary pane keeps
 * everything else (unsuffixed attributed parts plus any unattributed parts).
 */
fun partsForPane(message: Message, secondary: Boolean): List<MessageContentPart> {
    val parts = message.content ?: return emptyList()
    return parts.filter { isAddedAgentId(it.agentId) == secondary }
}
