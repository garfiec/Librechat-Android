plugins {
    id("librechat.kmp.library")
    id("librechat.kmp.compose")
}

android {
    namespace = "com.garfiec.librechat.core.ui"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
            implementation(libs.zoomimage.compose.coil3.core)
            implementation(libs.material.kolor)
            implementation(libs.compose.ui.backhandler)
        }
        androidMain.dependencies {
            // Runtime-permission launcher for saving images to the gallery (API < 29).
            implementation(libs.activity.compose)
            implementation(libs.kermit)
        }
        named("androidInstrumentedTest").dependencies {
            implementation(libs.junit)
            implementation(libs.android.test.runner)
            implementation(libs.android.test.ext.junit)
            implementation(libs.compose.ui.test)
            implementation(libs.compose.ui.test.manifest)
            // Pin espresso ≥3.7.0: the 3.5.x pulled in transitively by ui-test-junit4 injects
            // input via InputManager.getInstance, which no longer exists on API 36+.
            implementation(libs.espresso.core)
        }
    }
}
