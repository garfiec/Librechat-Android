package com.garfiec.librechat.feature.chat.components.artifact

/**
 * Matches the remark-directive artifact format:
 *   :::artifact{key="value" ...}
 *   ```optionalLanguage          (3 or more backticks; LibreChat's own server prompt
 *   content                       defaults to 4, bumping to 5+ if content itself
 *   ```                           contains a 4-backtick run)
 *   :::
 *
 * Group 1: attribute string inside braces
 * Group 2: the opening fence (captured so the closing fence must match the same length)
 * Group 3: optional language hint after opening backticks
 * Group 4: content between the fences
 */
private val ARTIFACT_REGEX = Regex(
    """:::artifact\{([^}]*)\}\s*(`{3,})(\w*)\n([\s\S]*?)\2\s*:::""",
)

/**
 * Parses key="value" pairs from the attribute string inside {braces}.
 */
private val ATTR_REGEX = Regex(
    """(\w+)\s*=\s*"([^"]*)"""",
)

/**
 * Detects artifact markers in message text and returns a list of segments
 * with the artifacts extracted.
 */
fun detectArtifacts(text: String): List<ArtifactSegment> {
    val segments = mutableListOf<ArtifactSegment>()
    var lastIndex = 0

    ARTIFACT_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val before = text.substring(lastIndex, match.range.first).trim()
            if (before.isNotEmpty()) {
                segments.add(ArtifactSegment.Text(before))
            }
        }

        val attrString = match.groupValues[1]
        val languageHint = match.groupValues[3].ifEmpty { null }
        val content = match.groupValues[4].trimEnd()
        val attrs = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(attrString).forEach { attrMatch ->
            attrs[attrMatch.groupValues[1]] = attrMatch.groupValues[2]
        }

        val artifact = Artifact(
            identifier = attrs["identifier"] ?: attrs["id"] ?: "artifact-${match.range.first}",
            type = attrs["type"] ?: "text/plain",
            title = attrs["title"] ?: "Artifact",
            language = languageHint ?: attrs["language"],
            content = content,
            version = attrs["version"]?.toIntOrNull() ?: 1,
        )
        segments.add(ArtifactSegment.ArtifactReference(artifact))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            segments.add(ArtifactSegment.Text(remaining))
        }
    }

    if (segments.isEmpty() && text.isNotBlank()) {
        segments.add(ArtifactSegment.Text(text))
    }

    return segments
}

/**
 * Groups artifacts by identifier and returns a map of identifier to list of
 * versioned artifacts (sorted by version number ascending).
 */
fun groupArtifactVersions(segments: List<ArtifactSegment>): Map<String, List<Artifact>> {
    return segments
        .filterIsInstance<ArtifactSegment.ArtifactReference>()
        .map { it.artifact }
        .groupBy { it.identifier }
        .mapValues { (_, artifacts) -> artifacts.sortedBy { it.version } }
}
