# Pre-UAT automation pass (/android-dev) — emulator, 2026-10-08

**Directive:** owner-ordered mechanical pre-pass before the owner demo session; orchestrator as judge/runner.
**Verdict: ENVIRONMENT-BLOCKED for content-dependent flows — no product defect found.** Owner demo to proceed on the S25+ (healthy environment), mechanical battery first.

## What ran (AVD pocketpet-cardfix, API 35, WebView Browser Tester 124)
- APK 355a494 installed (Success). All 10 harness goals carry stub scripts ($0).
- **unreadable-1: PASS first-try** — `REFUSED (expected REFUSED, gaps=0, attempts=1)`: the full harness machinery (launch → service await → observe → graceful refusal per vision 03 §5 → report → suite-report → expected-terminal match) works on-device.
- **exit-reliability-1: 5 launches blocked by the webview a11y starvation** — every attempt reads a toolbar-only tree (6 lines, pageNodes=2); the webview content subtree is never served to the observation service. Remedies exhausted across TWO boots (warm snapshot + true cold `-no-snapshot`): `pm clear org.chromium.webview_shell`, shell force-stop (webview re-create with service bound), service re-put after every force-stop launch (runbook), UiAutomator pulse during observation (made it worse — shell UiAutomation disrupts other a11y clients), clean relaunch with zero interference.
- **Differential proof it is NOT the product:** `uiautomator`/MCP ui_describe SEES the full webview tree ("Blank", "A nearly empty page.") through its own channel while our bound service gets 2 page nodes — the tree exists; the webview-shell (not real Chrome) does not push content a11y to third-party services in this state. Identical code reads real Chrome perfectly on the S25+ (2026-10-07 evidence: full Tarte runs, card bring-to-front, 16 ExitCheckRows).
- **Graceful-refusal law exercised 6+ times** on-device: every starvation ended in a clean REFUSED (vision 03 §5), never a crash, never a wrong action.

## Mechanical UAT content status (no owner judgment needed)
Covered by the 632-test JVM suite (same fixtures/loop/stubs incl. card resolutions + secret-absence) + 2026-10-07 S25+ evidence. The remaining mechanical re-verification (multi-action tap-approve, bait, cheapest, credential-card masked input, H9 card visibility) runs on the S25+ immediately before the owner-judgment items — same adb driving as 2026-10-07.
