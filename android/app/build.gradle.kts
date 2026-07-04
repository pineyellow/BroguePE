import java.util.Properties

val broguePeVersionName = "1.0.3"

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val requiredKeystoreProperties =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasAnyKeystoreProperty =
    requiredKeystoreProperties.any(keystoreProperties::containsKey)
val hasCompleteKeystoreProperties =
    requiredKeystoreProperties.all(keystoreProperties::containsKey)

if (hasAnyKeystoreProperty && !hasCompleteKeystoreProperties) {
    val missing = requiredKeystoreProperties.filterNot(keystoreProperties::containsKey)
    throw GradleException(
        "android/keystore.properties is incomplete; missing: ${missing.joinToString()}"
    )
}

// Android Studio's Generate Signed APK wizard supplies these temporarily.
val injectedSigningProperties = listOf(
    "android.injected.signing.store.file",
    "android.injected.signing.store.password",
    "android.injected.signing.key.alias",
    "android.injected.signing.key.password",
)
val hasInjectedSigning =
    injectedSigningProperties.all { providers.gradleProperty(it).isPresent }

android {
    namespace = "com.pineyellow.broguepe"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.pineyellow.broguepe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = broguePeVersionName

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DBROGUE_PE_VERSION=$broguePeVersionName",
                )
                cFlags += listOf(
                    "-std=c99",
                    "-Wall",
                    "-Wno-parentheses",
                    "-Wno-unused-result",
                    "-Wno-format",
                    "-Wno-incompatible-pointer-types-discards-qualifiers",
                    "-O2",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        getByName("debug")
        if (hasCompleteKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }

        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("../../bin/assets")
            java.srcDirs(
                "src/main/java",
                "../SDL2/android-project/app/src/main/java"
            )
        }
    }
}

// Never let an ordinary release build silently produce a debug-signed or
// unsigned APK. The Android Studio signing wizard remains supported through
// its injected signing properties above.
tasks.configureEach {
    if (name == "packageRelease") {
        doFirst {
            if (!hasCompleteKeystoreProperties && !hasInjectedSigning) {
                throw GradleException(
                    "Release signing is not configured. Add android/keystore.properties " +
                        "or use Android Studio's Generate Signed APK wizard."
                )
            }
        }
    }
}
