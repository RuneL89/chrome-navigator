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

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
