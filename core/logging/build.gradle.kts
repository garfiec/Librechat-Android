plugins {
    id("librechat.kmp.library")
    id("librechat.kmp.koin")
    id("librechat.kotlin.serialization")
}

android {
    namespace = "com.garfiec.librechat.core.logging"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            implementation(libs.kermit)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.coroutines.android)
        }
    }
}
