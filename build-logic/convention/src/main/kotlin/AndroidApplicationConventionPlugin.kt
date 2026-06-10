import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.util.Properties

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("librechat.detekt")
            pluginManager.apply("org.jetbrains.kotlinx.kover")

            val appVersion = readAppVersion(target)
            val release = readReleaseSigning(target)

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(BuildConstants.JVM_TOOLCHAIN_VERSION)
                compilerOptions {
                    freeCompilerArgs.addAll("-opt-in=kotlin.time.ExperimentalTime")
                }
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = BuildConstants.COMPILE_SDK
                defaultConfig {
                    minSdk = BuildConstants.MIN_SDK
                    targetSdk = BuildConstants.TARGET_SDK
                    versionCode = appVersion.code
                    versionName = appVersion.name
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    isCoreLibraryDesugaringEnabled = true
                }
                lint {
                    disable += "NullSafeMutableLiveData"
                }
                if (release != null) {
                    signingConfigs {
                        create("release") {
                            storeFile = file(release.storeFile)
                            storePassword = release.storePassword
                            keyAlias = release.keyAlias
                            keyPassword = release.keyPassword
                            enableV1Signing = true
                            enableV2Signing = true
                            enableV3Signing = true
                        }
                    }
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        // Strip unused resources alongside the code shrink. Requires minify.
                        isShrinkResources = true
                        // Use the real release key when credentials are present (CI release
                        // builds, or a local keystore.properties); otherwise fall back to the
                        // debug key so local `assembleRelease` and CI checks still work.
                        signingConfig = if (release != null) {
                            signingConfigs.getByName("release")
                        } else {
                            signingConfigs.getByName("debug")
                        }
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                }
            }

            dependencies.add("coreLibraryDesugaring", libs.findLibrary("desugar-jdk").get())
            dependencies.add("testImplementation", libs.findBundle("testing").get())
        }
    }
}

private data class AppVersion(val name: String, val code: Int)

/**
 * Reads `versionName` (calver `YYYY.MM.PATCH`) from version.properties and derives a
 * monotonic `versionCode` from it as `YEAR * 10_000 + MONTH * 100 + PATCH`, so the
 * code is a deterministic function of the name with nothing to track separately:
 * `2026.06.0` -> 20260600, `2026.06.1` -> 20260601. MONTH and PATCH must each be
 * <= 99 to stay monotonic; the build fails fast if either overflows. Pre-calver
 * semver releases used the same packing (`0.1.3` -> 103), so codes stayed monotonic
 * across the cutover.
 *
 * Pre-release suffixes (`-rc1`) are stripped, so `2026.06.1-rc1` and `2026.06.1` map
 * to the same code — fine for tagged stable releases; bump the patch if you ship an
 * RC users actually update from.
 */
private fun readAppVersion(target: Project): AppVersion {
    val props = Properties().apply {
        val file = target.rootProject.file("version.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val name = props.getProperty("versionName") ?: "0.0.0"
    val (year, month, patch) = name.substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
        .plus(listOf(0, 0, 0))
        .take(3)
    // The packing only stays monotonic while MONTH and PATCH each fit in two digits;
    // e.g. 2026.06.100 would collide with 2026.07.0 (both -> 20260700). MONTH never
    // exceeds 12, so in practice only PATCH can overflow. Fail the build (and
    // therefore CI) rather than silently ship a non-monotonic versionCode.
    check(month in 0..99 && patch in 0..99) {
        "versionName '$name' exceeds the YYYYMMPP versionCode scheme: MONTH and PATCH " +
            "must each be <= 99 (got month=$month, patch=$patch)."
    }
    return AppVersion(name = name, code = year * 10_000 + month * 100 + patch)
}

private data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Resolves release signing credentials from environment variables (CI) first, then a
 * local `keystore.properties` at the repo root. Returns null when any field is missing,
 * which signals the caller to fall back to debug signing — keeping the keystore out of
 * the repo while letting local `assembleRelease` and CI lint/test checks run unsigned.
 */
private fun readReleaseSigning(target: Project): ReleaseSigning? {
    val propsFile = target.rootProject.file("keystore.properties")
    val props = Properties().apply {
        if (propsFile.exists()) propsFile.inputStream().use { load(it) }
    }
    fun value(env: String, prop: String): String? =
        System.getenv(env) ?: props.getProperty(prop)

    val storeFile = value("SIGNING_STORE_FILE", "storeFile")
    val storePassword = value("SIGNING_STORE_PASSWORD", "storePassword")
    val keyAlias = value("SIGNING_KEY_ALIAS", "keyAlias")
    val keyPassword = value("SIGNING_KEY_PASSWORD", "keyPassword")

    return if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
        ReleaseSigning(storeFile, storePassword, keyAlias, keyPassword)
    } else {
        null
    }
}

private val Project.libs
    get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")
