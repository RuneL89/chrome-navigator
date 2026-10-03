// :core — the pure-JVM decision core (vision 01 §3).
// BOUNDARY (ART-2 / ART-11): this module must have ZERO Android dependencies —
// no androidx, no android.*, no Android Gradle plugin. It runs on the desktop JVM
// so all decision logic is unit-testable without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Wire client (Phase 3): OkHttp is the default transport behind the injectable
    // WireTransport interface; coroutines back the suspend API. Both are pure-JVM
    // (ART-2 boundary holds: no Android artifacts on :core — verifier-checked).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    // Wire gates (3.1-3.3) run against MockWebServer only — no live provider in tests.
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
