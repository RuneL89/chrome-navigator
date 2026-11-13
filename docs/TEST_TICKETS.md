# V2 prototype round — test ticket baseline (2026-11-14, Test Manager)

Scope: independent verification of `docs/V2_PROTOTYPES.md` on emulator `emulator-5554`.
Re-ran every claim where cheap (see §4 for exact commands). No product code modified.

## 1. Verdicts per prototype

| # | Prototype | Verdict | Note |
|---|---|---|---|
| 1 | MotionEvent trust | **CONFIRMED** | All 7 event types `trusted:true`, single clean sequence after the programmatic dispatch; `PROBE_BUTTON` present; results went `[]` → 7 entries, no concurrent input |
| 2 | Site corpus (6 sites) | **CONFIRMED with one CONTESTED item** | Wiki EN/FI, Verkkokauppa, Google consent wall, Fastmail all reproduce. **Red Bull LOADED on re-run** (title + full text) vs reported RED — see BUG-01 |
| 3 | Account-cart / cookies | **CONFIRMED (negative half); positive half OWNER-PENDING** | Names-only (`_cfuvid, cf_clearance, __cf_bm, vk_cc`), app-private jar, zero value leakage in logs/files. Server-side survival still needs an owner login session — see INC-02 |
| 4 | Gmail API send | **UNVERIFIABLE (blocked on owner gate)** | Needs a Google Cloud OAuth client; API contract research-stands — see INC-01 |
| 5 | Off-screen rendering | **CONFIRMED** | +2/s ticks, ~60 fps rAF, lazy=5, Settings foreground + screencap shows no overlay |

Cookie-set variance (not a defect, noted): Verkkokauppa jar showed 4 names on re-run
(`_cfuvid, cf_clearance, __cf_bm, vk_cc`) vs 2 reported (`__cf_bm, _cfuvid`).
Cookie sets are session-dependent — the lane must never assert fixed cookie names.

## 2. Tickets

### High severity

_Empty — no high-severity gaps found._

### Medium severity

| ID | Title | Requirement link | Evidence | Status | Owner-decision-needed |
|---|---|---|---|---|---|
| BUG-01 | Red Bull verdict contested: reported RED (blank) but loads fully on re-run | Prototype #2 (FR-22 per-site measurement) | `docs/prototypes/redbull.png` + `redbull2.png` (blank, `PROBE_STATE` empty `title/text`) vs re-run 2026-11-14 00:06 UTC: `PROBE_STATE` title `Red Bull -energiajuoma …`, url `https://www.redbull.com/fi-fi`, full text; `onPageFinished` fired. Hypotheses: slow SPA hydration vs early screenshot, or flaky bot-wall. P1 must re-measure with a fixed dwell/timeout policy before routing Red Bull to any lane | OPEN | no |
| INC-01 | Gmail API send end-to-end unverified | Prototype #4; FR-49, FR-50 | `V2_PROTOTYPES.md` §4: blocked on Google Cloud OAuth client (owner creates project → enable Gmail API → consent screen → Android client for `navigator.app` + SHA-1). Sprint 2 owner gate already planned; no code or fixture may claim this path works until the send + 30 s hold are demonstrated on device | OPEN | **yes** — owner creates the OAuth client |
| INC-02 | Account-cart positive half unverified (server-side survival) | Prototype #3; FR-03 | Negative half proven (no cookie bridge; app-private jar confirmed by `PROBE_COOKIES`). Positive half needs owner logged in to Verkkokauppa in Chrome + probe add-to-cart while logged in. Sprint 4 owner gate already planned | OPEN | **yes** — owner login session |

### Low severity

| ID | Title | Requirement link | Evidence | Status | Owner-decision-needed |
|---|---|---|---|---|---|
| CHG-01 | Probe harness must be stripped or debug-gated before any release build | Harness safety (FR-78, FR-79) | `app/src/main/AndroidManifest.xml`: `ProbeActivity android:exported="true"`, `ProbeOverlayService` `specialUse` FGS. Justified and scoped for probes (adb-driven, self-contained, no secrets — verified), and the manifest comment already promises removal with the V2 lane. Convert that comment into a tracked gate so the exported surface can never ship | OPEN | no |
| CHG-02 | Trust probe lacks an evidenced negative control | Prototype #1; FR-13 | **FIXED 2026-11-14**: `probe trustjs` records the JS arm — `[{"t":"click","trusted":false}]` (click only, no pointer/touch sequence); `probe trust` + a real `adb shell input tap 330 418` records the external arm — the full 7-event `trusted:true` sequence. Both arms now evidenced alongside the `dispatchTouchEvent` arm | FIXED | no |

WONTFIX: none.

## 3. Test-structure rules (enforced from Sprint P0 on)

1. **Two test layers, both mandatory.** Technical tests (JVM/unit/characterization, runnable
   without a device) + functional tests (device/emulator via the android-emulator plugin,
   recorded in evidence). A claim counts as verified only when its layer's test is green.
2. **Every acceptance criterion traces to an FR id.** Criteria without an FR link are rejected
   at sprint planning; FR register changes require the same owner-amendment discipline as the plan.
3. **UAT is reserved strictly for owner-judgment items** (cards, wording, animation feel,
   consent flows). Anything machine-checkable is verified by layers 1–2 before it ever reaches UAT.
4. **Sprint gate:** a sprint cannot pass with any OPEN ticket that is not owner-decision-gated.
   Owner-decision tickets (`owner-decision-needed: yes`) wait on the owner and do not block;
   everything else must be FIXED or formally WONTFIX (owner amendment) before the sprint closes.
5. **No secrets in evidence, ever** (FR-78/V2 plan global rule): cookie values, tokens, key
   material, and message bodies stay out of logs, screenshots, fixtures, and ticket files.
   Sweeps like §4 item 7 are part of every functional run.

## 4. Commands re-ran (all against `emulator-5554`, adb at
`%ANDROID_SDK%\platform-tools\adb.exe`)

```
# 1. Trust
adb -s emulator-5554 shell am force-stop navigator.app
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe trust
adb -s emulator-5554 logcat -d -s NavigatorProbe   # PROBE_BUTTON + PROBE_STATE results[7×trusted:true]

# 5. Off-screen (+ invisibility)
adb -s emulator-5554 shell appops set navigator.app SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell am start-foreground-service -n navigator.app/.probe.ProbeOverlayService --ei seconds 30
adb -s emulator-5554 shell am start -n com.android.settings/.Settings   # other app forward
adb -s emulator-5554 logcat -d -s NavigatorProbe   # PROBE_OFFSCREEN +2/s, raf ~60fps, lazy=5
adb -s emulator-5554 shell dumpsys activity activities | grep -i topResumedActivity
adb -s emulator-5554 exec-out screencap -p          # Settings only, no overlay

# 2+3. Sites (each preceded by force-stop + logcat -c)
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://en.wikipedia.org/wiki/Tove_Jansson"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://fi.wikipedia.org/wiki/Tove_Jansson"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://www.verkkokauppa.com"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://www.google.com/search?q=Tove+Jansson"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://www.fastmail.com"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe site --es url "https://www.redbull.com"
adb -s emulator-5554 shell am start -n navigator.app/.probe.ProbeActivity --es probe cookies --es url "https://www.verkkokauppa.com"
adb -s emulator-5554 logcat -d -s NavigatorProbe   # PROBE_PAGE / PROBE_COOKIES rows

# Safety sweeps
grep -rn -i "apikey\|api_key\|secret\|bearer\|client_secret" app/src/main/kotlin/navigator/app/probe/ app/src/main/assets/probe/
grep -rn "ProbeActivity\|ProbeOverlayService" app/src/main --include="*.kt" --include="*.xml"  # only manifest + own files
adb -s emulator-5554 shell "run-as navigator.app grep -h -o -i 'cf_clearance=[^ ;]*\|__cf_bm=[^ ;]*' files/probe-*.txt"  # empty = clean
```
