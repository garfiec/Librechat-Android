package com.garfiec.librechat.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendVersionTest {

    // region parse

    @Test
    fun parseAcceptsFullSemver() {
        val v = BackendVersion.parse("0.8.5")
        assertEquals(BackendVersion.SemanticVersion(0, 8, 5), v)
    }

    @Test
    fun parseAcceptsLeadingV() {
        assertEquals(BackendVersion.SemanticVersion(1, 2, 3), BackendVersion.parse("v1.2.3"))
        assertEquals(BackendVersion.SemanticVersion(1, 2, 3), BackendVersion.parse("V1.2.3"))
    }

    @Test
    fun parseDefaultsPatchToZero() {
        assertEquals(BackendVersion.SemanticVersion(0, 8, 0), BackendVersion.parse("0.8"))
    }

    @Test
    fun parseRetainsPrereleaseSuffix() {
        // Historical bug guard: patch token "6-rc1" once failed toIntOrNull and fell back to 0,
        // parsing "0.8.6-rc1" as 0.8.0 and silently downgrading every version gate. The triple
        // must parse correctly; the prerelease is now retained for rc-level ordering.
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6, "rc1"), BackendVersion.parse("0.8.6-rc1"))
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6, "rc.1"), BackendVersion.parse("v0.8.6-rc.1"))
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("0.8.6"))
    }

    @Test
    fun parseStripsBuildMetadataSuffix() {
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("0.8.6+build.5"))
        // Partial-sync target form: base version + pinned commit metadata.
        assertEquals(BackendVersion.SemanticVersion(0, 8, 7), BackendVersion.parse("0.8.7+dev.9f8e7d6c"))
    }

    @Test
    fun parsePrereleaseDoesNotDowngradeGate() {
        // The motivating end-to-end case: a prerelease server footer must not defeat the gate
        // against anything at or below its own position in the release line.
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.6-rc1", "0.8.5"))
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.6-rc1", "0.8.6-rc1"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(BackendVersion.parse("garbage"))
        assertNull(BackendVersion.parse("1"))
        assertNull(BackendVersion.parse("1.x.0"))
        assertNull(BackendVersion.parse(""))
    }

    // endregion

    // region isCompatible

    @Test
    fun isCompatibleExactMatch() {
        assertTrue(BackendVersion.isCompatible("0.8.5", "0.8.5"))
    }

    @Test
    fun isCompatibleRejectsPatchMismatch() {
        // The reason this function exists in its tightened form: upstream ships
        // breaking changes inside the same minor (0.8.4 → 0.8.5 changed SSE shape).
        assertFalse(BackendVersion.isCompatible("0.8.5", "0.8.4"))
        assertFalse(BackendVersion.isCompatible("0.8.5", "0.8.6"))
    }

    @Test
    fun isCompatibleRejectsMinorMismatch() {
        assertFalse(BackendVersion.isCompatible("0.8.5", "0.9.0"))
        assertFalse(BackendVersion.isCompatible("0.8.5", "0.7.0"))
    }

    @Test
    fun isCompatibleRejectsMajorMismatch() {
        assertFalse(BackendVersion.isCompatible("0.8.5", "1.0.0"))
    }

    @Test
    fun isCompatibleFailsOpenOnUnparseableInput() {
        assertTrue(BackendVersion.isCompatible("garbage", "0.8.5"))
        assertTrue(BackendVersion.isCompatible("0.8.5", "garbage"))
    }

    // endregion

    // region isCompatibleOrNewer

    @Test
    fun isCompatibleOrNewerExactMatchPasses() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.5", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerRejectsLowerPatch() {
        // Pre-fix bug: this returned true because patch was ignored, silently
        // activating 0.8.5-only features on a 0.8.4 server.
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.4", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerAcceptsHigherPatch() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.6", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerAcceptsHigherMinor() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.9.0", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerRejectsLowerMinor() {
        assertFalse(BackendVersion.isCompatibleOrNewer("0.7.99", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerAcceptsHigherMajor() {
        assertTrue(BackendVersion.isCompatibleOrNewer("1.0.0", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerRejectsLowerMajor() {
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.5", "1.0.0"))
    }

    @Test
    fun isCompatibleOrNewerFailsOpenOnUnparseableActual() {
        assertTrue(BackendVersion.isCompatibleOrNewer("garbage", "0.8.5"))
    }

    @Test
    fun isCompatibleOrNewerReturnsFalseOnUnparseableMinimum() {
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.5", "garbage"))
    }

    // endregion

    // region prerelease ordering (rc gates)

    @Test
    fun isCompatibleTreatsRcAndFinalOfSameLineAsCompatible() {
        // The mismatch banner keys on the release line — an rc of the targeted line is not noise.
        assertTrue(BackendVersion.isCompatible("0.8.8-rc1", "0.8.8"))
        assertTrue(BackendVersion.isCompatible("0.8.8", "0.8.8-rc2"))
        assertTrue(BackendVersion.isCompatible("0.8.8-rc1", "0.8.8-rc2"))
        assertFalse(BackendVersion.isCompatible("0.8.8-rc1", "0.8.7"))
    }

    @Test
    fun rcSortsBelowItsFinalRelease() {
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.8-rc1", "0.8.8"))
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8", "0.8.8-rc1"))
    }

    @Test
    fun rcNumbersOrderNumerically() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc2", "0.8.8-rc1"))
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.8-rc1", "0.8.8-rc2"))
        // rc10 > rc2 — numeric, not lexicographic.
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc10", "0.8.8-rc2"))
        // Separator spelling is irrelevant: "-rc.1" == "-rc1".
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc.1", "0.8.8-rc1"))
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc1", "0.8.8-rc.1"))
    }

    @Test
    fun prereleaseLabelsOrderAlphaBetaRc() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc1", "0.8.8-beta2"))
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-beta1", "0.8.8-alpha3"))
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.8-alpha1", "0.8.8-rc1"))
    }

    @Test
    fun higherPatchBeatsAnyPrereleaseOfLowerPatch() {
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.8-rc1", "0.8.7"))
        assertFalse(BackendVersion.isCompatibleOrNewer("0.8.7", "0.8.8-rc1"))
    }

    // endregion

    // region supportsFeature (partial-sync / dev-commit gates)

    private val dev0888Cycle = DetectedBackend(
        // A 0.8.8-cycle dev build: package.json still reports the previous release.
        version = "0.8.7",
        classification = BackendBuildClass.DEV,
        commitDate = "2026-07-20",
    )

    @Test
    fun supportsFeatureFailsClosedOnNullDetection() {
        assertFalse(BackendVersion.supportsFeature(null, "0.8.8-rc1", landedDate = "2026-07-01"))
    }

    @Test
    fun supportsFeaturePassesOnVersionAlone() {
        val rc = DetectedBackend("0.8.8-rc1", BackendBuildClass.RC)
        assertTrue(BackendVersion.supportsFeature(rc, "0.8.8-rc1"))
        val final = DetectedBackend("0.8.8", BackendBuildClass.OFFICIAL)
        assertTrue(BackendVersion.supportsFeature(final, "0.8.8-rc1"))
    }

    @Test
    fun supportsFeatureDevBuildPassesByLandedDate() {
        // Version gate alone fails (0.8.7 < 0.8.8-rc1), but the build commit postdates the landing.
        assertTrue(BackendVersion.supportsFeature(dev0888Cycle, "0.8.8-rc1", landedDate = "2026-07-14"))
        // Same-day landing counts as present.
        assertTrue(BackendVersion.supportsFeature(dev0888Cycle, "0.8.8-rc1", landedDate = "2026-07-20"))
    }

    @Test
    fun supportsFeatureDevBuildOlderThanLandingFails() {
        assertFalse(BackendVersion.supportsFeature(dev0888Cycle, "0.8.8-rc1", landedDate = "2026-07-21"))
    }

    @Test
    fun supportsFeatureDateFallbackIsDevOnly() {
        // A tagged OFFICIAL 0.8.7 image predates the feature no matter its commit date —
        // the date fallback must not resurrect the gate for non-DEV builds.
        val official = DetectedBackend("0.8.7", BackendBuildClass.OFFICIAL, commitDate = "2026-07-20")
        assertFalse(BackendVersion.supportsFeature(official, "0.8.8-rc1", landedDate = "2026-07-14"))
    }

    @Test
    fun supportsFeatureWithoutLandedDateIsVersionOnly() {
        assertFalse(BackendVersion.supportsFeature(dev0888Cycle, "0.8.8-rc1"))
    }

    @Test
    fun supportsFeatureDevBuildWithoutDateFailsClosed() {
        val undated = dev0888Cycle.copy(commitDate = null)
        assertFalse(BackendVersion.supportsFeature(undated, "0.8.8-rc1", landedDate = "2026-07-14"))
    }

    @Test
    fun supportsFeatureDevBuildAfterVersionBumpPassesByVersion() {
        // Post-rc-prep dev builds report the rc version — the version branch handles them.
        val postBump = DetectedBackend("0.8.8-rc1", BackendBuildClass.DEV, commitDate = "2026-07-25")
        assertTrue(BackendVersion.supportsFeature(postBump, "0.8.8-rc1", landedDate = "2026-08-01"))
    }

    // endregion
}
