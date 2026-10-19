// Chrome Navigator (working title) — root build.
// Module law: :core is pure-JVM Kotlin, :app is the Android application (vision 01 §3-§4).
// (The KSP plugin line retired with the Room layer — PHASE_VEXP3, the training program removed.)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
