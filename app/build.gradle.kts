import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("AZADLINK_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("AZADLINK_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("AZADLINK_KEY_ALIAS")
val releaseKeyPassword = System.getenv("AZADLINK_KEY_PASSWORD")
val releaseSigningConfigured = !releaseKeystorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()


android {
    namespace = "com.azadlink.vpn"
    compileSdk = 35
    defaultConfig {
        // AzadLink v0.11.2 stable release
        applicationId = "com.azadlink.vpn"
        minSdk = 26
        targetSdk = 35
        // Aether and bundled native tunnel engines are currently built for 64-bit ARM only.
        // Do not let 32-bit-only AAR libraries make Android launch a 32-bit process without libaether.so.
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 17
        versionName = "0.11.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { jniLibs.useLegacyPackaging = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":aether-vpn"))
    implementation("com.google.code.gson:gson:2.11.0")
    val coreAar = file("libs/libv2ray.aar")
    if (coreAar.exists()) implementation(files(coreAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}
