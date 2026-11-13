// NaviHelper·Pleos TTS가 에뮬레이터에서 실제로 동작하는지 확인하는 작은 앱(제출용 아님).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "app.playdrive.probe"
    compileSdk = 35
    defaultConfig { applicationId = "app.playdrive.probe"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
}
