import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional for local builds: put the keystore data in
// `keystore.properties` at the repository root (gitignored), otherwise the
// release build falls back to the debug key so CI/builds still work.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { stream -> keystoreProperties.load(stream) }
}

android {
    // JVM unit tests run the real org.json but the android.jar stubs throw on
    // android.util.Log; return-default-values keeps pure-JVM tests usable.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    namespace = "com.yim1remote.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.yim1remote.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.8"

        // No instrumentation tests yet (milestone 1 is a buildable skeleton).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Release signing reads keystore.properties (gitignored). Without it the
    // release build falls back to the standard Android debug key so the project
    // still builds for contributors.
    signingConfigs {
        // Debug-only key, committed on purpose so a fresh clone builds without
        // writing to %USERPROFILE%\.android (CI/sandbox friendly). It cannot be
        // used for distribution.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storePath = keystoreProperties.getProperty("storeFile")
                if (!storePath.isNullOrBlank()) {
                    storeFile = file(storePath)
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Signed with the project release key when keystore.properties is
            // present; debug-signed fallback keeps contributor builds working.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

// The camera protocol is plain HTTP/JSON, so the JDK HttpURLConnection and
// org.json (test-only) are sufficient; no networking framework is required.
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests (the android.jar stub
    // throws "not mocked"); reserved for protocol/fixture parsing tests.
    testImplementation("org.json:json:20240303")
}
