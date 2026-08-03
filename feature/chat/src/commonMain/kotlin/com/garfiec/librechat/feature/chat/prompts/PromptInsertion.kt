package com.garfiec.librechat.feature.chat.prompts

import com.garfiec.librechat.core.model.PromptGroup

/**
 * Matches `{{name}}` with optional surrounding whitespace. Deliberately `[^{}]+?` rather than `\w+`,
 * mirroring the web client's `detectVariables`, which accepts spaces, hyphens and dots inside the
 * braces — so a prompt authored there can carry variable names a `\w+` pattern never sees.
 *
 * Both closing braces MUST stay escaped. A bare `}}` parses on the JVM but is rejected by Android's
 * ICU-backed engine with a `PatternSyntaxException` at class-init, surfacing as an
 * `ExceptionInInitializerError` that takes down the chat screen for any account with a saved prompt.
 */
private val VARIABLE_PATTERN = Regex("""\{\{([^{}]+?)\}\}""")

/**
 * Variables the server substitutes on its own (`replaceSpecialVars`). They must never be presented
 * as blanks for the user to fill — they are resolved per-request against the caller's clock and
 * identity, and overwriting them here would freeze the value at insertion time.
 */
private val SPECIAL_VARIABLES = setOf(
    "current_date",
    "current_user",
    "iso_datetime",
    "current_datetime",
)

private fun String.isSpecialVariable(): Boolean = trim().lowercase() in SPECIAL_VARIABLES

/**
 * Unique user-fillable variable names in [template], in first-appearance order.
 * Server-substituted specials are excluded — see [SPECIAL_VARIABLES].
 */
fun extractPromptVariables(template: String): List<String> =
    VARIABLE_PATTERN.findAll(template)
        .map { it.groupValues[1].trim() }
        .filterNot { it.isSpecialVariable() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/** True when [template] has at least one variable the user is expected to fill in. */
fun hasFillableVariables(template: String): Boolean = extractPromptVariables(template).isNotEmpty()

/**
 * Replaces each `{{name}}` in [template] with its entry from [values], matched case-insensitively on
 * the trimmed name. A missing or blank value leaves the placeholder in place rather than blanking
 * it, so a half-filled preview still reads as a template. Specials are left untouched for the server.
 */
fun substitutePromptVariables(template: String, values: Map<String, String>): String {
    if (values.isEmpty()) return template
    val byLowerName = values.mapKeys { (key, _) -> key.trim().lowercase() }
    return VARIABLE_PATTERN.replace(template) { match ->
        val name = match.groupValues[1].trim()
        if (name.isSpecialVariable()) {
            match.value
        } else {
            byLowerName[name.lowercase()]?.takeIf { it.isNotBlank() } ?: match.value
        }
    }
}

/**
 * The group's production prompt text, or null when the group carries no prompt at all.
 *
 * The two endpoints populate different fields: the list routes attach `productionPrompt` via a
 * `$lookup` and omit `prompts` entirely, while the detail route returns the full `prompts` array.
 * Checking `productionPrompt` first is what makes this work for a group that came from either.
 */
fun resolvePromptText(group: PromptGroup): String? =
    group.productionPrompt?.prompt
        ?: group.prompts.firstOrNull { it.id == group.productionId }?.prompt
        ?: group.prompts.firstOrNull()?.prompt

/** What a caller should do with a selected prompt group. */
sealed interface PromptInsertion {
    /** Text is ready to drop into the composer as-is. */
    data class Ready(val text: String) : PromptInsertion

    /** [template] needs [variables] filled in before it can be inserted. */
    data class NeedsVariables(val template: String, val variables: List<String>) : PromptInsertion
}

/**
 * Resolves [group] into an insertion outcome, or null when the group has no prompt text to insert
 * (nothing should be written to the composer in that case).
 *
 * Single source of truth for turning a [PromptGroup] into composer text: the `/` picker, "Use in
 * chat" from the library, and the detail screen all go through here. A per-call-site copy that
 * reads only `prompts` — never populated by the list endpoints — falls through to inserting the
 * group's command word instead of the prompt.
 */
fun resolvePromptInsertion(group: PromptGroup): PromptInsertion? {
    val text = resolvePromptText(group)?.takeIf { it.isNotBlank() } ?: return null
    val variables = extractPromptVariables(text)
    return if (variables.isEmpty()) {
        PromptInsertion.Ready(text)
    } else {
        PromptInsertion.NeedsVariables(text, variables)
    }
}
