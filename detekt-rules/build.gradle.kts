plugins {
    // No version: the Kotlin Gradle plugin is already on the classpath via build-logic, so
    // requesting a version here triggers a "plugin already on the classpath" resolution error.
    id("org.jetbrains.kotlin.jvm")
}

// Plain JVM module producing the custom Detekt ruleset jar. Wired into a module's analysis via
// `detektPlugins(project(":detekt-rules"))`. Deliberately does NOT apply the `librechat.detekt`
// convention plugin (it would try to analyze itself with its own rules and create a cycle).
dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(libs.detekt.test)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
