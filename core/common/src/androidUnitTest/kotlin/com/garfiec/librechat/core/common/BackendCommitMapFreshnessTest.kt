package com.garfiec.librechat.core.common

import com.garfiec.librechat.core.common.generated.BackendCommitMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Freshness guard (JVM-only): the commit pinned in the root `UPSTREAM_VERSION` must be present in the
 * baked [BackendCommitMap] AND resolve to the version its `tag=` implies. This fails PR CI if someone
 * bumped the upstream submodule without re-running `./gradlew generateBackendCommitMap`, or if a
 * regeneration mis-mapped the pinned commit (e.g. a degraded/partial submodule) — no git needed at
 * test time, just the committed table + the pin file. Fails LOUDLY (not vacuously) if it can't locate
 * or read the pin file, so a misconfigured runner surfaces as a red test rather than a silent pass.
 */
class BackendCommitMapFreshnessTest {

    @Test
    fun pinnedUpstreamCommitResolvesToItsTagVersion() {
        val upstreamVersion = locateUpstreamVersionFile()
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

        // Cross-check: the pinned commit is the release/rc tag commit, so it must resolve to the
        // version its tag implies (e.g. tag=v0.8.7 → "0.8.7"). Catches a degraded/mis-generated table
        // that still happens to contain the commit but maps it to the wrong version.
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

    private fun locateUpstreamVersionFile(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null)
        repeat(MAX_PARENT_WALK) {
            val candidate = dir?.resolve("UPSTREAM_VERSION")
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        const val MAX_PARENT_WALK = 8
    }
}
