# app/ — DOX contract

## Purpose

`:app` — the Android application (vision 01 §3): a11y service, overlay UI, guards
integration, Room, ONNX, cloud LLM client. Phase 0 ships only the buildable skeleton and a
placeholder `MainActivity`; each component arrives in its own phase.

## Ownership

* `src/main/kotlin/navigator/app/` — Android UI and services
* `src/main/AndroidManifest.xml` — application manifest
* `build.gradle.kts` — Android build config (minSdk 34, compile/targetSdk 36, Compose)

## Local Contracts

* Stack pins (vision 01 §3): Compose UI, OkHttp + kotlinx.serialization, Room, ONNX Runtime
  Android AAR; minSdk 34 / target+compileSdk 36
* Every navigation decision routes through `:core`'s `DecisionEngine` (ART-2) — no direct
  provider calls anywhere in this module

## Work Guidance

## Verification

* `./gradlew :app:assembleDebug` must produce an APK

## Child DOX Index

No child folders.
