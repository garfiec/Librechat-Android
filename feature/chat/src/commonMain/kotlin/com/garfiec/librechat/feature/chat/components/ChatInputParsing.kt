package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData

/**
 * Extracts the slash-command query from the composer text.
 *
 * Returns the text after a leading `/`, or null when the picker should not be open. Anchored to the
 * start of the input so slashes inside ordinary prose — dates, paths, fractions — never trigger it;
 * a space closes the picker, which is how the user dismisses it without clearing what they typed.
 */
fun parseSlashQuery(text: String): String? {
    if (!text.startsWith("/")) return null
    val afterSlash = text.substring(1)
    return if (!afterSlash.contains(' ')) afterSlash else null
}

/**
 * The label the query is matched against, mirroring the web client's composed option label so both
 * clients rank the same prompt the same way. The body stands in for a missing oneliner, which is
 * what lets a query match a prompt whose title never mentions it.
 */
private fun PromptMentionDisplayData.searchLabel(): String {
    val description = oneliner?.takeIf { it.isNotBlank() } ?: promptText.orEmpty()
    return if (description.isBlank()) name else "$name: $description"
}

private const val MAX_SUGGESTIONS = 8

/**
 * Ranks [prompts] against a slash-command [query], best first.
 *
 * Matching deliberately does not require a `command`: the server's list projection omits that field
 * entirely, so filtering on it yields an empty picker no matter what the user's library contains.
 *
 * The second key falls back to the name (web's `command ?? name`) rather than matching on `command`
 * alone. Since `command` is always absent, a `command`-only key would never rank anything, and the
 * label key tops out at `STARTS_WITH` because the name is a prefix of `"name: description"` — so
 * typing a prompt's exact name would not float it above a prompt that merely starts with it.
 */
fun filterMatchingSlashCommands(
    query: String,
    prompts: List<PromptMentionDisplayData>,
): List<PromptMentionDisplayData> {
    if (prompts.isEmpty()) return emptyList()
    return matchSorter(
        items = prompts,
        query = query,
        keys = listOf({ it.searchLabel() }, { it.command ?: it.name }),
    ).take(MAX_SUGGESTIONS)
}
