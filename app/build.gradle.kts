plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "io.github.rohmanhakim.tracktrainer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.rohmanhakim.tracktrainer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // youtubedl-android bundles its yt-dlp binary as a native lib that must be extracted
    // to run; Android Studio requires extractNativeLibs=true equivalent for AGP 9.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        // See app/lint.xml — project-level opt-in for selected Media3 @UnstableApi usage.
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Schemas exported to app/schemas/ and committed (owner decision — see T02 session notes).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (contents land in a later task; dependency lands here per S1)
    implementation(libs.androidx.datastore.preferences)



    // Coroutines / Flow
    implementation(libs.kotlinx.coroutines.android)

    // Coil 3 for YouTube thumbnail rendering in library rows (T04, decision 2A).
    // Exclude kotlin-stdlib: Coil 3.6.3's POM pins 2.4.10, which conflicts with
    // AGP 9.3.3's built-in Kotlin 2.2.10 and breaks basic stdlib resolution.
    implementation(libs.coil.compose) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }

    // yt-dlp for YouTube extraction (D2, validated at gate G1).
    implementation(libs.youtubedl.android.library)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Media3 (first used in T05)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.common)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}