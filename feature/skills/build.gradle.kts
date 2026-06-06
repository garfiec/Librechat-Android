plugins {
    id("librechat.kmp.feature")
}

android {
    namespace = "com.garfiec.librechat.feature.skills"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            // Compose-native markdown for the skill detail body. Same renderer the
            // chat feature uses (com.mikepenz), pulled in directly here so this
            // module doesn't depend on :feature:chat (features depend on :core:* only).
            implementation(libs.markdown.renderer.m3)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.koin.test)
        }
    }
}
