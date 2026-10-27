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
    // NewPipe Extractor requires core library desugaring on minSdk < 33 (we target 26).
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    lint {
        // See app/lint.xml — project-level opt-in for selected Media3 @UnstableApi usage.
        lintConfig = file("lint.xml")
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

    // NewPipe Extractor (Phase 2 T01 spike — JitPack release v0.26.5)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")

    // Required by NewPipe Extractor on minSdk < 33 (core library desugaring). Must be declared
    // in the coreLibraryDesugaring configuration (not implementation) once isCoreLibraryDesugaringEnabled is on.
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}