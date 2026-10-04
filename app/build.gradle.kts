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
// gitignored asset is verified against the sha256 committed in assets/laya/PROVENANCE.txt.
val verifyLayaModel by tasks.registering {
    group = "verification"
    description = "Verifies the laya int8 shadow model is present and matches its committed sha256."
    doLast {
        val model = file("src/main/assets-local/laya/laya-int8.onnx")
        val provenance = file("src/main/assets/laya/PROVENANCE.txt")
        if (!provenance.exists()) {
            throw GradleException(
                "laya shadow model provenance missing (app/src/main/assets/laya/PROVENANCE.txt) — " +
                    "run tools/fetch_laya.py (see the file header for the uv command)",
            )
        }
        val recorded = provenance.readLines()
            .firstOrNull { it.startsWith("sha256: ") }
            ?.removePrefix("sha256: ")?.trim()
            ?: throw GradleException("app/src/main/assets/laya/PROVENANCE.txt carries no 'sha256: ' line")
        if (!model.isFile) {
            throw GradleException(
                "laya shadow model missing (app/src/main/assets-local/laya/laya-int8.onnx) — " +
                    "build it with tools/fetch_laya.py (see the file header for the uv command)",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        if (actual != recorded) {
            throw GradleException(
                "laya shadow model sha256 mismatch: provenance pins $recorded, " +
                    "app/src/main/assets-local/laya/laya-int8.onnx is $actual — " +
                    "re-run tools/fetch_laya.py or fix the provenance",
            )
        }
        logger.lifecycle("verifyLayaModel: ${model.name} OK (sha256 ${actual.take(16)}...)")
    }
}

tasks.named("preBuild") { dependsOn(verifyLayaModel) }
