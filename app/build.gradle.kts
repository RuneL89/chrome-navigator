// :app — the Android application (vision 01 §3). Phase 0: buildable skeleton only.
import java.security.MessageDigest

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

    // Phase 8: the ~0.5 GB laya shadow model lives in the gitignored assets-local/ tree
    // (rebuilt by tools/fetch_laya.py; assets/laya/PROVENANCE.txt pins its sha256) and
    // merges into the APK's assets under laya/ next to the committed PROVENANCE.txt.
    sourceSets.getByName("main") {
        assets.srcDir("src/main/assets-local")
    }

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
    implementation(libs.compose.icons.core)
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

    // Phase 9: the owner's OpenRouter key storage (encrypted, local-only — ART-8).
    implementation(libs.security.crypto)

    // JVM unit tests (Gates 1.1/1.2 are Robolectric-free: pure Kotlin over fakes)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Gate 8.1/8.3: the REAL laya int8 graph runs on the desktop JVM through the same
    // ai.onnxruntime API the device uses (test-only artifact, never shipped)
    testImplementation(libs.onnxruntime.desktop)
    // Room DAO gate (5.1): the decision-log database runs on the JVM under Robolectric
    // (test-only; the Android SQLite framework is stubbed in plain unit tests). The
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
    // The laya gates resolve the model + fixture tree relative to the project root.
    systemProperty("navigator.project.root", rootDir.absolutePath)
    // This dev box has no system-wide VC++ runtime; the desktop ONNX Runtime's extracted
    // onnxruntime.dll cannot resolve msvcp140/vcruntime140 without help. Point the test
    // JVM's DLL search at a directory that carries the redist (the gitignored extracted
    // copy tools/fetch_laya.py maintains, else the Android SDK's emulator set — the same
    // lookup fetch_laya.py performs). No-op on machines with the redist installed.
    listOf(
        rootDir.resolve(".tools/vcrt/dlls"),
        file(System.getenv("LOCALAPPDATA") ?: "").resolve("Android/Sdk/emulator"),
    ).firstOrNull { it.resolve("msvcp140.dll").isFile }?.let { vcrt ->
        environment("PATH", "$vcrt;${System.getenv("PATH") ?: ""}")
    }
    testLogging {
        events("passed", "failed", "skipped")
        // full stack traces + messages in the console (crashed executors never reach the XML)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Phase 8 (phase doc §2.1): the build must never silently lack the shadow model. The
// gitignored assets are verified against the sha256s committed in assets/laya/PROVENANCE.txt.
// Phase 8C: PROVENANCE.txt carries one section per exported checkpoint (the Phase-8 421M
// shadow artifact AND the v32b primary) — every declared artifact is hash-gated; the
// harness picks the active one by engine mode.
// 2026-10-06 demotion: the v32b artifact + tokenizer are EVAL-ONLY (owner decision after
// UAT Tests 2-3) — their provenance sections declare "Packaging: eval-only" and the files
// live OUT of the APK in the gitignored .tools/laya-v32b/. verifyLayaModel checks them
// only when present (absent → a loud skip note, never a build failure; wrong hash when
// present → still loud).
val verifyLayaModel by tasks.registering {
    group = "verification"
    description = "Verifies every laya int8 artifact is present and matches its committed sha256."
    doLast {
        val provenance = file("src/main/assets/laya/PROVENANCE.txt")
        if (!provenance.exists()) {
            throw GradleException(
                "laya model provenance missing (app/src/main/assets/laya/PROVENANCE.txt) — " +
                    "run tools/fetch_laya.py (see the file header for the uv command)",
            )
        }
        // Parse the per-artifact sections: a "File: <name>" line opens a record; the
        // following "sha256: <hex>" (before the next File: line) pins it, and an optional
        // "Packaging: eval-only" line marks it NOT packaged (checked at the eval-only path).
        data class Artifact(val fileName: String, val sha256: String, val evalOnly: Boolean)
        val artifacts = mutableListOf<Artifact>()
        var current: String? = null
        var currentSha: String? = null
        var currentEvalOnly = false
        fun flush() {
            val name = current
            val sha = currentSha
            if (name != null && sha != null) artifacts.add(Artifact(name, sha, currentEvalOnly))
        }
        for (rawLine in provenance.readLines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("File: ") -> {
                    flush(); current = line.removePrefix("File: ").trim(); currentSha = null; currentEvalOnly = false
                }
                line.startsWith("sha256: ") -> currentSha = line.removePrefix("sha256: ").trim()
                line.startsWith("Packaging: ") -> currentEvalOnly = line.contains("eval-only", ignoreCase = true)
            }
        }
        flush()
        if (artifacts.isEmpty()) {
            throw GradleException("app/src/main/assets/laya/PROVENANCE.txt carries no 'File:' + 'sha256:' artifact sections")
        }
        fun sha256Of(model: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        }
        for (artifact in artifacts) {
            val model = if (artifact.evalOnly) {
                val evalFile = rootDir.resolve(".tools/laya-v32b/${artifact.fileName}")
                if (!evalFile.isFile) {
                    logger.lifecycle(
                        "verifyLayaModel: ${artifact.fileName} SKIPPED — eval-only (demoted " +
                            "2026-10-06, not packaged) and absent from .tools/laya-v32b/ (restore " +
                            "with tools/fetch_laya.py --checkpoint v32b)",
                    )
                    continue
                }
                evalFile
            } else {
                val packaged = file("src/main/assets-local/laya/${artifact.fileName}")
                if (!packaged.isFile) {
                    throw GradleException(
                        "laya model missing (app/src/main/assets-local/laya/${artifact.fileName}) — " +
                            "build it with tools/fetch_laya.py --checkpoint (see the file header for the uv command)",
                    )
                }
                packaged
            }
            val actual = sha256Of(model)
            if (actual != artifact.sha256) {
                throw GradleException(
                    "laya model sha256 mismatch for ${artifact.fileName}: provenance pins ${artifact.sha256}, " +
                        "the file is $actual — re-run tools/fetch_laya.py or fix the provenance",
                )
            }
            logger.lifecycle("verifyLayaModel: ${artifact.fileName} OK (sha256 ${actual.take(16)}...)")
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyLayaModel) }
