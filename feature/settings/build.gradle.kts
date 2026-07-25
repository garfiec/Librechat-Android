plugins {
    id("librechat.kmp.feature")
}

android {
    namespace = "com.garfiec.librechat.feature.settings"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))
            implementation(project(":core:logging"))
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
            implementation(libs.kermit)
            implementation(libs.qrose)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.koin.test)
        }
    }
}
