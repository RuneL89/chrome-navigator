# EMULATOR TEST ROUND — post-run-3 repair (build 5ec5f43, 2026-10-17 17:00–17:11)

**Headline:** All three new mechanisms behaved exactly as specified wherever they engaged — but both live runs ended STUCK_KILL on **pre-existing, explainable causes, not regressions**, and the emulator's webview shell proved too primitive to exercise the rotation legs against a real type-resistant field. The S25+ (Chrome) remains the real test bed for that.

**Setup:** AVD `pocketpet-test` (Pixel 5, API 35), build `5ec5f43` installed, a11y service on. The image ships no Chrome — runs drove `org.chromium.webview_shell`, which is on the browser allowlist by design (the Phase-10D battery precedent). Live key via the harness intent extra (`openrouterKey`), engine `jev`, `ladder true`. Two custom-goal runs; total writer+decision spend ≈ **$0.08–0.10**.

## Test A — "open google.com and search for mushroom recipes" → STUCK_KILL at t+164s

**The new machinery, proven live:**
- **Landed-text verification caught two accepted-but-dropped types** (`-> FAILED (TEXT_NOT_LANDED)` at t+26.9s and t+128.4s). Pre-repair, both were false successes — this is fix 1 working, on its first live outing.
- **Truthful rotation lines** fired (`fill: set-text rejected, the text did not land (text did not land) - n17`).
- **Recovery**: after the dropped type the engine re-targeted a real search field (n8), the text landed, and **the IME submit fired** ("the platform submit affordance fired (IME action on 'mushroom recipes')") — the fill+submit happy path is intact (no-regression check passed).
- **Informed re-plans** carried the right causes (`uncertain_after_handshake`) and **resumed mid-ladder** (gen2, gen3 both resumed at subtask 2); gen3 changed strategy (dropped the site-entry leg).
- **Exit discipline** held throughout (ART-4 refusals, anti-oscillation window, exit-conflict hint, L1/L2 recovery).

**The issue (for your evaluation):** the final subtask carried a WEAK done-condition; the code checker said the marker was present and the noul judge agreed the results page was there (0.83–0.90 across ~8 checks), but WEAK-guard completion requires the sustained-streak/completion-card path. The completion card fired once and **the harness auto-answered "keep going"** (no owner on the emulator) — after which no acceptance path remained (the card is max-one-per-task), the engine kept verifying, the stuck detector climbed to L3, STUCK_KILL. On the PHONE this same situation hands the card to **you** — you'd confirm and the run achieves. So: harness artifact of a real design edge — **the one-card-per-task + WEAK-guard combination has no acceptance path when the only human says "keep going"**. Not introduced by this repair; surfaced by it.

**Minor observation:** the first fill targeted a Finnish-labelled control ("Google-haku Kysy Googlelta Hae Googlesta" — the emulator locale served Finnish Google) scored 3.21 by stage-2, above the 3.0 floor. The drop was caught and the engine recovered on the next step. Watch item only.

## Test B — "book a hotel for next weekend in Tallinn" (start: booking.com) → STUCK_KILL at t+133s

**The environment is the blocker:** through the webview shell, booking.com never presented a typeable search surface —
- The form-fields bucket was **empty** (empty-slate grace 1/3→3/3, then no-good-target; top expected level 1.43–1.57 « the 3.0 floor — the honest "nothing to type into" verdict, and per law **never a blind tap**).
- The writer's `booking.com/stays` site entry landed on pages whose **titles shared nothing with the requested path** — the landing-coherence guard refused them (working as designed after the allrecipes-404 lesson).
- The noul judge scored the IS-DONE conditions 0.37–0.49 (UNCERTAIN) — the page genuinely wasn't what the ladder hoped.

**The machinery behaved lawfully everywhere it engaged** (informed re-plans with `empty_slate` then `uncertain_after_handshake` causes; resume preserved; open-site code path; coherence refusals; no-good-target evidence). But **fix 2's paste and tap-and-yield legs never got a live target** — there was no field to type into at all. Their logic is JVM-pinned (15 tests); the live proof needs Chrome on the S25+.

## Verdict & recommendation

- **No regression and no defect in the `5ec5f43` repair** — everything new behaved per spec; the verifier was CLEAN and the emulator agrees where it could observe.
- **Issue 1 (owner evaluation): the WEAK-guard + one-completion-card deadlock.** Real-world impact is low on the phone (you confirm the card; done), but it is the same class as the run-3 escalation-card burn: a no-win state after the single human touchpoint is consumed. Candidate future fix (parked, your call): allow the completion card to re-arm when the code checker + noul BOTH keep agreeing above a high bar (e.g. noul ≥ 0.85 three consecutive times post-card), or make the card's "keep going" bounded (N more attempts, then re-ask).
- **Run 4 on the S25+ is the real rotation test** — Chrome + real booking.com, where run 3 proved both type mechanisms get rejected. The build is verifier-clean and installed-on-emulator-proven; the S25+ install is one command away on your word.

**Artifacts:** `.state/hotel-iter/emu/emu-test{A,B}-logcat.log`, `test{A,B}-report.json` (full 100- and 57-line step logs).
