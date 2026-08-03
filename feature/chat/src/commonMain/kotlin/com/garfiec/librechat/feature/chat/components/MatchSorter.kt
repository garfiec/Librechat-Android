package com.garfiec.librechat.feature.chat.components

/**
 * Match tiers, highest first. A query is scored against each candidate string and the best tier
 * wins; [MATCHES] is the floor for a fuzzy subsequence hit and is returned as a fractional value
 * between 1.0 and 2.0 so closer matches outrank looser ones within the tier.
 */
object MatchRanking {
    const val CASE_SENSITIVE_EQUAL = 7.0
    const val EQUAL = 6.0
    const val STARTS_WITH = 5.0
    const val WORD_STARTS_WITH = 4.0
    const val CONTAINS = 3.0
    const val ACRONYM = 2.0
    const val MATCHES = 1.0
    const val NO_MATCH = 0.0
}

/**
 * Accented Latin letters folded to their base form, so "resume" matches "Résumé".
 *
 * Upstream gets this from a Unicode NFD normalise + combining-mark strip, which `commonMain` has no
 * equivalent for. This table covers Latin-1 Supplement and Latin Extended-A — the range real prompt
 * names use. Characters outside it are compared unfolded rather than being dropped.
 */
private const val ACCENTED_CHARS =
    "àáâãäåòóôõöøèéêëçðìíîïùúûüñšÿýžÀÁÂÃÄÅÒÓÔÕÖØÈÉÊËÇÐÌÍÎÏÙÚÛÜÑŠŸÝŽ"
private const val FOLDED_CHARS =
    "aaaaaaooooooeeeecdiiiiuuuunsyyzAAAAAAOOOOOOEEEECDIIIIUUUUNSYYZ"

private fun Char.foldDiacritic(): Char {
    val index = ACCENTED_CHARS.indexOf(this)
    return if (index >= 0) FOLDED_CHARS[index] else this
}

private fun String.prepareForComparison(): String = map { it.foldDiacritic() }.joinToString("")

/**
 * First letter of each space- and hyphen-delimited word, e.g. "Match Teaching-Assistant" -> "mta".
 */
private fun acronymOf(value: String): String = buildString {
    value.split(' ').forEach { word ->
        word.split('-').forEach { part ->
            part.firstOrNull()?.let { append(it) }
        }
    }
}

/**
 * Fuzzy subsequence score in `[MATCHES, MATCHES + 1)`. Every character of [query] must appear in
 * [candidate] in order; the score rewards matching a high proportion of the query across a small
 * span, so a tight run of characters beats the same characters scattered across the string.
 */
private fun closenessRanking(candidate: String, query: String): Double {
    var matchingInOrderCharCount = 0

    fun findMatchingCharacter(matchChar: Char, from: Int): Int {
        for (j in from until candidate.length) {
            if (candidate[j] == matchChar) {
                matchingInOrderCharCount++
                return j + 1
            }
        }
        return -1
    }

    val firstIndex = findMatchingCharacter(query[0], 0)
    if (firstIndex < 0) return MatchRanking.NO_MATCH

    var charNumber = firstIndex
    for (i in 1 until query.length) {
        charNumber = findMatchingCharacter(query[i], charNumber)
        if (charNumber < 0) return MatchRanking.NO_MATCH
    }

    val spread = charNumber - firstIndex
    val spreadPercentage = 1.0 / spread
    val inOrderPercentage = matchingInOrderCharCount.toDouble() / query.length
    return MatchRanking.MATCHES + inOrderPercentage * spreadPercentage
}

/** Scores [query] against [candidate], returning the best tier from [MatchRanking]. */
fun matchRanking(candidate: String, query: String): Double {
    val preparedCandidate = candidate.prepareForComparison()
    val preparedQuery = query.prepareForComparison()

    if (preparedQuery.length > preparedCandidate.length) return MatchRanking.NO_MATCH
    if (preparedCandidate == preparedQuery) return MatchRanking.CASE_SENSITIVE_EQUAL

    val lowerCandidate = preparedCandidate.lowercase()
    val lowerQuery = preparedQuery.lowercase()

    if (lowerCandidate == lowerQuery) return MatchRanking.EQUAL
    if (lowerCandidate.startsWith(lowerQuery)) return MatchRanking.STARTS_WITH
    if (lowerCandidate.contains(" $lowerQuery")) return MatchRanking.WORD_STARTS_WITH
    if (lowerCandidate.contains(lowerQuery)) return MatchRanking.CONTAINS

    // A single character that isn't even contained can't match by acronym or subsequence either.
    if (lowerQuery.length == 1) return MatchRanking.NO_MATCH

    if (acronymOf(lowerCandidate).contains(lowerQuery)) return MatchRanking.ACRONYM

    return closenessRanking(lowerCandidate, lowerQuery)
}

private data class Ranked<T>(
    val item: T,
    val rank: Double,
    val keyIndex: Int,
    val rankedValue: String,
)

/**
 * Ranks [items] against [query], keeping only those scoring at or above [threshold] and returning
 * them best-first. Ports the `match-sorter` ranking the web client uses for its `/` prompt picker,
 * so the same query surfaces the same prompt in the same position on both clients.
 *
 * Each item is scored against every key and keeps its best hit. Ties break first on which key
 * matched — earlier keys win, so a name hit outranks an equally-ranked body hit — and then on the
 * matched text itself, so equal results hold a stable order rather than drifting between renders.
 *
 * An empty [query] returns [items] unchanged and unranked, matching the web client: the picker opens
 * showing the library in its server-provided order rather than an arbitrary alphabetisation.
 */
fun <T> matchSorter(
    items: List<T>,
    query: String,
    keys: List<(T) -> String?>,
    threshold: Double = MatchRanking.MATCHES,
): List<T> {
    if (query.isEmpty()) return items

    return items.mapNotNull { item ->
        var best = Ranked(item, MatchRanking.NO_MATCH, -1, "")
        keys.forEachIndexed { keyIndex, selector ->
            val value = selector(item) ?: return@forEachIndexed
            val rank = matchRanking(value, query)
            if (rank > best.rank) {
                best = Ranked(item, rank, keyIndex, value)
            }
        }
        best.takeIf { it.rank >= threshold && it.rank > MatchRanking.NO_MATCH }
    }
        .sortedWith(
            compareByDescending<Ranked<T>> { it.rank }
                .thenBy { it.keyIndex }
                .thenBy { it.rankedValue },
        )
        .map { it.item }
}
