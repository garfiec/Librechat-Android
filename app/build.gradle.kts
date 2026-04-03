plugins {
    id("librechat.android.application")
    id("librechat.android.compose")
    id("librechat.android.koin")
}

android {
    namespace = "com.librechat.android"

    defaultConfig {
        applicationId = "com.librechat.android"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:files"))
    implementation(project(":feature:voice"))

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.koin.compose.viewmodel.navigation)
    implementation(libs.compose.material3.wsc)
    implementation(libs.bundles.lifecycle)
    implementation(libs.coil.compose)
    implementation(libs.profileinstaller)
    implementation(libs.timber)

    debugImplementation(libs.leakcanary)

    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
