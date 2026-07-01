package com.garfiec.librechat.core.common

import com.garfiec.librechat.core.common.generated.BackendCommitMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the baked commit→version table resolves known upstream release commits and that the
 * resolved strings feed [BackendVersion] correctly (rc → release line via [BackendVersion.parse]).
 * The SHAs below are immutable git tag commits, so these assertions are stable across regenerations.
 */
class BackendCommitMapTest {

    private val v087 = "9e74cc0e57b395926122bd4062c1fcedc48ed465" // v0.8.7 tag commit
    private val v087rc1 = "055585f9f1f43c71b8f883a2c89e905d124d0721" // v0.8.7-rc1 tag commit
    private val v086 = "566e20b613389600a7975fd8f7c1bffbe54f06c8" // v0.8.6 tag commit

    @Test
    fun resolvesOfficialReleaseCommit() {
        assertEquals("0.8.7", BackendCommitMap.versionForCommit(v087))
        assertEquals("OFFICIAL", BackendCommitMap.classificationForCommit(v087))
    }

    @Test
    fun resolvesPriorOfficialRelease() {
        assertEquals("0.8.6", BackendCommitMap.versionForCommit(v086))
        assertEquals("OFFICIAL", BackendCommitMap.classificationForCommit(v086))
    }

    @Test
    fun resolvesReleaseCandidateAndMapsToReleaseLine() {
        assertEquals("0.8.7-rc1", BackendCommitMap.versionForCommit(v087rc1))
        assertEquals("RC", BackendCommitMap.classificationForCommit(v087rc1))
        // The gate strips the prerelease suffix, so an rc server is treated as its release line.
        assertEquals(BackendVersion.SemanticVersion(0, 8, 7), BackendVersion.parse("0.8.7-rc1"))
        assertTrue(BackendVersion.isCompatibleOrNewer("0.8.7-rc1", "0.8.7"))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals("0.8.7", BackendCommitMap.versionForCommit(v087.uppercase()))
    }

    @Test
    fun unknownCommitResolvesNull() {
        assertNull(BackendCommitMap.versionForCommit("0000000000000000000000000000000000000000"))
        assertNull(BackendCommitMap.classificationForCommit("0000000000000000000000000000000000000000"))
    }

    @Test
    fun blankCommitResolvesNull() {
        assertNull(BackendCommitMap.versionForCommit(""))
        assertNull(BackendCommitMap.versionForCommit("   "))
    }
}
