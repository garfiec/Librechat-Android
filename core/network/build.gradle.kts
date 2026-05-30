plugins {
    id("librechat.kmp.library")
    id("librechat.kmp.koin")
    id("librechat.kotlin.serialization")
}

android {
    namespace = "com.garfiec.librechat.core.network"
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops {
            val nwparams_defaults by creating {
                defFile(project.file("src/iosMain/cinterop/nwparams_defaults.def"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:logging"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        named("androidUnitTest").dependencies {
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
