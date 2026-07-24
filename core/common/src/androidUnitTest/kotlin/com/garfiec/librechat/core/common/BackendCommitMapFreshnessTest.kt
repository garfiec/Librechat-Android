package com.garfiec.librechat.core.common

import com.garfiec.librechat.core.common.generated.BackendCommitMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Freshness guard (JVM-only): the commit pinned in the root `UPSTREAM_VERSION` must be present in the
 * baked [BackendCommitMap] AND be consistent with the declared target. This fails PR CI if someone
 * bumped the upstream submodule without re-running `./gradlew generateBackendCommitMap`, or if a
 * regeneration mis-mapped the pinned commit (e.g. a degraded/partial submodule) — no git needed at
 * test time, just the committed table + the pin files. Fails LOUDLY (not vacuously) if it can't locate
 * or read the pin files, so a misconfigured runner surfaces as a red test rather than a silent pass.
 *
 * Mode-aware (see version.properties): for a full release/rc sync the pinned commit is the tag
 * commit and must resolve to exactly the tag's version. For a PARTIAL sync (backendTargetVersion of
 * the form `X.Y.Z+dev.<sha8>`) the pinned commit is untagged: it must still be present in the map,
 * the `<sha8>` must be a prefix of the pinned commit, and the resolved version is only required to
 * match the declared base `X.Y.Z` — the tag= line is just the anchor and may legitimately differ
 * from the resolved version in the window between upstream's rc version bump and the rc tag.
 */
class BackendCommitMapFreshnessTest {

    @Test
    fun pinnedUpstreamCommitResolvesConsistently() {
        val upstreamVersion = locateRepoFile("UPSTREAM_VERSION")
            ?: fail("Could not locate UPSTREAM_VERSION within $MAX_PARENT_WALK parents of ${System.getProperty("user.dir")}")
        val lines = upstreamVersion.readLines()
        val commit = lines.firstOrNull { it.startsWith("commit=") }
            ?.substringAfter("commit=")?.trim()?.takeIf { it.isNotEmpty() }
            ?: fail("UPSTREAM_VERSION at ${upstreamVersion.path} has no non-empty commit= line")

        val resolved = BackendCommitMap.versionForCommit(commit)
        assertNotNull(
            resolved,
            "UPSTREAM_VERSION commit=$commit is not in BackendCommitMap — " +
                "run ./gradlew generateBackendCommitMap and commit the result.",
        )

        val target = locateRepoFile("version.properties")?.readLines()
            ?.firstOrNull { it.startsWith("backendTargetVersion=") }
            ?.substringAfter("=")?.trim()?.takeIf { it.isNotEmpty() }
            ?: fail("Could not read backendTargetVersion from version.properties next to UPSTREAM_VERSION")

        val devSha = target.substringAfter("+dev.", missingDelimiterValue = "")
        if (devSha.isNotEmpty()) {
            // Partial sync: the +dev sha must pin the same commit, and the resolved version must
            // match the declared base line (tag= is only the anchor, not the resolved version).
            assertTrue(
                commit.lowercase().startsWith(devSha.lowercase()),
                "backendTargetVersion \"$target\" pins +dev.$devSha but UPSTREAM_VERSION commit=$commit " +
                    "does not start with it — the two pin files disagree.",
            )
            val base = target.substringBefore('+')
            assertEquals(
                base,
                resolved,
                "Partial-sync pinned commit $commit resolves to \"$resolved\" but backendTargetVersion " +
                    "declares base \"$base\" — one of the two is stale; re-run generateBackendCommitMap " +
                    "or fix version.properties.",
            )
        } else {
            // Full release/rc sync: the pinned commit is the tag commit, so it must resolve to the
            // version its tag implies (e.g. tag=v0.8.7 → "0.8.7"). Catches a degraded/mis-generated
            // table that still happens to contain the commit but maps it to the wrong version.
            val expected = lines.firstOrNull { it.startsWith("tag=") }
                ?.substringAfter("tag=")?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
            if (expected != null) {
                assertEquals(
                    expected,
                    resolved,
                    "Pinned commit $commit resolves to \"$resolved\" but UPSTREAM_VERSION tag implies " +
                        "\"$expected\" — BackendCommitMap may be mis-generated; re-run generateBackendCommitMap.",
                )
            }
        }
    }

    private fun locateRepoFile(name: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null)
        repeat(MAX_PARENT_WALK) {
            val candidate = dir?.resolve(name)
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val MAX_PARENT_WALK = 8
    }
}
