plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.desmond.gptwake"
    // 37 is required by compose.ui 1.12.0-beta02, which material3 1.5.0-alpha24 depends on.
    // targetSdk deliberately stays at 36 — this is a compile-time requirement, not a
    // behaviour opt-in.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.desmond.gptwake"
        minSdk = 32
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    androidResources {
        noCompress += listOf("onnx", "txt", "phone")
    }

    buildFeatures {
        viewBinding = false
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
            // Not in git. Its only purpose is a stable debug signature so `adb install -r` keeps
            // working across rebuilds on a test device; a fresh clone simply falls back to the
            // SDK's own debug key.
            val ks = rootProject.file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "probe"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Deliberately left unsigned. CI signs with apksigner using the release keystore held
            // in repository secrets, so no signing material ever lives in this repo.
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Kotlin API and native .so both come from the same sherpa-onnx v1.13.4 AAR.
    // The Kotlin Gradle plugin supplies kotlin-stdlib; pinning it here would risk a
    // version skew against the compiler.
    implementation(files("libs/sherpa-onnx-1.13.4-classes.jar"))

    // Compose, on the ALPHA BOM (-> material3 1.5.0-alpha24, compose.ui 1.12.0-beta02).
    //
    // This is deliberate and it is the only way to get Material 3 Expressive. On stable
    // material3 1.4.0 every Expressive entry point is Kotlin-`internal` and unusable from
    // app code: MaterialExpressiveTheme, MotionScheme, MaterialTheme.motionScheme, and the
    // increased shape scale (name-mangled `getLargeIncreased$material3`). Only the flexible
    // app bars are public there.
    //
    // The cost is that these APIs move: ButtonGroup and friends shipped in 1.4.0-alpha18 and
    // were removed again before 1.4.0-beta01. Pin the BOM and read the release notes before
    // bumping it.
    implementation(platform("androidx.compose:compose-bom-alpha:2026.07.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Morph and RoundedPolygon live here, not in material3 — MaterialShapes only supplies the
    // named presets. Pinned above the 1.0.1 that material3 resolves to.
    implementation("androidx.graphics:graphics-shapes:1.1.0")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.core:core:1.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // The Views stack (appcompat, com.google.android.material, constraintlayout) is gone along
    // with MainActivity.java and activity_main.xml. Nothing in the app references it any more.
}
