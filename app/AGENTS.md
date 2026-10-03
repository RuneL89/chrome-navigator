# app/ — DOX contract

## Purpose

`:app` — the Android application (vision 01 §3): a11y service, overlay UI, guards
integration, Room, ONNX, cloud LLM client. Phase 0 shipped the buildable skeleton;
Phase 1 added the observation layer (`observe/`).

## Ownership

* `src/main/kotlin/navigator/app/` — Android UI and services
  * `src/main/kotlin/navigator/app/observe/` — observation layer (Phase 1,
    vision 03): `NavigatorAccessibilityService` (fresh-root reads of the
    `PACKAGE_ALLOWLIST` browsers; read-only until Phase 6 — declares
    `canPerformGestures` but never dispatches), `A11ySnapshot` + `TreeSerializer`
    (JVM-pure model and `[nN] "text" kinds (cx,cy)` serialization, meaningful-node
    filter, 40-char truncation, meaningful-depth indent cap 4), `UrlBarReader`
    (id-first `com.android.chrome:id/url_bar`, editable-fallback, `Url =
    Value|Hidden|Unavailable`), `CaptureMode`/`CaptureActivity`/`CaptureReceiver`
    (debug fixture capture into the `:core` `A11yDump` schema; refused in
    non-debuggable builds)
* `src/main/AndroidManifest.xml` — application manifest (service + debug capture
  activity/receiver declarations; service config XML in `src/main/res/xml/`)
* `build.gradle.kts` — Android build config (minSdk 34, compile/targetSdk 36, Compose)

## Local Contracts

* Stack pins (vision 01 §3): Compose UI, OkHttp + kotlinx.serialization, Room, ONNX Runtime
  Android AAR; minSdk 34 / target+compileSdk 36
* Every navigation decision routes through `:core`'s `DecisionEngine` (ART-2) — no direct
  provider calls anywhere in this module
* Observation law is vision 03: fresh root every observation (`node.refresh()` before
  reads), no caching across steps, URL bar never stale (`Hidden` over stale values)
* No Android types in the pure observation model (`A11ySnapshot`, `TreeSerializer`,
  `UrlBarReader` strategy) — they must stay JVM-unit-testable without Robolectric;
  Android `AccessibilityNodeInfo` adapters live only in the service
* `Observation.nodes` is Phase 2's bucketing input — the node list shape is frozen
  (PHASE_01 §6); changes to it are a cross-phase contract change

## Work Guidance

## Verification

* `./gradlew :app:assembleDebug` must produce an APK
* `./gradlew :app:testDebugUnitTest` — Gate 1.1 serializer goldens +
  Gate 1.2 URL-bar strategy paths (Robolectric-free JVM tests)
* `./gradlew :core:test` — `RealA11yDumpFixturesTest` strictly re-validates every
  committed a11y-dump fixture (seed + Phase-1 real captures)

## Child DOX Index

No child folders.
