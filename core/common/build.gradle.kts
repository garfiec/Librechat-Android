import java.util.Properties

plugins {
    id("librechat.kmp.library")
    id("librechat.kmp.koin")
}

// Short commit the build was cut from, baked into BuildConfig so the running app can show it
// (Settings → About). Falls back to "unknown" when there's no git checkout (e.g. a source
// tarball). Only this module's BuildConfig references it, so a new commit recompiles core:common
// alone — it doesn't cascade through the module graph.
fun gitSha(): String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

// Single-source the target backend version: read `backendTargetVersion` from the root
// version.properties and emit a commonMain constant both Android and iOS compile.
// BuildConfig (how GIT_SHA is injected) is Android-only and can't reach iOS, so the
// shared BackendVersion.SUPPORTED_BACKEND_VERSION must be sourced via codegen instead.
// Config-cache-safe: the File/dir are captured at configuration time; the action never
// touches project/rootProject/providers.
val generateBackendVersion = tasks.register("generateBackendVersion") {
    val versionFile = rootProject.file("version.properties")
    val outputDir = layout.buildDirectory.dir("generated/backendVersion/commonMain/kotlin")
    inputs.file(versionFile)
    outputs.dir(outputDir)
    doLast {
        val props = Properties()
        versionFile.inputStream().use { stream -> props.load(stream) }
        val version = props.getProperty("backendTargetVersion")?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("backendTargetVersion missing or blank in version.properties")
        val pkgDir = outputDir.get().dir("com/garfiec/librechat/core/common").asFile
        pkgDir.mkdirs()
        pkgDir.resolve("BackendTargetVersion.kt").writeText(
            """
            package com.garfiec.librechat.core.common

            internal const val BACKEND_TARGET_VERSION = "$version"
            """.trimIndent() + "\n",
        )
    }
}

// Baked commit → version table. LibreChat exposes NO server version (no /api/version, no
// version field/header — see BackendVersion.kt). The one reliable signal is /api/config's
// buildInfo.commit: the git SHA the server image was built from. This task walks the
// `upstream/` submodule and generates a COMMITTED table mapping each release/rc tag commit
// (and recent untagged dev commits) to the version its package.json reports, so
// ConfigRepositoryImpl.detectVersion() can resolve a stock server (no CUSTOM_FOOTER) that
// would otherwise read "unknown". Output is committed source — normal builds just compile it;
// this task is only run on upstream sync and by the release safety net. Not wired into
// compilation on purpose (no per-build git walk). Uses ProcessBuilder (not Gradle exec) so it
// stays config-cache-safe when invoked; only File handles are captured at configuration time.
val generateBackendCommitMap = tasks.register("generateBackendCommitMap") {
    val upstreamDir = rootProject.file("upstream")
    val upstreamVersionFile = rootProject.file("UPSTREAM_VERSION")
    val outputFile = layout.projectDirectory
        .file("src/commonMain/kotlin/com/garfiec/librechat/core/common/generated/BackendCommitMap.kt")
        .asFile
    inputs.file(upstreamVersionFile)
    doLast {
        val startNanos = System.nanoTime()
        val hashPrefixLen = 12
        val devCommitCount = 1000

        if (!upstreamDir.exists()) {
            error("upstream/ submodule not found at ${upstreamDir.path}; run: git submodule update --init")
        }

        var gitCalls = 0
        fun git(vararg args: String): String {
            gitCalls++
            val proc = ProcessBuilder(listOf("git", *args)).directory(upstreamDir).start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            if (proc.waitFor() != 0) error("git ${args.joinToString(" ")} failed: $err")
            return out.trim()
        }

        val versionTagRegex = Regex("""^v?\d+\.\d+""")
        val versionLineRegex = Regex(""""version"\s*:\s*"([^"]+)"""")
        fun normalize(raw: String): String = raw.trim().trimStart('v', 'V')
        fun versionAt(commit: String): String? =
            versionLineRegex.find(git("show", "$commit:package.json"))?.groupValues?.get(1)?.let(::normalize)

        // prefix -> version, plus the subset of prefixes that are exact tag commits (classification).
        val prefixToVersion = linkedMapOf<String, String>()
        val tagPrefixes = linkedSetOf<String>()
        fun add(version: String, commit: String, isTag: Boolean) {
            val prefix = commit.take(hashPrefixLen).lowercase()
            prefixToVersion[prefix] = version // idempotent: a given commit always reports one version
            if (isTag) tagPrefixes.add(prefix)
        }

        // 1) All version tags (official + rc). One call resolves every tag→commit (annotated deref).
        git("for-each-ref", "--format=%(refname:short) %(objectname) %(*objectname)", "refs/tags")
            .lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split(' ')
                val name = parts[0]
                if (!versionTagRegex.containsMatchIn(name)) return@forEach
                val commit = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: parts[1]
                add(normalize(name), commit, isTag = true)
            }
        val tagCount = prefixToVersion.size

        // 2) Untagged dev commits: last N from the pinned HEAD, each assigned its package.json
        //    version via a single pass over version-change boundaries (a few git shows, not N).
        val pinnedHead = upstreamVersionFile.takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("commit=") }?.substringAfter("commit=")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: git("rev-parse", "HEAD")
        val revList = git("rev-list", "-n", devCommitCount.toString(), pinnedHead)
            .lineSequence().filter { it.isNotBlank() }.toList() // newest -> oldest
        var boundaryShows = 0
        if (revList.isNotEmpty()) {
            val oldest = revList.last()
            val boundaries = git("log", "--format=%H", "$oldest..$pinnedHead", "--", "package.json")
                .lineSequence().filter { it.isNotBlank() }.toSet()
            var current = versionAt(oldest).also { boundaryShows++ }
            for (commit in revList.asReversed()) { // oldest -> newest
                if (commit in boundaries) {
                    versionAt(commit)?.let { current = it }
                    boundaryShows++
                }
                current?.let { add(it, commit, isTag = false) }
            }
        }

        // Emit a flat prefix→version table as one packed string constant, parsed once into hash
        // structures at class init → O(1) lookup, and far more compact in the class file than a
        // 1000+-entry mapOf literal (which also risks the 64 KB JVM method-size limit as N grows).
        // The string constant itself is capped at 64 KB by the class-file format; at ~22 bytes/line
        // that leaves headroom to ~2800 entries before the string would need to be split.
        val sortedPrefixes = prefixToVersion.keys.sorted() // deterministic regardless of git enum order
        val body = buildString {
            appendLine("// GENERATED by ./gradlew generateBackendCommitMap — do not edit by hand.")
            appendLine("// Maps a LibreChat upstream build commit (/api/config buildInfo.commit) to the")
            appendLine("// version its package.json reports, so the app resolves a server's version")
            appendLine("// without a server-side version endpoint. Regenerated on upstream sync.")
            appendLine("@file:Suppress(\"MaxLineLength\", \"LargeClass\")")
            appendLine()
            appendLine("package com.garfiec.librechat.core.common.generated")
            appendLine()
            appendLine("/**")
            appendLine(" * Upstream build-commit prefix → reported version. Backed by one packed-string")
            appendLine(" * constant parsed once at init into a [Map] + [Set], so lookups are O(1). rc/dev")
            appendLine(" * versions keep their suffix and map to their release line at the gate via")
            appendLine(" * BackendVersion.parse(). Classification is diagnostics-only.")
            appendLine(" */")
            appendLine("object BackendCommitMap {")
            appendLine("    private const val PREFIX_LEN = $hashPrefixLen")
            appendLine()
            appendLine("    // One entry per line: \"<$hashPrefixLen-char-prefix> <version> [T]\"; 'T' = exact release/rc tag commit.")
            appendLine("    private val ENTRIES: String = \"\"\"")
            sortedPrefixes.forEach { prefix ->
                val tag = if (prefix in tagPrefixes) " T" else ""
                appendLine("$prefix ${prefixToVersion.getValue(prefix)}$tag")
            }
            appendLine("\"\"\"")
            appendLine()
            appendLine("    private val prefixToVersion: Map<String, String>")
            appendLine("    private val tagPrefixes: Set<String>")
            appendLine()
            appendLine("    init {")
            appendLine("        val versions = HashMap<String, String>(${prefixToVersion.size} * 4 / 3 + 1)")
            appendLine("        val tags = HashSet<String>(${tagPrefixes.size} * 4 / 3 + 1)")
            appendLine("        for (line in ENTRIES.lineSequence()) {")
            appendLine("            if (line.isEmpty()) continue")
            appendLine("            val parts = line.split(' ')")
            appendLine("            versions[parts[0]] = parts[1]")
            appendLine("            if (parts.size > 2) tags.add(parts[0])")
            appendLine("        }")
            appendLine("        prefixToVersion = versions")
            appendLine("        tagPrefixes = tags")
            appendLine("    }")
            appendLine()
            appendLine("    /** Reported version for a full build-commit SHA (O(1)), or null if unknown. */")
            appendLine("    fun versionForCommit(sha: String): String? {")
            appendLine("        if (sha.length < PREFIX_LEN) return null")
            appendLine("        return prefixToVersion[sha.substring(0, PREFIX_LEN).lowercase()]")
            appendLine("    }")
            appendLine()
            appendLine("    /** OFFICIAL (release tag), RC (prerelease tag), or DEV (untagged); null if unknown. */")
            appendLine("    fun classificationForCommit(sha: String): String? {")
            appendLine("        if (sha.length < PREFIX_LEN) return null")
            appendLine("        val key = sha.substring(0, PREFIX_LEN).lowercase()")
            appendLine("        val version = prefixToVersion[key]?.lowercase() ?: return null")
            appendLine("        return when {")
            appendLine("            key !in tagPrefixes -> \"DEV\"")
            appendLine("            \"-rc\" in version || \"-beta\" in version || \"-alpha\" in version -> \"RC\"")
            appendLine("            else -> \"OFFICIAL\"")
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(body)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        println(
            "generateBackendCommitMap: ${prefixToVersion.size} prefixes " +
                "($tagCount tags + ${prefixToVersion.size - tagCount} dev), " +
                "$gitCalls git calls ($boundaryShows boundary shows), ${elapsedMs}ms, " +
                "${outputFile.length() / 1024}KB -> ${outputFile.path}",
        )
    }
}

android {
    namespace = "com.garfiec.librechat.core.common"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateBackendVersion)
            dependencies {
                implementation(libs.coroutines.core)
                implementation(libs.okio)
                api(libs.kotlinx.datetime)
                // Kermit only — :core:logging depends on this module, so `Diag` is unreachable here.
                // Its PersistentLogWriter is a Kermit LogWriter, so plain Kermit still reaches the
                // diagnostic export.
                implementation(libs.kermit)
            }
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
            implementation(libs.koin.android)
        }
        named("androidUnitTest").dependencies {
            implementation(libs.koin.test)
        }
    }
}
