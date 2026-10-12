# Phase 10D — Informed re-plan, one-step rollback, complete evidence (three stages, autonomous)

**Generalization law (owner mandate, 2026-10-12):** every change is site-agnostic. No host, label, language, or site-specific string in production code, prompts, or tests. The failure block is built from the page's *action buckets* (links / buttons / fields / scroll / back) and the *cause class*, never from domain knowledge. Wikipedia, BBC, Google and allrecipes are test instances, not design targets. This serves arbitrary long-horizon autonomy ("book me a hotel in Tallinn next week") — §7 lists what that still needs.

## 0. The defect (evidence)

`replan()` (`NavigationLoop.kt:964–993`) sends the writer only `maskedGoal` + `generation` (line 975); `GenClient.restructure` (`GenClient.kt:166–168`) never puts generation into the prompt ⇒ gen2/gen3 are byte-identical requests. Proven by UAT 10C Test 1 (`task-1791818895934`): three cosmetically-rewritten identical ladders, two wasted generations, three strikes, owner Stop, `STUCK_KILL`. The log line at `NavigationLoop.kt:967–970` already claims "asking the writer over the goal + failure context" — the intent existed, never wired. Two riders: `applyLadder` (`:806–817`) resets the pointer to subtask 1, silently discarding verified progress; and on the no-good-target path the failing slate + scores are never persisted (`Stage2Outcome.NoGoodTarget` carries only a reason).

## 1. Owner-ratified decisions

1. **Verified subtasks immutable by default** — the writer refines only the remainder; code-enforced, loud refusal otherwise.
2. **Exactly one step back may be rolled back** — the most recently verified subtask may be rewritten when the path itself was wrong. Everything before it stays frozen.
3. **A rollback must be declared, with the lesson** — the ladder JSON gains `rollback: {steps, lesson}`; missing/blank while the prefix diverges = refused loudly. The lesson rides the row + history digest as UAT evidence.
4. **Growth allowed** — the failing subtask may split into several smaller intermediate subtasks (still ≤ 8).
5. **Cheap recovery first** — in-page recovery (handshake assist → one bounded stage-1 re-ask → fallback) unchanged; the informed re-plan fires exactly where today's blind one fires. Cap unchanged (`MAX_REPLANS = 2`, card at 3 consecutive failures).
6. **All three workstreams, in three stages**, one owner UAT only after all three.
7. **Evidence gap closed in this phase**; the cascade "try another intent" idea stays parked.

## 2. Stage 1 — Informed re-plan + one-step rollback (the core)

**W1.1 Failure block (app/loop).** New `ReplanContext`: failing subtask (ordinal, instruction, rendered condition), verbatim cause + its class, bounded page identity (host/title/excerpt, reusing `ContentExtractor` and existing caps), affordance digest (failing intent's slate via `Cascade.slate`/`bucketFor` + top labels of the other buckets via `Candidate.line()`), history tail (≤3), assist text, verified count, generation. Built by a **small helper at the in-loop failure sites** (`cascadeFallbackStep` `:2133`, the give-up site `:2713` — where `obs`/`candidates`/`state` exist; `replan()` can't see them, being declared before the loop). Threaded through `cascadeFallbackStep → handleSubtaskFailureOrKill → arbitrateSubtaskFailure → replan`, also fixing the cause currently dropped at `:1070` (`stuckCause = null`). Masked with `redactionKeys()` like `maskedGoal`; page material delimited untrusted. New code in helpers, never inline in `run()` (10C's 64KB method-limit lesson).

**W1.2 Writer surface (app/gen).** `PROMPT_VERSION` → `restructure-v4`; `restructureUser` gains an optional `<failure>…</failure>` block (untrusted clause mirrored from assist/phrase); the system prompt gains: keep verified subtasks exactly as given; refine only the remainder, adding intermediate subtasks when this page cannot serve the next step; plus a short code-authored per-cause hint (no-good-target / empty slate / uncertain-after-handshake / unresolvable route / no-scroll-candidate / stuck-repeats / engine give-up). `LadderSource.restructure(goal, generation, failure: ReplanContext? = null)`; `None` + five test fakes updated.

**W1.3 Rollback + monotonic rule (core + loop).** Ladder schema gains `rollback: {steps, lesson}` (parse + validate: gen ≥ 2 only; `steps == 1` via a named constant; lesson non-blank, length-bounded). New pure core rule `validateReplan(previous, verifiedCount, next)`: the next ladder's first `(verifiedCount − steps)` subtasks byte-equal (instruction + rendered condition) to the previous prefix; growth allowed; violation → loud refusal = no re-plan capacity (existing `:980–983` path) + history line. On acceptance, `applyLadder` re-keys the pointer to the first unfinished subtask (`SubtaskPointer` advanced N times — 10C's `applyCondition` re-key is the precedent) instead of resetting to 1. **The rollback never bypasses the 10C condition-honesty gate** (a rewritten condition already true where the subtask starts still gets repair/derive/loud refusal). Reconcile `ExitPolicy`'s streak across the new re-key (today `applyLadder` re-creates `exitPolicy`; doc 04's streak law says it survives a re-plan that keeps the condition) and pin it.

**W1.4 Provenance + surfaces.** Truthful re-plan log lines ("re-plan N — writer over the goal + failure context (subtask k/N, cause class)"; "rolled back 1 step — lesson: …"). The lesson + rollback count ride the history digest and an **additive** `plan` row field (`LoggedPlan` gains `rolledBack` + `lesson`; goldens regenerated). `LadderBlock` "re-plan N" pin unchanged. Escalation card untouched (no card copy change ⇒ no design interview).

**W1.5 Tests (Gate 10D.1, JVM).** Core: rollback parse/validation/depth cap/gen-1 refusal; `validateReplan` accept/refuse matrix. Loop: informed re-plan returns a different ladder; prefix preserved, pointer on the first unfinished subtask; a one-step rollback re-keys and re-verifies; a deeper rewrite refused loudly → card; failure block masked and injection-proof; honesty gate still fires on a rolled-back condition. Prompt: NEW `golden-restructure-v4.json` (retire-and-add; v3 stays byte-identical) with **both** request variants (initial + informed re-plan) — legal, the file is new; `GenClientTest` repointed + extended.

## 3. Stage 2 — Evidence: the failing slate reaches the database

`Stage2Outcome.NoGoodTarget` carries `scores` + `viewText`; `cascadeFallbackStep(withStage2 = true)` persists them into the **existing** `targetScoresJson`/`targetStateText` columns (currently null/stale here). Tests: the fallback row round-trips the failing slate; the analyser shows it. Gate 10D.2 (JVM).

## 4. Stage 3 — General field legibility (the blank-label class)

**General rule, no site logic:** an editable control whose text/content-description/hint are all empty is named by (in order) the platform's placeholder source as exposed on the device → the nearest visible text in its container → role + container — never dropped, never blank. **Probe first** (one on-device capture of a placeholder-only input's raw node, extending the debug capture dump to carry label sources), then the enumerator/observation change, then an observation fixture family for placeholder-only fields (retire-and-add if the shape changes) + `CandidateEnumeratorTest` pins. This generally unblocks search-driven goals (Test 3's Google search, Test 1's search route). Gate 10D.3 (JVM + emulator probe).

## 5. Vision amendments (owner ratifies before Stage 1 code)

* **01 §3** — writer roles/diagram: the re-plan carries the failure context; the writer may declare a bounded rollback.
* **04 §1** (failure-path paragraph, lines 42–47) — informed re-plan; immutable verified prefix; exactly one declared step back with the lesson; growth allowed; rollback never bypasses the honesty gate or a gate denial.
* **05 §2** — the `plan` row field gains the additive `rolledBack` + `lesson`.
* **06 §2a / §4** — the failure block is masked like any writer input; its page material is untrusted; a re-plan can never authorize what the risk gate denied.
* **07 §2 / §3** — the v4 fixture family (failure block + rollback variants), the placeholder-label observation family, and the gate-rule list gains the restructure/re-plan prompt.
* Version bumps in each amended header. Plus `Implementation Plan/PHASE_10D_informed_replan.md` (10C's doc structure), the master-index row, `.state/phase-10D-status.json`, compliance-log entries, and DOX updates (root `AGENTS.md` app bullet; `app/AGENTS.md` writer/loop/verification; `core/AGENTS.md` ladder bullet incl. the stale whitelist text).

## 6. Process — autonomous, per stage

**Role flow (owner-set, 2026-10-12): the glm-5.3-flash implementer budget is still exceeded, so I implement myself.** Per stage, loop until the stage is clean:

1. **I implement** the stage (code, fixtures, tests, DOX, .state records) — commits per round, branch `phase-10d` off `phase-10c`.
2. **Hand to the verifier** (`canon-verifier`, cold, independent re-runs of every gate).
3. Verifier returns findings → **I fix** → hand back to the verifier. Repeat until the verifier reports clean.
4. **I run the app verification on the emulator** via the android-emulator plugin (@Android-Dev): build, install, launch, run the stage's relevant behaviour ($0 stub harness where useful, the Phase-9 gate surface, a live cascade run on the emulator). Issues found → fix → hand back to the verifier → re-verify → re-run the app verification.
5. Stage closes when the JVM suite is green, the verifier is clean, and the emulator verification passes. **Then the next stage starts autonomously — no stopping, no asking between stages.**

After **all three stages** are done: the final emulator pass, a fresh APK, install on the S25+, and the handover to you for the **owner UAT** (canon-reporter presents it): re-run `open wikipedia.org and open the world war 2 page`, then the battery — `open bbc.com and open the News section`; `open google.com and search for mushroom recipes`; `open bbc.com and tell me the top news headline`; `open allrecipes.com and open the DINNER category, then open the first recipe shown` — monitored as before. Acceptance: (a) on any failure the rows show a **different** gen2 ladder with the verified prefix preserved, plus the rollback + lesson when one was declared; (b) Test 1 completes with a real tap or fails with that informed plan visible; (c) zero false completions; (d) the search goal reaches a real type-and-submit (Stage 3); (e) no regression on the JVM suite or the emulator gates. Live legs ≈ $0.05–0.20 (your key); no budget watchdog (standing decision). Design-interview rule assessed **N/A** for Stage 1 (live-log wording is data/verb law — recorded in the compliance log like 10B; if you later want the escalation card to *show* the cause, that is a UI change and gets its own interview).

## 7. Out of scope (parked, with reasons)

* **Cascade falling back to another intent's slate** — expected to be subsumed; revisit only if dead ends remain.
* **Escalation-card copy** (showing the failure cause) — UI; needs the design interview.
* **Phase 11** (shadow-in-product, result screen).
* **Road to the "hotel in Tallinn" class of goal** (identified, not built): ladders longer than 8 subtasks, cross-page working memory beyond the count tracker, the risk-gate pathway for consequential bookings.

## 8. Risks

The writer may ignore the monotonic/rollback rules — hence code checks, not prompt hope. Prompt growth per re-plan (~200–300 tokens), bounded by reusing existing excerpt/slate caps. The two-request fixture shape is new for the family — documented in the fixture's `note`. Local-function declaration order and the 64KB method limit are known 10C landmines. Rollback depth is a named constant so raising it later is one line + a doc amendment.