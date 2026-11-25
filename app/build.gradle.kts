import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = System.getenv("AIRPAD_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() && file(it).isFile }
val releaseStorePassword = System.getenv("AIRPAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("AIRPAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("AIRPAD_KEY_PASSWORD")
val releaseSigningConfigured = releaseKeystorePath != null &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.wasti.airpad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wasti.airpad"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storeType = "JKS"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
