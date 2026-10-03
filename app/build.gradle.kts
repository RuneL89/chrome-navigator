// :app — the Android application (vision 01 §3). Phase 0: buildable skeleton only.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "navigator.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "navigator.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Robolectric (Room DAO gate 5.1) needs merged test resources
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // Declared per the ratified stack (vision 01 §3); the Room compiler (KSP) wired in Phase 5
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.onnxruntime.android)

    // Runtime for encoding the :core fixture schemas (Phase-1 capture mode)
    implementation(libs.kotlinx.serialization.json)

    // Phase 6: the loop is suspend-first (engine, observer, log sink); :core keeps
    // coroutines internal, :app declares its own copy for the loop + harness.
    implementation(libs.kotlinx.coroutines.core)

    implementation(project(":core"))

    // JVM unit tests (Gates 1.1/1.2 are Robolectric-free: pure Kotlin over fakes)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Room DAO gate (5.1): the decision-log database runs on the JVM under Robolectric
    // (test-only; the Android SQLite framework is stubbed in plain unit tests). The
    // vintage engine lets the JUnit4-based Robolectric runner ride the JUnit platform.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
