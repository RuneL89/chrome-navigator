// :app — the Android application (vision 01 §3). Phase 0: buildable skeleton only.
// PHASE_VEXP3: the training-data program is RETIRED (owner directive 2026-10-19,
// "remove that noise completely") — no Room persistence, no ONNX Runtime, no laya
// model packaging, no verifyLayaModel; the legacy loop logs through NoOpLogSink.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        // Phase 9 instrumented gates (9.1-9.4) run through the AndroidX runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // EXPERIMENT BRANCH ONLY (exp/vision-box, 2026-10-17): the debug APK installs
            // ALONGSIDE the product app instead of replacing it — the owner's device runs
            // the phase-10d build under `navigator.app`, and the vision experiment must
            // not touch it. The FILE is also renamed: both branches used to write the
            // same `app-debug.apk`, and a parallel phase-10d build clobbered the output
            // dir mid-session (measured 2026-10-17 — the wrong APK got installed).
            // Remove this block when the branch merges or dies.
            applicationIdSuffix = ".visionexp"
            versionNameSuffix = "-visionexp"
            applicationVariants.all {
                if (buildType.name == "debug") {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                            ?.outputFileName = "navigator-visionexp-debug.apk"
                    }
                }
            }
        }
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

    // Robolectric (the setup prefs round-trip gate) needs merged test resources
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
    implementation(libs.compose.icons.core)
    implementation(libs.activity.compose)

    // Declared per the ratified stack (vision 01 §3); the Room/ONNX lines retired with
    // the training program (PHASE_VEXP3).
    implementation(libs.okhttp)

    // Runtime for encoding the :core fixture schemas (Phase-1 capture mode)
    implementation(libs.kotlinx.serialization.json)

    // Phase 6: the loop is suspend-first (engine, observer, log sink); :core keeps
    // coroutines internal, :app declares its own copy for the loop + harness.
    implementation(libs.kotlinx.coroutines.core)

    implementation(project(":core"))

    // Phase 9: the owner's OpenRouter key storage (encrypted, local-only — ART-8).
    implementation(libs.security.crypto)

    // JVM unit tests (Gates 1.1/1.2 are Robolectric-free: pure Kotlin over fakes)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Phase 10 Gate 10.1: the generative writer client's golden request/response +
    // validation-matrix gates run against MockWebServer (test-only — no gate ever calls
    // a live provider; the :core wire gates' precedent).
    testImplementation(libs.mockwebserver)
    // The prefs round-trip gate runs on the JVM under Robolectric (test-only). The
    // vintage engine lets the JUnit4-based Robolectric runner ride the JUnit platform.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.core)

    // Phase 9 instrumented gates (9.1-9.4): overlay mechanics/privacy/wiring/latency,
    // driven on-device by the orchestrator (androidTest compiles green here; the
    // connected run is the gate's executor).
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Fixture-backed loop gates resolve the fixture tree relative to the project root.
    systemProperty("navigator.project.root", rootDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        // full stack traces + messages in the console (crashed executors never reach the XML)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
