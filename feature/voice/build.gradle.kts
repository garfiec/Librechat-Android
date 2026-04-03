plugins {
    id("librechat.android.feature")
}

android {
    namespace = "com.librechat.android.feature.voice"
}

dependencies {
    implementation(project(":core:network"))
    implementation(libs.timber)
    implementation(libs.markdown.renderer.m3)
}
