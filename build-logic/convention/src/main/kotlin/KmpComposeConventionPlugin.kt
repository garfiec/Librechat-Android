import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // Apply the shared stability config to every target (JVM, Android, iOS). The Android
            // convention plugin wires this via KotlinCompile freeCompilerArgs, which only reaches
            // JVM/Android compilations — KMP modules need the compose-compiler extension to also
            // cover native, so commonMain composables get the configured stable types.
            extensions.configure<ComposeCompilerGradlePluginExtension> {
                val stabilityConfig = rootProject.layout.projectDirectory.file("compose-stability.conf")
                if (stabilityConfig.asFile.exists()) {
                    stabilityConfigurationFiles.add(stabilityConfig)
                }
                if (providers.gradleProperty("enableComposeCompilerReports").orNull == "true") {
                    val reportsDir = layout.buildDirectory.dir("compose_metrics")
                    reportsDestination.set(reportsDir)
                    metricsDestination.set(reportsDir)
                }
            }

            val composeExtension = extensions.getByType<ComposeExtension>()
            val compose = composeExtension.dependencies

            // Override Compose Resources' default package. ResourcesExtension is a
            // sub-extension of ComposeExtension — `extensions.configure<ResourcesExtension>`
            // at project level throws "extension does not exist".
            composeExtension.extensions.configure<ResourcesExtension> {
                val modulePath = project.path.removePrefix(":").replace(":", ".")
                packageOfResClass = "com.garfiec.librechat.$modulePath.resources"
            }

            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.commonMain.dependencies {
                    implementation(compose.runtime)
                    implementation(compose.foundation)
                    implementation(compose.material3)
                    implementation(compose.materialIconsExtended)
                    implementation(compose.ui)
                    implementation(compose.animation)
                    @Suppress("DEPRECATION")
                    implementation(compose.components.resources)
                    @Suppress("DEPRECATION")
                    implementation(compose.components.uiToolingPreview)
                }
            }
        }
    }
}
