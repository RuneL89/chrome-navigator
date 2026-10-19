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
    // Fixture/golden-backed tests resolve the repo tree relative to the project root —
    // tests read it via this property. (The Tier A eval harness was retired with the
    // training program, PHASE_VEXP3.)
    systemProperty("navigator.project.root", rootDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// UAT 3.1 (Phase 3): the ONE live-provider path in the build — two real calls through the
// production SystemOneClient. Every gate runs keyless on MockWebServer; only this task
// touches a provider. Needs the owner key (env NAVIGATOR_OPENROUTER_KEY or the git-ignored
// .tools/keys.env); costs ~$0.00005 at the measured ~$0.00002/call.
val liveSmoke by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "UAT 3.1: two live provider calls through SystemOneClient (choice+noul, score); " +
        "prints decisions, distributions, usage and latency. Requires the owner key; real spend."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "navigator.core.wire.LiveSmokeKt"
    workingDir = rootDir // DefaultEngineConfigs resolves .tools/keys.env from the repo root
}
