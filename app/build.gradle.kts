plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.azadlink.vpn"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.azadlink.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(project(":aether-vpn"))
    implementation("com.google.code.gson:gson:2.11.0")
    val coreAar = file("libs/libv2ray.aar")
    if (coreAar.exists()) implementation(files(coreAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}
