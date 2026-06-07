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
            implementation(libs.material.kolor)
        }
    }
}
