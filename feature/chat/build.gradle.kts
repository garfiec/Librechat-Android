plugins {
    id("librechat.kmp.feature")
}

android {
    namespace = "com.garfiec.librechat.feature.chat"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))
            implementation(project(":core:logging"))
            implementation(libs.atomicfu)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.coil3)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.koin.test)
        }
    }
}
