package com.garfiec.librechat.core.model.error

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The typed `type` values the backend puts on a stream-error payload — upstream's `ErrorTypes`
 * (`packages/data-provider/src/config.ts`).
 *
 * The server used to swallow several of these and let the run continue on stale state; it now
 * raises them as typed errors and propagates them. Without a mapping a client renders the raw
 * JSON payload as the error text, which is how `{"type":"resource_recovery_required", …}` ends up
 * on screen where a sentence telling the user to reattach their files belongs.
 *
 * Only the codes with something ACTIONABLE to say are listed. That is the point of the type: an
 * unrecognized code must fall through to the generic message rather than being surfaced raw, so
 * adding a case is how a code stops being generic — never a requirement for one to be safe.
 *
 * The user-provided-key codes deliberately are NOT here. They already route through
 * [parseUserKeyError] to a snackbar with a Settings action, and duplicating them would surface the
 * same failure twice.
 */
enum class StreamErrorType(val wire: String) {
    /**
     * Required CodeAPI files could not be restored before the model ran.
     *
     * Recoverable only by the user: the files behind the run are gone, so retrying the same turn
     * fails identically until they are attached again. Previously the run continued with stale
     * image URLs reaching the provider, which is the failure this replaced.
     */
    RESOURCE_RECOVERY_REQUIRED("resource_recovery_required"),

    /** The model the turn asked for is not available on this deployment any more. */
    MISSING_MODEL("missing_model"),

    /**
     * The provider rejected the model as unknown. Not one of upstream's `ErrorTypes` — it arrives
     * as a LangChain documentation URL embedded in provider prose, which upstream matches with a
     * regex and replaces wholesale. See [Companion.MODEL_NOT_FOUND_PATTERN].
     */
    MODEL_NOT_FOUND("model_not_found"),

    /** The models configuration has not loaded, so no model can be resolved yet. */
    MODELS_NOT_LOADED("models_not_loaded"),

    /** This endpoint's model list has not loaded. */
    ENDPOINT_MODELS_NOT_LOADED("endpoint_models_not_loaded"),

    /** The admin excluded this agent's provider. */
    INVALID_AGENT_PROVIDER("invalid_agent_provider"),

    /** The model declined to answer on content-policy grounds. Not a failure to retry. */
    REFUSAL("refusal"),

    /** The prompt is longer than the model accepts. */
    INPUT_LENGTH("INPUT_LENGTH"),

    /** The request tripped the deployment's moderation. */
    MODERATION("moderation"),

    /**
     * The SSE job 404'd — it completed, expired, or was deleted before this client subscribed.
     * Reconnecting or reopening the conversation is the resolution, not retrying the send.
     */
    STREAM_EXPIRED("stream_expired"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /**
         * The typed error in a raw stream-error message, or null.
         *
         * Null covers every degrade-to-generic case: the message is not JSON, is not an object,
         * carries no `type`, or carries one this client does not recognize. A newer server's code
         * must never crash a client or reach the user as a bare identifier.
         *
         * Checked BEFORE the JSON parse, because [MODEL_NOT_FOUND] arrives as provider prose
         * rather than as a typed payload and would otherwise fall straight through to generic.
         */
        fun parse(rawMessage: String): StreamErrorType? {
            if (rawMessage.isBlank()) return null
            if (MODEL_NOT_FOUND_PATTERN.containsMatchIn(rawMessage)) return MODEL_NOT_FOUND
            val element = runCatching { parser.parseToJsonElement(rawMessage) }.getOrNull() ?: return null
            val obj = element as? JsonObject ?: return null
            // Safe-cast, not `.jsonPrimitive`: that extension throws on an object or array value,
            // and this runs inside the SSE mapping coroutine.
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            return byWire[type]
        }

        /**
         * MIRRORED from upstream `client/src/components/Messages/Content/Error.tsx`:
         * `/langchain\.com\/.*\/MODEL_NOT_FOUND(?:\/|\b)/i`.
         *
         * A provider embeds this documentation link in an otherwise unhelpful sentence, so it is
         * matched anywhere in the text rather than parsed. `\b` is spelled out as a lookahead on a
         * non-word character or end-of-input, because Kotlin's `Regex` on Android is ICU and its
         * `\b` handling around a URL is not worth relying on.
         *
         * Every literal brace stays escaped: a pattern that compiles on the JVM can still throw at
         * class-init under ICU, and no unit test on this side would catch it.
         */
        private val MODEL_NOT_FOUND_PATTERN =
            Regex("""langchain\.com/.*/MODEL_NOT_FOUND(?:/|[^A-Za-z0-9_]|$)""", RegexOption.IGNORE_CASE)

        private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Prefix for the marker a ViewModel puts on the shared error channel so the UI can swap
         * in a localized string.
         *
         * The ViewModel layer cannot reach compose resources, and the error channel carries a
         * String — the same constraint `McpViewModel.DEFERRED_MARKER` solves the same way. The
         * alternative, resolving the string in the UI from a second state field, means every
         * error surface has to know about both fields and the two can disagree.
         */
        const val MARKER_PREFIX = "stream_error:"
    }

    /** The marker form of this error, for the shared string-typed error channel. */
    val marker: String get() = "$MARKER_PREFIX$wire"
}
