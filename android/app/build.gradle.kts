import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The Play upload key: the key file lives outside the repo; where it is and its passwords are in the git-ignored
// android/keystore.properties (see RELEASING.md).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.ospfranco.trustmail"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ospfranco.trustmail"
        minSdk = 26
        targetSdk = 37
        // fastlane passes the next Google Play version code with -PversionCode=N (see fastlane/Fastfile).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The standard React Native debug key, kept so the Android OAuth client registered for the
        // Expo app (package + SHA-1) keeps working. Never use it for a public release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // The APK to sideload to friends (`./gradlew :app:assembleRelease`). It is signed with the debug key on
            // purpose: that key's SHA-1 is the one registered with Google, so Google sign-in works. Not for stores.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        // The Google Play build: `./gradlew :app:bundlePlay`. Same as release, but signed with the upload key.
        // (Play re-signs what it distributes with its own key, so Google sign-in needs *that* key's SHA-1.)
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            if (keystoreProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // Compose's test library pulls an older Espresso that crashes on Android 17 (InputManager.getInstance is gone).
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
