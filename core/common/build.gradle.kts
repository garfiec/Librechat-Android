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
