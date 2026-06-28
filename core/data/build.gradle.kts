plugins {
    id("librechat.kmp.library")
    id("librechat.kmp.koin")
    id("librechat.kmp.room")
    id("librechat.kotlin.serialization")
}

android {
    namespace = "com.garfiec.librechat.core.data"
    testOptions {
        // Robolectric needs the merged manifest + resources on the host-JVM unit-test classpath.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:logging"))
            implementation(libs.datastore.preferences)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.security.crypto)
        }
        named("androidUnitTest").dependencies {
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)
            // Host-JVM Room lane (account-tenancy migration + isolation suite): Robolectric supplies a
            // working framework SQLite on the JVM, since production Android uses the framework driver.
            implementation(libs.robolectric)
            implementation(libs.android.test.core)
            implementation(libs.room.testing)
            implementation(libs.truth)
            implementation(libs.coroutines.test)
        }
        named("androidInstrumentedTest").dependencies {
            implementation(libs.room.testing)
            implementation(libs.truth)
            implementation(libs.coroutines.test)
            implementation(libs.android.test.runner)
            implementation(libs.android.test.ext.junit)
        }
    }
}
