package com.garfiec.librechat.core.model.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-model-call token budget breakdown (v0.8.7). Mirrors upstream
 * `TTokenBudgetBreakdown` — the inputs that make up a context window for one call.
 */
@Serializable
data class TokenBudgetBreakdown(
    val maxContextTokens: Int = 0,
    val instructionTokens: Int = 0,
    val systemMessageTokens: Int = 0,
    val dynamicInstructionTokens: Int = 0,
    val toolSchemaTokens: Int = 0,
    val summaryTokens: Int = 0,
    val toolCount: Int = 0,
    val messageCount: Int = 0,
    val messageTokens: Int = 0,
    val availableForMessages: Int = 0,
)

/**
 * Context-window usage snapshot for the current branch/config (v0.8.7). Carried
 * by the `on_context_usage` SSE event and returned by the context-projection
 * endpoint. Mirrors upstream `TContextUsageEvent`.
 */
@Serializable
data class ContextUsage(
    val breakdown: TokenBudgetBreakdown = TokenBudgetBreakdown(),
    /** Usable budget this call: maxContextTokens minus output reserve. */
    val contextBudget: Int? = null,
    val effectiveInstructionTokens: Int? = null,
    val prePruneContextTokens: Int? = null,
    /** Tokens still free after instructions + pruned messages. */
    val remainingContextTokens: Int? = null,
    val calibrationRatio: Double? = null,
    val runId: String? = null,
    val agentId: String? = null,
) {
    /** The context window size for this call. */
    val maxContextTokens: Int get() = breakdown.maxContextTokens

    /**
     * Tokens consumed of the window. Prefers the server's [remainingContextTokens]
     * (window minus remaining); otherwise sums the breakdown's components.
     */
    val usedTokens: Int
        get() = remainingContextTokens
            ?.let { (maxContextTokens - it).coerceAtLeast(0) }
            ?: (
                breakdown.instructionTokens +
                    breakdown.systemMessageTokens +
                    breakdown.toolSchemaTokens +
                    breakdown.summaryTokens +
                    breakdown.messageTokens
                )

    /** Fraction of the window used, clamped to 0..1. Zero when the window is unknown. */
    val usedFraction: Float
        get() = if (maxContextTokens > 0) {
            (usedTokens.toFloat() / maxContextTokens.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Provider-reported usage for a single completed model call (v0.8.7
 * `on_token_usage`). Mirrors upstream `TTokenUsageEvent` (snake_case wire keys).
 */
@Serializable
data class TokenUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    val model: String? = null,
    val provider: String? = null,
)

/** Per-model context window + (optional) pricing. Mirrors upstream `TModelTokenomics`. */
@Serializable
data class ModelTokenomics(
    val context: Int? = null,
    val prompt: Double? = null,
    val completion: Double? = null,
    val cacheWrite: Double? = null,
    val cacheRead: Double? = null,
)
