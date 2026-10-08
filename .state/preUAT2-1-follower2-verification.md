# PREUAT2-1 follower #2 — verifier report (cold)

Date: 2026-10-08. Base: `09ea133` ("Restore fallback chain: last-known page URL after in-task cards").
Fix: `normalizePageUrl` — mobile Chrome omits the URL-bar scheme, so scheme-less bar
text (`127.0.0.1:8099/subscriptions.html`) was rejected by the old http(s)-prefix guard
and never tracked as last-known.

## Verdict: PASS

No contradictions with `Project Vision/`. All gates green, evidenced below.

## Check 1 — `app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt`

`git diff HEAD` shows exactly two hunks: `@@ -219,7 +219,7 @@` (one comment word)
and `@@ -235,37 +235,69 @@` (the helper region). Nothing else in the loop changed.

- Helper `normalizePageUrl` (lines 261-272): deterministic pure function of `raw`,
  no `throw`/`require`/`!!` paths — null/blank to null; trim; `http(s)://` passes
  through as-is; scheme-less host (`substringBefore('/')`: dotted host, `localhost`,
  `[::1]`, each with optional `:port`) gets the scheme prepended (loopback -> `http`,
  else `https`); everything else (`about:blank` — no dot, not loopback; `hidden`;
  blank) -> null.
- Applied at BOTH sites: `notePageUrl` (lines 276-278, stores the NORMALIZED value)
  and `restoreBrowserForeground` (lines 286-303, serves the first chain candidate
  that normalizes, fires the VIEW intent with the normalized URL, notes
  `(normalized)` when the value changed).
- Chain order unchanged: `card -> observation -> last-known` at the gate site
  (lines 1132-1139) and the completion site (lines 809-813).
- Exclusions unchanged: gate restore gated on `gateCardFronted && !taskAborted`
  (line 1132); overlay / never-fronting / trusted / task-ending paths untouched.
- Restore-before-execute unchanged: gate restore (1132-1139) precedes EXECUTE (1143).
- Loud no-URL line byte-identical to base: `git show HEAD:...NavigationLoop.kt`
  line 263 vs worktree line 292 — same string
  (`restore: $what fronted the harness but the URL bar holds no page URL - no VIEW intent`).
- Old `usablePageUrl` fully removed: `grep -rn usablePageUrl app/ core/` -> zero hits.
- ART-4/ART-8 untouched: guards (`guards!!.seed(obs.url)`, line 588), state text
  (`url = obs.url`, line 602), cold-start, and `PageContext(obs.url)` (lines 652, 941)
  all still consume the RAW bar read — normalization is scoped to the restore seam
  + last-known tracking only.

## Check 2 — `CardForegroundRestoreTest.kt`

- `git diff HEAD` removed-line count: 0 (`grep -c "^-[^-]"` -> 0). Pure addition.
- `@Test` count: 14 at HEAD -> 18 in worktree (result XML confirms
  `tests="18" skipped="0" failures="0" errors="0"`; all 18 case names enumerated).
- 4 new cases (lines 408-579), each meaningful (asserts exact fired intent URL AND
  the log marker, not just terminal state):
  1. scheme-less loopback card URL fires as `http://127.0.0.1:8099/subscriptions.html`
     with `(normalized) (card)`;
  2. scheme-less non-loopback fires as `https://example.test/wiki/X`;
  3. scheme-less bar read tracked as last-known (normalized) and serves on an empty
     card URL (`(last-known)`, correctly WITHOUT `(normalized)` since stored schemed);
  4. garbage (`about:blank`, empty; `hidden` pinned by the pre-existing test) never
     serves + the loud line.
- 14 pre-existing tests: unchanged (zero removed lines).

## Check 3 — law

- ART-2: no engine consulted — the helper is pure string code; the restore seam
  (launcher + settle) is the pre-existing 2026-10-08 defect-fix machinery.
- No thresholds/timeouts/step-reorder: `Config` fields untouched
  (`restoreSettleMs = 1_000` as at base); hunks confined to the helper region.
- ART-4/ART-8: URL handling is not page content; content-consuming call sites
  unchanged (see Check 1).

## Check 4 — change set vs HEAD (`09ea133`)

Tracked modified (5): `.state/AGENTS.md`, `.state/compliance-log.md`, `app/AGENTS.md`,
`app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt`,
`app/src/test/kotlin/navigator/app/loop/CardForegroundRestoreTest.kt`.
Untracked: `.state/demo-session-2026-10-08/owner-card-evidence.jpg` (the cited owner
screenshot evidence), `.state/preUAT2-1-follower2.json` (implementer status note),
`.zcodeignore` (environment tooling file, unrelated to the fix, benign).
`git status --short -- "Project Vision/" "Implementation Plan/"` -> empty.
`git diff HEAD --stat -- "Project Vision" "Implementation Plan" "core" "tools"
"handoff" "app/src/main/assets" "gradle"` -> empty. Nothing under `Project Vision/`.

## Check 5 — test re-run (`./gradlew test --rerun-tasks`, executed cold by verifier)

`BUILD SUCCESSFUL in 8m 18s`, exit 0 (`70 actionable tasks: 70 executed`).
JUnit XML sums: `:core` 293 tests / 0 failures / 0 errors / 0 skipped;
`:app` debug 357 / 0 / 0 / 0. Total 650 — matches the claimed 650 exactly
(646 prior + 4 new). Release-variant XMLs also present: 357, no failures/errors.
`CardForegroundRestoreTest` XML: 18/18 green (names listed in Check 2).

## Defects

None. One observation (not a defect, out of scope): the helper is case-sensitive
to the scheme prefix and treats single-label non-loopback hosts (`myhost:8080`)
as garbage -> null (conservative, fails toward the loud no-URL line). Matches the
dispatched spec; no change requested.
