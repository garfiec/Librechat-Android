import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            extensions.configure<DetektExtension> {
                config.setFrom(rootProject.files("config/detekt/detekt.yml"))
                buildUponDefaultConfig = true
                allRules = false
                parallel = true
            }

            tasks.withType<DetektCreateBaselineTask>().configureEach {
                enabled = false
            }

            dependencies {
                add("detektPlugins", libs.findLibrary("detekt-formatting").get())
                add("detektPlugins", libs.findLibrary("detekt-koin").get())
                add("detektPlugins", libs.findLibrary("detekt-compose").get())
                // Custom row-tenancy ruleset (AccountScopedDao). :detekt-rules deliberately does not
                // apply this convention, so wiring it here can't create a self-analysis cycle.
                add("detektPlugins", project(":detekt-rules"))
            }
        }
    }
}

private val Project.libs
    get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")
