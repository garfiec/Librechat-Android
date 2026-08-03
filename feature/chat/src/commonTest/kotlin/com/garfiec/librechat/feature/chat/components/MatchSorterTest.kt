package com.garfiec.librechat.feature.chat.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ranking tiers and their ordering against the web client's `match-sorter`.
 *
 * Ranking bugs don't announce themselves — a mis-ported tier just puts the right prompt slightly too
 * far down the list — so the tiers are asserted by value rather than by observed sort order alone.
 */
class MatchSorterTest {

    // ── Tier assignment ──────────────────────────────────────────────────

    @Test
    fun exactMatchIsCaseSensitiveEqual() {
        assertEquals(MatchRanking.CASE_SENSITIVE_EQUAL, matchRanking("Summarize", "Summarize"))
    }

    @Test
    fun caseDifferenceDropsToEqual() {
        assertEquals(MatchRanking.EQUAL, matchRanking("Summarize", "summarize"))
    }

    @Test
    fun prefixIsStartsWith() {
        assertEquals(MatchRanking.STARTS_WITH, matchRanking("Summarize Question", "summ"))
    }

    @Test
    fun laterWordPrefixIsWordStartsWith() {
        assertEquals(MatchRanking.WORD_STARTS_WITH, matchRanking("Summarize Question", "ques"))
    }

    @Test
    fun midWordSubstringIsContains() {
        assertEquals(MatchRanking.CONTAINS, matchRanking("Summarize Question", "uestio"))
    }

    @Test
    fun initialsAreAcronym() {
        assertEquals(MatchRanking.ACRONYM, matchRanking("Complaint Activity Log", "cal"))
    }

    @Test
    fun hyphensSplitWordsForAcronym() {
        assertEquals(MatchRanking.ACRONYM, matchRanking("Math Teaching-Assistant", "mta"))
    }

    @Test
    fun scatteredCharactersAreFuzzy() {
        val rank = matchRanking("Complaint Activity Log", "cmpt")
        assertTrue(
            rank >= MatchRanking.MATCHES && rank < MatchRanking.ACRONYM,
            "expected a fuzzy tier score in [1,2), got $rank",
        )
    }

    @Test
    fun unmatchedCharacterIsNoMatch() {
        assertEquals(MatchRanking.NO_MATCH, matchRanking("Summarize", "xyz"))
    }

    @Test
    fun queryLongerThanCandidateIsNoMatch() {
        assertEquals(MatchRanking.NO_MATCH, matchRanking("Sum", "Summarize"))
    }

    @Test
    fun singleUncontainedCharacterIsNoMatchRatherThanFuzzy() {
        // Guards the short-circuit: without it a lone character would fall through to the fuzzy tier
        // and match almost everything.
        assertEquals(MatchRanking.NO_MATCH, matchRanking("Summarize", "q"))
    }

    @Test
    fun singleContainedCharacterStillMatches() {
        assertEquals(MatchRanking.CONTAINS, matchRanking("Summarize", "z"))
    }

    @Test
    fun diacriticsFoldToBaseLetters() {
        assertEquals(MatchRanking.EQUAL, matchRanking("Résumé", "resume"))
        assertEquals(MatchRanking.STARTS_WITH, matchRanking("Über Alles", "uber"))
        assertEquals(MatchRanking.WORD_STARTS_WITH, matchRanking("Alles Über", "uber"))
    }

    @Test
    fun tighterFuzzyMatchOutranksLooserOne() {
        val tight = matchRanking("abcdef", "abc")
        val loose = matchRanking("axxxbxxxc", "abc")
        assertTrue(tight > loose, "tight=$tight should outrank loose=$loose")
    }

    // ── Ordering ─────────────────────────────────────────────────────────

    private data class Group(val name: String, val body: String? = null)

    private val keys = listOf<(Group) -> String?>({ it.name }, { it.body })

    @Test
    fun resultsAreOrderedByTier() {
        val items = listOf(
            Group("Quick Summary"), // word-starts-with
            Group("Presummed"),     // contains
            Group("Summ"),          // equal
            Group("Summarize"),     // starts-with
        )
        assertEquals(
            listOf("Summ", "Summarize", "Quick Summary", "Presummed"),
            matchSorter(items, "summ", keys).map { it.name },
        )
    }

    @Test
    fun equalRanksBreakTieOnTheMatchedText() {
        // Both start with the query; a stable order beats one that drifts between recompositions.
        val items = listOf(Group("Summarize Question"), Group("Summarize"))
        assertEquals(
            listOf("Summarize", "Summarize Question"),
            matchSorter(items, "summ", keys).map { it.name },
        )
    }

    @Test
    fun earlierKeyWinsWhenRanksTie() {
        val nameHit = Group(name = "Alpha", body = "irrelevant")
        val bodyHit = Group(name = "irrelevant", body = "Alpha")
        val result = matchSorter(listOf(bodyHit, nameHit), "alpha", keys)
        assertEquals(listOf("Alpha", "irrelevant"), result.map { it.name })
    }

    @Test
    fun nonMatchesAreDropped() {
        val items = listOf(Group("Summarize"), Group("Translate"))
        assertEquals(listOf("Summarize"), matchSorter(items, "summ", keys).map { it.name })
    }

    @Test
    fun emptyQueryReturnsEverythingInSourceOrder() {
        // The picker opens showing the library as the server ordered it (most-used first), not
        // alphabetised — so an empty query must not sort.
        val items = listOf(Group("Zebra"), Group("Apple"), Group("Mango"))
        assertEquals(listOf("Zebra", "Apple", "Mango"), matchSorter(items, "", keys).map { it.name })
    }

    @Test
    fun nullKeyValuesAreSkipped() {
        val items = listOf(Group("Summarize", body = null))
        assertEquals(listOf("Summarize"), matchSorter(items, "summ", keys).map { it.name })
    }

    @Test
    fun matchingOnBodyAloneStillSurfacesTheGroup() {
        // Web's label embeds the prompt body, so a query can legitimately hit text the row's title
        // never shows.
        val items = listOf(Group("Untitled", body = "rewrite this as a haiku"))
        assertEquals(listOf("Untitled"), matchSorter(items, "haiku", keys).map { it.name })
    }
}
