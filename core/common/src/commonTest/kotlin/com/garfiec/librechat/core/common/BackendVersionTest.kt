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
    fun parseStripsPrereleaseSuffix() {
        // Pre-fix bug: patch token "6-rc1" failed toIntOrNull and fell back to 0,
        // parsing "0.8.6-rc1" as 0.8.0 and silently downgrading every version gate.
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("0.8.6-rc1"))
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("v0.8.6-rc.1"))
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("0.8.6"))
    }

    @Test
    fun parseStripsBuildMetadataSuffix() {
        assertEquals(BackendVersion.SemanticVersion(0, 8, 6), BackendVersion.parse("0.8.6+build.5"))
    }

    @Test
    fun parsePrereleaseDoesNotDowngradeGate() {
        // The motivating end-to-end case: a prerelease server footer must not defeat the gate.
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.6-rc1", "0.8.6"))
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
}
