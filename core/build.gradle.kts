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
    // The eval harness (Phase 7) resolves the repo tree (committed .state/eval artifacts,
    // eval inputs) relative to the project root — tests read it via this property.
    systemProperty("navigator.project.root", rootDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Tier A eval harness (Phase 7): runs the deterministic replay suite and writes the
// measurement report artifacts to .state/eval/ (git-tracked, vision 07 §5).
// Extra args for the entry point (e.g. the pairing-authoring helper) can be passed with
// -PevalArgs="list-candidates".
val generateTierAReport by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the Tier A eval replay suite and writes .state/eval/tier-a-report.{md,json}."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "navigator.core.eval.TierAHarnessKt"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(rootDir.absolutePath) +
            (project.findProperty("evalArgs")?.toString()?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
    })
}
