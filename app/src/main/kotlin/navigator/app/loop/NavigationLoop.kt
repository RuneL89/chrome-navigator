package navigator.app.loop

import kotlinx.coroutines.delay
import navigator.app.gen.AssistResult
import navigator.app.gen.PhraseResult
import navigator.app.observe.BrowserPackages
import navigator.app.observe.TreeSerializer
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.DecisionRecord
import navigator.core.decision.FallbackReporting
import navigator.core.decision.Intents
import navigator.core.decision.Option
import navigator.core.decision.NoulQuestion
import navigator.core.decision.TargetRubric
import navigator.core.decision.band
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.CandidateSource
import navigator.core.enumerate.ObservedNode
import navigator.core.enumerate.Recovery
import navigator.core.gate.ActionKind
import navigator.core.gate.AdditiveEngineCheck
import navigator.core.gate.CREDENTIAL_TYPING_CLASSES
import navigator.core.gate.FieldDescriptor
import navigator.core.gate.GateAction
import navigator.core.gate.GateCard
import navigator.core.gate.GateDecision
import navigator.core.gate.GateEvent
import navigator.core.gate.HClass
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate
import navigator.core.gate.TaskGate
import navigator.core.log.ActionDisposition
import navigator.core.log.BucketedQuestion
import navigator.core.log.CompressedBy
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.ExitCheckAnswer
import navigator.core.log.ExitCheckFallbackAnswer
import navigator.core.log.ExitCheckGuardLevel
import navigator.core.log.ExitCheckRow
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.ExitProvenance
import navigator.core.log.FallbackInfo
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedExitPath
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedGateEvent
import navigator.core.log.LoggedGuardLevel
import navigator.core.log.LoggedPlan
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import navigator.core.log.enumFromSerialName
import navigator.core.log.serialNameOf
import navigator.core.log.toLoggedBand
import navigator.core.decision.NoulResult
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.SubtaskPointer
import navigator.core.log.serialName
import navigator.core.state.StageViews
import navigator.core.state.StateTextBuilder

/**
 * The navigation loop (vision 04 — law). One step, in STRICT order (§2):
 *
 *   OBSERVE (fresh a11y read + URL bar; unusable tree -> graceful refusal, doc 03 §5)
 *   -> ENUMERATE (deterministic bucket taxonomy, doc 03 §3; the stage-2 slate grouping —
 *      browser-chrome excluded, the `finish` pseudo-action retired from slates)
 *   -> DECIDE — THE DECISION CASCADE (Phase 10B, vision 04 §2 v1.6.0): stage-1 ONE
 *      `choice` over the FIXED 9-intent vocabulary on the INTENT STATE VIEW; band
 *      routing — HIGH executes the intent (open-site -> the code VIEW intent;
 *      follow-link/activate-control/dismiss-nag -> stage-2 over the bucket slate; 
 *      fill-field -> stage-2 (form-fields) -> the WRITER-PHRASED text; submit ->
 *      stage-2 (buttons, the SUBMIT_TRAP gate semantics); go-back/scroll -> bare
 *      gestures; verify-done -> the shared finish-verification path), UNCERTAIN -> the
 *      bounded handshake (ONE assist per page-hash -> ONE re-ask -> the code fallback),
 *      LOW -> the wrong-page signal (go-back-leaning recovery). Stage-2: ONE batched
 *      score request ([DecisionEngine.scoreBatch]) over the slate; code resolves the
 *      argmax ([TargetRubric]) — a flat spread is NO-GOOD-TARGET, never a tap. The
 *      injected engine is STUB/Jev/router — the loop cannot tell, ART-2
 *   -> GATE ([RiskGate.classify]; uncertain mutation risk -> the ADDITIVE-ONLY engine
 *      check [AdditiveEngineCheck] (vision 06 §3, Phase 8E); then [TaskGate]; card
 *      events via [GatePresenter]) BEFORE any execution
 *   -> EXECUTE (gestures at node-map coordinates; text entry focus-first + paste, the
 *      text taken from a [TextSource]: the writer phrase, the stub phrase, or — doc 06
 *      §2a — the secret typed on the credential card, injected directly and never logged)
 *   -> LOG (exactly one [DecisionLogRow] per navigation decision)
 *
 * Exits (vision 04 §3): exactly one of goal-achieved (engine noul HIGH **AND** the code
 * completion guard — ART-4: a self-report alone is refused), budget exhausted (clean
 * stop, partial results, everything logged), stuck-kill, guard abort (double-deny),
 * user cancel. The graceful refusal (doc 03 §5) and the pre-navigation triage
 * `impossible` abort (vision 04 §1) are recorded as their own terminal states.
 *
 * v1.5.0 (owner-ratified after UAT 10.1-a): the subtask IS-DONE evaluation is no longer
 * gated on the engine volunteering `finish` — when the ACTIVE subtask's code
 * done-condition is satisfied, the loop runs the SAME anchored noul check itself
 * (rate-bounded: one per page-hash + active ordinal), and a ladder run's stuck L3
 * auto-kill routes through the owner escalation card instead of ending the task
 * silently. ART-4 is untouched: code satisfaction triggers the QUESTION; the advance
 * still requires engine noul HIGH AND the guard.
 *
 * Every Android edge sits behind an interface ([LoopObserver], [ActionExecutor],
 * [GatePresenter], [DecisionLogSink]) so the loop runs in plain JVM unit tests with
 * fakes (Gate 6.1, Robolectric-free). `ShadowRouter` (Phase 8) replaces the engine
 * reference at construction with zero loop changes (ART-2 proof point).
 */
class NavigationLoop(
    private val engine: DecisionEngine,
    private val observer: LoopObserver,
    private val executor: ActionExecutor,
    private val gatePresenter: GatePresenter,
    private val logSink: DecisionLogSink,
    private val completionCardPresenter: CompletionCardPresenter = AutoKeepGoingCompletionPresenter,
    private val spendMeter: SpendMeter = SpendMeter(),
    private val stateTextBuilder: StateTextBuilder = StateTextBuilder(),
    /**
     * The cascade's per-stage view builder (Phase 10B, vision 03 §4 v2.3.0): builds the
     * INTENT and TARGET state views every stage call rides. The canonical text (built by
     * [stateTextBuilder]) stays the row's logged/corpus record — the stage views never
     * replace it.
     */
    private val stageViews: StageViews = StageViews(),
    private val config: Config = Config(),
    /**
     * Cold-start edges (Phase 8D, vision 04 §1): when both are wired, the loop runs the
     * deterministic cold-start pre-step — derive the goal's start URL in code, probe
     * the browser, fire the VIEW intent when the browser is absent or on an unrelated
     * page — BEFORE triage and the first OBSERVE. NO engine is consulted. Null (the
     * default) skips the pre-step: tests and harnesses that manage the browser
     * themselves are unaffected.
     */
    private val browserProbe: BrowserProbe? = null,
    private val browserLauncher: BrowserLauncher? = null,
    /**
     * The plan-proposal seam (Phase 08G, vision 04 §1): consulted ONCE at triage. The
     * default ([PlanSource.None]) supplies NO plan — until the Phase-10 writer ships
     * the default path is byte-identical to the pre-08G loop (no state text, no step
     * log line, every row carries plan=null). A proposed plan is provenance only
     * (ART-2: context, never executor) — it rides the log rows and the run report.
     */
    private val planSource: PlanSource = PlanSource.None,
    /**
     * The ADDITIVE product-UI listener (Phase 9, phase doc §2.1): the overlay surface's
     * live facts ([LoopUiListener]). Default null ⇒ every existing path (all JVM tests,
     * the harness) is byte-identical; a wired listener is fire-and-forget presentation —
     * it can never steer the loop. Labels ride the redaction fold at source (doc 05 §2 /
     * doc 06 §2a), so a sanctioned secret echo can never reach the overlay.
     */
    private val uiListener: LoopUiListener? = null,
    /**
     * The task-ladder seam (Phase 10, vision 04 §1 v1.4.0): when wired, EVERY goal
     * restructures into a validated task ladder before any navigation — the ACTIVE
     * subtask rides the state text (v3), the pointer advances only on a verified
     * IS-DONE (doc 04 §3), triage and the cold-start pre-step are subsumed. The default
     * ([LadderSource.None]) supplies NO ladder: every existing path is byte-identical
     * (the phase doc's non-ladder paths — the harness's scripted stub goals keep
     * triage/cold-start).
     */
    private val ladderSource: LadderSource = LadderSource.None,
    /**
     * The answer-extraction seam (Phase 10, phase doc §2.1): ONE writer call at a
     * GOAL_ACHIEVED terminal over the goal + the code-MASKED final state (doc 06 §2a);
     * the answer rides the run report until Phase 11. Null = no extraction
     * (byte-identical).
     */
    private val answerExtractor: AnswerExtractor? = null,
    /**
     * The owner escalation card (Phase 10, vision 04 §1 v1.4.0): after 3 consecutive
     * subtask failures on a LADDER run — "Retry / Re-plan / Stop"; 30s timeout = Stop.
     * The default is the FAIL-SAFE stop (an unwired escalation never spins an
     * unattended run); the product wires the overlay card window.
     */
    private val escalationCardPresenter: EscalationCardPresenter = StopEscalationPresenter,
    /**
     * The cascade's writer seams (Phase 10B, phase doc §2.6 / vision 04 §2 v1.6.0): the
     * uncertainty handshake's assist and the fill-field phrase. Null (the default) =
     * no assist (an UNCERTAIN stage-1 falls straight to the code fallback) and every
     * fill-field phrase call refuses (routing to recovery — NEVER raw-goal typing).
     * Usage meters into the per-task [WriterBudget] at the restructure/re-plan/
     * extraction sites' pattern; the hard cap gates further writer calls.
     */
    private val assistSource: AssistSource? = null,
    private val phraseSource: PhraseSource? = null,
) {

    /**
     * The fallback-composition probe (vision 02 §7): when the injected engine is a
     * [FallbackEngine] (the `laya-fb-jev` mode), its per-decision records flow into the
     * log rows (`escalated` + `fallback`, doc 05 v1.2.0). A capability probe, not an
     * identity branch — every other engine leaves this null and the loop is unchanged
     * (the ART-2 law: the loop never branches on WHICH engine it holds; this reads a
     * generic reporting surface, the same seam [UsageFeed] uses). Null also when a
     * router wraps the composition — the laya modes run bare by design.
     */
    private val fallbackReporting: FallbackReporting? = engine as? FallbackReporting

    data class Config(
        /** Vision 03 §5: fewer meaningful nodes than this = unusable tree. */
        val minUsableNodes: Int = 5,
        /** The canvas-page rule (vision 03 §5): a content subtree with fewer nodes is
         *  "one undifferentiated node" (canvas pages, about:blank — and the transient
         *  collapse Chromium produces during a reload). Null skips the check. */
        val minPageNodes: Int = 5,
        /** Fresh re-reads before refusing. Patient on purpose: a page load on a loaded
         *  emulator can drop the Chromium a11y subtree for tens of seconds — ~30s of
         *  reads distinguishes a transient from a genuinely unreadable page. */
        val refuseAfterUnusableReads: Int = 20,
        /** Settle time between EXECUTE and the post-step observation (page render). */
        val settleMs: Long = 700,
        /** Breather between the fresh reads of an unusable tree (page render). */
        val unusableRetryDelayMs: Long = 1_500,
        /** The short settle after a card restore's VIEW intent (the browser re-fronts
         *  and the page refocuses) before the readiness wait's first re-read
         *  (2026-10-08 defect fix; 1000 -> 2000 — OneUI's resume+restore outlasted the
         *  old value, wasting the first re-read). */
        val restoreSettleMs: Long = 2_000,
        /** The readiness-wait budget after a card restore's VIEW intent (2026-10-08 S25+
         *  follower #3): re-reads on the [unusableRetryDelayMs] cadence until the
         *  usable-tree bar passes, at most ~this much wall-clock; expiry proceeds
         *  anyway — a still-dark page is the next observe's starvation machinery's job. */
        val restoreReadinessMs: Long = 10_000,
        val budget: BudgetLimits = BudgetLimits(),
    )

    /**
     * Explicit budget overrides (tests). Every field left null resolves to the
     * triage-selected [BudgetProfile] (vision 04 §5); a fully-default [BudgetLimits]
     * therefore means "the profile numbers", and the three original classes resolve to
     * the original Phase-6 defaults (Standard profile).
     */
    data class BudgetLimits(
        val stepsSoft: Int? = null,
        val stepsHard: Int? = null,
        val timeSoftMs: Long? = null,
        val timeHardMs: Long? = null,
        val spendSoftMicroUsd: Long? = null,
        val spendHardMicroUsd: Long? = null,
    )

    /**
     * Runs one task to exactly one terminal state. [isCancelled] is polled between
     * steps (the harness cancel button; never true in headless runs).
     * [fallbackIdentity] names the fallback engine of a composed engine (the `laya-fb-jev`
     * mode) so escalated rows can carry the `fallback {engine, model}` provenance
     * (doc 05 v1.2.0); null for every single-engine mode. [startUrl] is the goal's
     * scripted start URL (the harness goal's `startUrl`), the cold-start derivation's
     * fallback when the goal text names no site itself; null elsewhere.
     */
    suspend fun run(
        goal: String,
        goalId: String?,
        engineIdentity: EngineIdentity,
        clock: Clock = Clock.SYSTEM,
        isCancelled: () -> Boolean = { false },
        fallbackIdentity: EngineIdentity? = null,
        startUrl: String? = null,
    ): RunResult {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val startMs = clock.nowMs()
        val taskId = "task-$startMs"
        val stepLog = mutableListOf<String>()
        val softWarnings = mutableListOf<String>()

        fun line(message: String) {
            stepLog.add("[t+${clock.nowMs() - startMs}ms] $message")
        }

        // -- per-task machinery -------------------------------------------------------
        val taskGate = TaskGate()
        val stuck = StuckDetector()
        // Phase 10: the exit policy is PER SUBTASK on a ladder run (the SAME discipline
        // applies at subtask granularity, vision 04 §3 v1.4.0) — a fresh policy on every
        // pointer advance and every re-plan; the legacy path keeps one for the task.
        var exitPolicy = ExitPolicy()
        val history = mutableListOf<String>()
        val hints = mutableListOf<String>()
        // The credential values this task has received from gate cards (doc 06 §2a).
        // MEMORY ONLY: they feed the sanctioned-secret MASKING (every state-text
        // section, the question payloads, the HISTORY digest, the additive-consult
        // proposition, the execute step-log line, the report's boundary scrub) — a
        // value is never sent to any engine raw and never rendered raw into a log,
        // row, or report.
        val cardSecrets = mutableListOf<String>()

        // The deterministic redaction key set (doc 06 §2a via doc 05 §2): every
        // sanctioned secret PLUS — for one longer than the serializer's 40-char label
        // cap — its capped prefix, because the enumerator truncates labels BEFORE any
        // masking sees them (PREUAT2-4 (C): a long secret's truncated echo must mask
        // too, not leak its prefix). Order is stable: the full secret first, so the
        // prefix pass only ever fires where the truncation actually cut the value.
        fun redactionKeys(): List<String> = cardSecrets.flatMap { secret ->
            when {
                secret.isEmpty() -> emptyList()
                secret.length > TreeSerializer.MAX_TEXT_LENGTH ->
                    listOf(secret, secret.take(TreeSerializer.MAX_TEXT_LENGTH))
                else -> listOf(secret)
            }
        }
        var goalText = goal
        var recoveryArmed = false
        var rows = 0
        var gateEvents = 0
        var metaDecisions = 0
        var triageLabel = "untriaged"
        var triageClass: TriageClass? = null
        var budgetProfile: BudgetProfile? = null
        var guards: CompletionGuards? = null
        var entity: TargetEntity? = null
        // Task working memory for action-multi (vision 04 §1, Phase 08G): created at
        // triage, fed once per OBSERVE, resolved after EXECUTE; the guard reads it.
        var tracker: TargetTracker? = null
        // The triage-time plan proposal (Phase 08G): provenance only, ART-2. Null on
        // the default path (byte-identical to the pre-08G loop). Phase 10: on a LADDER
        // run this carries the ladder provenance instead (`<prompt-version>/gen<n>` +
        // the subtask instructions — the same additive `plan` row field, doc 05 §2).
        var plan: ProposedPlan? = null
        var exitSummary: ExitSummary? = null
        /** The completion card's resolution when it was shown (once per task). */
        var completionCardResolution: String? = null
        var lastPost: StepObservation? = null
        // The last-known page URL (2026-10-08 S25+ defect-follower): the per-step
        // URL-bar read is intermittently empty on some devices, so the card-restore
        // chain falls back to the most recent observation that DID carry a usable
        // page URL (stored normalized). Null until some observation yields one.
        var lastKnownPageUrl: String? = null

        // -- the task ladder (Phase 10, vision 04 §1 v1.4.0) ---------------------------
        // When [ladderSource] is wired, EVERY goal restructures before any navigation:
        // the ACTIVE subtask rides the state text (v3), the pointer advances only on a
        // verified IS-DONE (doc 04 §3), re-plans are bounded (≤2 per task, the owner
        // card's Re-plan draws the same cap), and the writer calls meter into the
        // SEPARATE per-task writer budget (vision 04 §5 v1.4.0, ART-5).
        var ladder: Ladder? = null
        var pointer: SubtaskPointer? = null
        var ladderVersion: String? = null
        var ladderGeneration = 0
        var rePlansUsed = 0
        var consecutiveSubtaskFailures = 0
        var extractedAnswer: ExtractedAnswer? = null
        val writerBudget = WriterBudget()
        // Declared with the ladder machinery: the ladder helpers (the failure path, the
        // re-plan) capture it; the main loop below mutates it per step.
        var stepIndex = 0
        // R1-A (vision 04 §3 v1.5.0): the LAST code-triggered IS-DONE check's
        // (page-hash, active-subtask ordinal) — the rate bound. v1.6.0 adds the bounded
        // held-hash re-checks: on an UNCHANGED page-hash whose guard stays satisfied,
        // re-checks fire every [ExitPolicyConfig.heldHashRecheckEverySteps] steps, at
        // most [ExitPolicyConfig.heldHashRecheckMax] per hash — the loop owns the
        // counters, [ExitPolicy.heldHashRecheckDue] is the pure decision.
        var lastCodeCheck: Pair<Int, Int>? = null
        // Phase 10C (vision 04 §1 v1.7.0): a CODE navigation was launched and its landing
        // URL has not been reconciled yet — the next observation's URL is the landing page.
        var pendingLandingReconcile = false
        // Phase 10C (vision 04 §2 v1.7.0): the bounded stage-1 re-ask — at most ONE per
        // page-hash when the chosen intent's slate is unusable; the re-ask's answer is
        // pinned for the step that follows (the re-ask consumes a step, like a check).
        var noGoodTargetReaskHash: Int? = null
        var forcedIntentNextStep: String? = null
        // Phase 10C (doc 04 §1 v1.7.0): the code-owned extraction record the
        // `answerExtracted` done-condition reads — one answer per report subtask ordinal.
        val reportAnswers = mutableMapOf<Int, ExtractedAnswer>()
        var codeCheckHash: Int? = null
        // Phase 10C (vision 04 §1 v1.7.0): the condition-honesty gate's memo — at most ONE
        // evaluation per (active subtask ordinal, page-hash); a page change or a pointer
        // advance re-arms it (the same discipline as the code-triggered check below).
        var honestyChecked: Pair<Int, Int>? = null
        var codeCheckCountOnHash = 0
        var codeCheckStepsSince = 0

        // The cascade's uncertainty handshake bound (vision 04 §2 v1.6.0, ART-5): ONE
        // assist per page-hash — a second UNCERTAIN on the same page falls straight to
        // the code fallback.
        var assistTriedHash: Int? = null

        // The empty-slate grace (gate-105 finding): a target-bearing intent whose slate
        // is EMPTY on a page whose a11y tree is still materializing (a partially-served
        // webview renders text before its links/controls) must not kill the task on the
        // first occurrence — up to [EMPTY_SLATE_GRACE_STEPS] observe-only steps give the
        // page time; a non-empty slate resets the counter; beyond the grace the ordinary
        // no-good-target fallback runs. All bounds code constants (ART-5).
        var consecutiveEmptySlates = 0

        // The scroll bounce (gate-105): the previous scroll candidate's id, so a repeated
        // scroll intent alternates direction when both are enumerated (never hammers a
        // page end — the stuck detector's screen-hash/zero-diff signals stay meaningful).
        var lastScrollCandidateId: String? = null

        // -- the no-overlay card restore (2026-10-08 pre-UAT defect) -------------------
        // A card presenter that had to bring the HARNESS to the front to show its card
        // (overlay permission absent) leaves Chrome in the background after the
        // resolution — the next gesture cannot land (dispatchGesture needs the
        // foreground) and the next observation reads the harness window (0 lines ->
        // spurious REFUSED). Before the loop continues the browser is re-fronted by
        // firing the VIEW intent for the page URL through the cold-start launcher seam.
        // No-op (byte-identical) when the card showed on the overlay window, when the
        // presenter never fronts (scripted/auto — every JVM test and harness auto mode),
        // when no launcher is wired, or when no page URL is known at all. The URL comes
        // from a preference chain (2026-10-08 S25+ defect-follower: the per-step URL-bar
        // read is intermittently empty on some devices): the card's URL, the current
        // observation's URL, then the last-known page URL — the serving source is
        // logged; the loud line stays for genuinely-never-had-a-URL runs. Every
        // candidate goes through [normalizePageUrl] (2026-10-08 owner-screenshot
        // follower: mobile Chrome OMITS the scheme, so the card showed
        // "127.0.0.1:8099/subscriptions.html" while the old http(s)-prefix guard
        // rejected it — scheme-less bar text now fires, loopback -> http else https).
        // After the intent the wait is OBSERVATION READINESS, not a fixed guess
        // (2026-10-08 S25+ follower #3): OneUI's resume+restore outlasted the settle, so
        // the first gesture landed wrong — [waitForBrowserReadiness] re-reads until the
        // tree is usable or the budget expires, then the step proceeds either way.

        /** Whether [presenter] fronted the harness for its most recent card. */
        fun frontedHarness(presenter: Any): Boolean =
            (presenter as? HarnessFrontAware)?.lastCardFrontedHarness() == true

        /**
         * Normalizes a raw URL-bar / card URL into a SCHEMED page URL, or null when the
         * text is not one — the restore's single page-URL gate. Mobile Chrome OMITS the
         * scheme in the URL bar, so the bar's visible text
         * ("127.0.0.1:8099/subscriptions.html") does not start with http(s):// and the
         * old http(s)-prefix guard rejected exactly the URL the card itself displayed
         * (2026-10-08 owner screenshot; the JVM tests passed on fully-schemed URLs).
         * Fully-schemed http(s) URLs return as-is; scheme-less text whose part before
         * any slash looks like a host (dotted host[:port], localhost, or [::1]) gets the
         * scheme prepended — http for the loopback hosts (the harness fixture server),
         * https otherwise; everything else ("about:blank", the "hidden" sentinel,
         * blank) is null. Deterministic; surfaced only at the restore log line.
         */
        fun normalizePageUrl(raw: String?): String? {
            if (raw == null) return null
            val text = raw.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("http://") || text.startsWith("https://")) return text
            val host = text.substringBefore('/')
            val loopback = host == "localhost" || host.startsWith("localhost:") ||
                host == "127.0.0.1" || host.startsWith("127.0.0.1:") ||
                host == "[::1]" || host.startsWith("[::1]:")
            if (!host.contains('.') && !loopback) return null
            return "${if (loopback) "http" else "https"}://$text"
        }

        /** Notes [o]'s page URL (normalized — scheme-less bar text is stored schemed)
         *  as the restore chain's last-known fallback; called at every observation. */
        fun notePageUrl(o: StepObservation) {
            normalizePageUrl(o.url)?.let { lastKnownPageUrl = it }
        }

        /**
         * The restore's readiness wait (2026-10-08 S25+ defect-follower #3, tightened by
         * the RC1 follower): the fixed settle expired before Chrome was front+interactive
         * on Samsung OneUI (the activity resume + VIEW-intent page restore outlast it),
         * so the focus-first tap landed wrong and the run starved. Re-observes on the
         * existing unusable-tree cadence until a read passes the readiness bar — which
         * is NOT merely a usable tree: the observation must ALSO name an allowlisted
         * browser as the ACTIVE window ([StepObservation.windowPackage]). The live-proven
         * RC1 defect: Chrome's tree is fully readable while Chrome sits in the
         * background, so the readability-only probe passed and the injection 13ms later
         * found no focused field. Bounded by [Config.restoreReadinessMs]; wall-clock
         * WITHIN the same step (the probes consume no budget and no steps — the same
         * precedent as the settle); on expiry the step proceeds anyway: a still-dark
         * page is the next observe's starvation machinery's job, not a new failure mode.
         * The outcome is logged loudly, naming the validated window package.
         */
        suspend fun waitForBrowserReadiness() {
            val startedAt = clock.nowMs()
            val maxRetries =
                (config.restoreReadinessMs / config.unusableRetryDelayMs.coerceAtLeast(1L)).toInt()
            var probe = observer.observe()
            notePageUrl(probe)
            var retries = 0
            while (!ready(probe) && retries < maxRetries) {
                retries += 1
                delay(config.unusableRetryDelayMs)
                probe = observer.observe()
                notePageUrl(probe)
            }
            if (ready(probe)) {
                line(
                    "restore: browser ready after ${clock.nowMs() - startedAt}ms " +
                        "(window=${probe.windowPackage.ifBlank { "unknown" }})",
                )
            } else {
                line(
                    "restore: browser not readable within budget - continuing " +
                        "(last window=${probe.windowPackage.ifBlank { "unknown" }})",
                )
            }
        }

        /**
         * Re-fronts the browser with the VIEW intent for the first candidate among
         * [candidates] (source label to raw URL, in preference order) that normalizes
         * ([normalizePageUrl]) to a page URL; short settle, then the readiness wait
         * ([waitForBrowserReadiness]) — logged with the serving source — noted
         * "(normalized)" when normalization changed the value.
         */
        suspend fun restoreBrowserForeground(what: String, vararg candidates: Pair<String, String?>) {
            val launcher = browserLauncher ?: return
            val served = candidates.firstNotNullOfOrNull { (source, raw) ->
                normalizePageUrl(raw)?.let { pageUrl -> Triple(source, raw, pageUrl) }
            }
            if (served == null) {
                line("restore: $what fronted the harness but the URL bar holds no page URL - no VIEW intent")
                return
            }
            val (source, raw, pageUrl) = served
            val normalizedNote = if (pageUrl != raw) " (normalized)" else ""
            line(
                "restore: $what fronted the harness (no-overlay path) - VIEW intent for " +
                    "$pageUrl$normalizedNote ($source)",
            )
            launcher.launch(pageUrl)
            delay(config.restoreSettleMs)
            waitForBrowserReadiness()
        }

        fun partialResult(): String =
            lastPost?.nodes
                ?.filter { !it.clickable && !it.editable && it.text.isNotBlank() }
                ?.take(PARTIAL_RESULT_MAX_LINES)
                ?.joinToString(" ") { it.text }
                ?.take(PARTIAL_RESULT_MAX_CHARS)
                .orEmpty()

        /** The run report's ladder block (Phase 10): the ACTIVE ladder + its pointer state. */
        fun ladderReport(terminal: TerminalState): LadderRunReport? {
            val l = ladder ?: return null
            val p = pointer ?: return null
            return LadderRunReport(
                version = ladderVersion ?: l.versionTag("unknown", ladderGeneration),
                generation = ladderGeneration,
                summary = l.summary,
                taskClass = serialNameOf(LadderTaskClass.serializer(), l.taskClass),
                subtasks = l.subtasks.mapIndexed { index, subtask ->
                    LadderRunReport.LadderSubtaskReport(
                        instruction = subtask.instruction,
                        done = DoneConditionChecker.render(subtask.done),
                        completed = index < p.completedCount ||
                            (index == p.index && terminal == TerminalState.GOAL_ACHIEVED),
                        active = index == p.index && terminal != TerminalState.GOAL_ACHIEVED,
                    )
                },
            )
        }

        fun finish(terminal: TerminalState, detail: String, steps: Int): RunResult {
            line("terminal: $terminal - $detail")
            // Phase 9: the overlay surface learns the one terminal state (fire-and-forget).
            uiListener?.onTaskState(LoopUiListener.TaskStateUpdate(terminal, detail))
            // doc 06 §2a render law (via doc 05 §2): reports render credential-sourced
            // text as [credential-redacted]. Construction keeps the value out of every
            // line (the masking seams above); this render-boundary pass guarantees it
            // even if a PAGE echoes the secret back into content text (partialResult) —
            // the one place page text lands verbatim in a report.
            fun scrub(text: String): String =
                redactionKeys().fold(text) { acc, key -> acc.replace(key, TextSource.REDACTED) }
            return RunResult(
                goalId = goalId,
                goal = goal,
                taskId = taskId,
                triage = triageLabel,
                triageClass = triageClass?.let { serialNameOf(TriageClass.serializer(), it) },
                budgetProfile = budgetProfile?.name,
                terminal = terminal,
                terminalDetail = detail,
                steps = steps,
                rowsLogged = rows,
                metaDecisions = metaDecisions,
                // Zero-log-gaps invariant (Gate 6.2): navigation decisions - rows.
                // Rephase-recovery meta-decisions are goal-text operations (no page
                // action, no row) and are EXCLUDED here via metaDecisions so the
                // invariant stays "every navigation decision logged exactly once".
                logGaps = steps - rows - metaDecisions,
                gateEvents = gateEvents,
                spendMicroUsd = spendMeter.spentMicroUsd,
                elapsedMs = clock.nowMs() - startMs,
                softWarnings = softWarnings.distinct(),
                stepLog = stepLog.map(::scrub),
                partialResult = scrub(partialResult()),
                exit = exitSummary,
                targets = tracker?.summary(),
                plan = plan,
                ladder = ladderReport(terminal),
                answer = extractedAnswer?.let { it.copy(text = scrub(it.text)) },
                writerSpendMicroUsd = writerBudget.spentMicroUsd,
            )
        }

        /**
         * The fallback record of the decision that JUST completed (null for any
         * single-engine mode). The strictly-sequential loop reads the composed engine's
         * slot right after its own call; a stale/mismatched record is a wiring bug and
         * fails loudly rather than mis-attributing an escalation.
         */
        fun readEscalation(questionName: String): DecisionRecord? {
            val record = fallbackReporting?.lastRecord() ?: return null
            check(record.questionName == questionName) {
                "fallback record '${record.questionName}' does not match the question just " +
                    "decided '$questionName' - escalation bookkeeping is stale"
            }
            return record
        }

        // -- the decision cascade's helpers (Phase 10B, vision 04 §2 v1.6.0) -------------

        /** The SUBTASK line of every stage view: the ACTIVE subtask, or the goal text. */
        fun subtaskInstruction(): String =
            pointer?.active?.instruction ?: goalText

        /** The DONE WHEN line: the ACTIVE subtask's rendered condition, or the goal text. */
        fun doneWhenText(): String =
            pointer?.let { "done: " + DoneConditionChecker.render(it.active.done) } ?: goalText

        /**
         * ONE uncertainty-handshake assist, metered into the per-task writer budget at
         * the restructure/re-plan/extraction pattern (SOFT/HARD warnings; the hard cap
         * gates the call). The caller masks sanctioned secrets out of [pageMaterial]
         * BEFORE this call (doc 06 §2a). Null = refused output / honesty skip / no
         * source / no budget room — the skip-to-fallback signal.
         */
        suspend fun runAssist(subtask: String, doneCondition: String, pageMaterial: String): AssistResult? {
            val source = assistSource ?: return null
            if (!writerBudget.hasHardRoom()) {
                line("handshake: the writer budget's hard cap is reached - no assist")
                return null
            }
            val result = source.assist(subtask, doneCondition, pageMaterial) ?: return null
            writerBudget.record(result.usage.costMicroUsd).let { check ->
                if (check != WriterBudget.Check.OK) softWarnings.add("writer budget ${check.name}")
            }
            return result
        }

        /**
         * ONE writer-phrased typed text for a fill-field step, metered like [runAssist].
         * Null = refused output / honesty skip / no source / no budget room — the
         * fill-field step FAILS TO RECOVERY (never raw-goal typing, vision 04 §2 v1.6.0).
         */
        suspend fun runPhrase(subtask: String, fieldLabel: String): PhraseResult? {
            val source = phraseSource ?: return null
            if (!writerBudget.hasHardRoom()) {
                line("fill-field: the writer budget's hard cap is reached - no phrase")
                return null
            }
            val result = source.phrase(subtask, fieldLabel) ?: return null
            writerBudget.record(result.usage.costMicroUsd).let { check ->
                if (check != WriterBudget.Check.OK) softWarnings.add("writer budget ${check.name}")
            }
            return result
        }

        suspend fun logRow(
            stepIndex: Int,
            state: StateTextBuilder.StateText,
            question: ChoiceQuestion,
            choice: ChoiceResult,
            candidates: CandidateList,
            executed: ExecutedAction,
            gateEvent: LoggedGateEvent?,
            outcome: Outcome,
            escalation: DecisionRecord? = null,
            exit: ExitProvenance? = null,
            typedSecret: Boolean = false,
            targetScores: Map<String, Double>? = null,
            targetStateText: String? = null,
        ) {
            // Escalated rows (doc 05 v1.2.0): teacherAnswer stays the PRIMARY's own
            // answer when it produced one (vision 05 §2 — the teacher is the primary of
            // record); the fallback's answer — the one EXECUTED (vision 02 §7) — rides
            // the fallback field. On a primary hard failure there is no primary answer:
            // the executed (fallback) answer stands in, and escalated=true makes the
            // provenance unambiguous.
            val escalated = escalation?.escalated == true
            val fallbackInfo = if (escalated) {
                val identity = requireNotNull(fallbackIdentity) {
                    "an escalated decision needs the fallback engine identity for its row"
                }
                FallbackInfo(
                    engine = identity.configId,
                    model = identity.model,
                    answer = requireNotNull(escalation!!.fallback) {
                        "an escalated record must carry the fallback's answer"
                    },
                )
            } else {
                null
            }
            val primaryChoice = escalation?.primary as? ChoiceResult
            val teacherAnswer = if (escalated && primaryChoice != null) {
                TeacherAnswer(primaryChoice.chosenId, primaryChoice.probabilities, primaryChoice.confidence)
            } else {
                TeacherAnswer(choice.chosenId, choice.probabilities, choice.confidence)
            }
            val row = DecisionLogRow(
                rowId = "$taskId-step-$stepIndex",
                taskId = taskId,
                goal = goal,
                stepIndex = stepIndex,
                timestamp = clock.nowMs(),
                stateText = state.text,
                stateFormatVersion = state.formatVersion,
                triageClass = requireNotNull(triageClass) { "triage must run before the first step" },
                questionPrimary = question,
                questionShadow = shadowQuestion(candidates, redactionKeys()),
                teacher = engineIdentity,
                teacherAnswer = teacherAnswer,
                shadow = null, // shadowMiss: the shadow engine arrives in Phase 8 (vision 02 §4)
                shadowAnswer = null,
                executedAction = executed,
                gateEvent = gateEvent,
                outcome = outcome,
                compressedBy = if (state.compressedBy != null) CompressedBy.CODE else null,
                shadowSampled = false,
                escalated = escalated,
                fallback = fallbackInfo,
                exit = exit,
                typedSecret = typedSecret,
                // Plan-proposal provenance (Phase 08G, vision 05 §2 `plan`): the task's
                // triage-time plan rides every row — null on the default path.
                plan = plan?.let { LoggedPlan(it.version, it.subgoals) },
                // Cascade stage-2 evidence (doc 05 §2 v1.6.0): the executed slate's
                // expected levels + the TARGET STATE VIEW the batch rode (the score
                // case's state law). Null on non-target-bearing steps; the ROUTER's
                // enrichment owns targetAgreement (the row is written primary-only).
                targetScores = targetScores,
                targetStateText = targetStateText,
            )
            logSink.write(row)
            rows += 1
            if (gateEvent != null) gateEvents += 1
        }

        /** Assesses the stuck window; L1 injects a hint, L2 arms the recovery set,
         *  L3 returns true (the would-be auto-kill — the rescue coach is Phase 10). On a
         *  LADDER run the L3 call sites route through [handleStuckKill] (v1.5.0: the
         *  GUARANTEED owner card) and the give-up site through
         *  [handleSubtaskFailureOrKill] (the bounded re-plan path's input); the legacy
         *  path keeps the direct task-level kill. */
        fun assessStuck(): Boolean {
            val assessment = stuck.assess() ?: return false
            when (assessment.level) {
                1 -> {
                    line("stuck: L1 hint injected (${assessment.signals})")
                    hints.add(StuckDetector.HINT)
                    // Sixth signal (vision 04 §3 v1.2.0): the exit-conflict note rides
                    // the L1 hint so the engine re-orients to evidence, not finishing.
                    assessment.exitConflictEvidence?.let { evidence ->
                        val conflictHint = StuckDetector.exitConflictHint(evidence)
                        line("stuck: $conflictHint")
                        hints.add(conflictHint)
                    }
                    // Vision 04 §4 (Phase 08G): for action-multi tasks the tracker's
                    // progress lines ride the L1 hint so the engine re-orients to the
                    // PENDING targets (the HISTORY digest is never the memory of record).
                    val multiTracker = tracker
                    if (triageClass == TriageClass.ACTION_MULTI && multiTracker != null) {
                        val progress = multiTracker.progressLines()
                        progress.forEach { hints.add(it) }
                        line("stuck: ${progress.first()}")
                    }
                }
                2 -> {
                    line("stuck: L2 - next decide comes from the recovery set (${assessment.signals})")
                    recoveryArmed = true
                }
                else -> return true
            }
            return false
        }

        // -- the task ladder machinery (Phase 10, vision 04 §1 v1.4.0) ----------------

        /** Maps the validated ladder's task class onto the row vocabulary (additive: read→lookup,
         *  action→action, multi→action-multi — the triage classes' budget/guard semantics). */
        fun ladderTriageClass(taskClass: LadderTaskClass): TriageClass = when (taskClass) {
            LadderTaskClass.READ -> TriageClass.LOOKUP
            LadderTaskClass.ACTION -> TriageClass.ACTION
            LadderTaskClass.MULTI -> TriageClass.ACTION_MULTI
        }

        /**
         * (Re)builds the per-ladder machinery from a VALIDATED ladder (ART-4: code
         * derives everything from the whitelist shapes — the class mapping, the guards,
         * the tracker, the row provenance via the EXISTING additive `plan` field). The
         * budget frame is deliberately NOT rebuilt on a re-plan: the decision-spend
         * budget is untouched by re-planning (vision 04 §5 v1.4.0); the first-derived
         * profile stays the task's frame.
         */
        fun applyLadder(newLadder: Ladder, version: String) {
            ladder = newLadder
            pointer = SubtaskPointer(newLadder)
            ladderVersion = version
            triageClass = ladderTriageClass(newLadder.taskClass)
            triageLabel = triageClass!!.serial()
            entity = TargetEntity.extract(goal)
            val taskTracker = if (newLadder.taskClass == LadderTaskClass.MULTI) TargetTracker() else null
            tracker = taskTracker
            guards = CompletionGuards(triageClass!!, entity, taskTracker)
            exitPolicy = ExitPolicy()
            consecutiveSubtaskFailures = 0
            lastCodeCheck = null // a fresh ladder: the code-check rate bound starts clean
            codeCheckHash = null
            codeCheckCountOnHash = 0
            codeCheckStepsSince = 0
            // Ladder provenance rides every row via the EXISTING additive `plan` field
            // (doc 05 §2: the writer-LLM plans carry their PROMPT_VERSION) — no schema
            // surgery; version = "<prompt-version>/gen<n>".
            plan = ProposedPlan(version, newLadder.subtasks.map { it.instruction })
        }

        /** The ACTIVE-SUBTASK state-text line (code-authored, flattened, one line). */
        fun activeSubtaskLine(): String? {
            val p = pointer ?: return null
            return "subtask ${p.activeOrdinal}/${p.total}: ${p.active.instruction} " +
                "(done: ${DoneConditionChecker.render(p.active.done)})"
        }

        /** The LADDER PROGRESS lines: completed subtasks one line each + count-shaped working memory. */
        fun ladderProgressLines(): List<String>? {
            val p = pointer ?: return null
            val l = ladder ?: return null
            return buildList {
                p.completed().forEachIndexed { index, subtask ->
                    add("[x] subtask ${index + 1}/${p.total}: ${subtask.instruction}")
                }
                // Working-memory counts for count-shaped done-conditions ("3 of 5 followed"
                // reads 3/5 while pending) — the tracker IS the code-owned memory.
                val processed = tracker?.processedCount() ?: 0
                l.subtasks.mapNotNull { it.done as? DoneCondition.CountReached }
                    .distinctBy { it.target }
                    .forEach { add("${it.target} $processed/${it.n}") }
            }.ifEmpty { listOf("(no subtasks completed yet)") }
        }

        /** The corner-log ladder block (the pure [navigator.app.overlay.LadderBlock] formatter). */
        fun ladderBlockLines(): List<String> {
            val l = ladder ?: return emptyList()
            val p = pointer ?: return emptyList()
            return navigator.app.overlay.LadderBlock.render(
                navigator.app.overlay.LadderBlock.State(
                    summary = l.summary,
                    instructions = l.subtasks.map { it.instruction },
                    doneFlags = List(l.subtasks.size) { index -> index < p.completedCount },
                    activeIndex = p.index,
                    generation = ladderGeneration,
                ),
            )
        }

        /** Fires the ladder's overlay surfaces: the pinned block + the cards' ACTIVE-SUBTASK display. */
        fun publishLadderSurfaces() {
            uiListener?.onLadderBlock(ladderBlockLines())
            activeSubtaskLine()?.let { uiListener?.onPlan(listOf(it)) }
        }

        /** The working-memory evidence the code-side IS-DONE checker reads (all code-read facts). */
        fun ladderEvidence(o: StepObservation, candidates: CandidateList): DoneConditionChecker.Evidence =
            DoneConditionChecker.Evidence(
                url = o.url,
                title = o.title,
                pageText = o.lines + o.excerpt,
                candidateLabels = candidates.flat.map { it.label },
                counters = mapOf(DoneConditionChecker.WORKING_MEMORY_KEY to (tracker?.processedCount() ?: 0)),
                answersProduced = reportAnswers.size,
            )

        /**
         * The code-side IS-DONE guard level over the ACTIVE subtask's done-condition —
         * the finish branch's EXACT computation, shared with the code-triggered check
         * (vision 04 §3 v1.5.0: identical construction on both entries).
         */
        fun subtaskGuardLevel(p: SubtaskPointer, evidence: DoneConditionChecker.Evidence): GuardLevel =
            when {
                !DoneConditionChecker.satisfied(p.active.done, evidence) -> GuardLevel.NONE
                DoneConditionChecker.guardStrength(p.active.done) ==
                    DoneConditionChecker.GuardStrength.STRONG -> GuardLevel.STRONG
                else -> GuardLevel.WEAK
            }

        /**
         * The anchored noul question for the ACTIVE subtask — identical construction on
         * both finish-check entries (the engine-initiated finish and the code-triggered
         * check): the v3 ATOMIC [AnchoredNoul.subtaskQuestion] over the subtask's
         * done-condition shape and the page identity (vision 04 §3 v1.6.0).
         */
        fun subtaskNoulQuestion(p: SubtaskPointer, o: StepObservation): NoulQuestion =
            AnchoredNoul.subtaskQuestion(
                done = p.active.done,
                pageTitle = o.title,
                pageUrl = o.url,
            )

        /**
         * The v1.6.0 sustained-streak condition key: the ACTIVE done-condition's rendered
         * text on a ladder run (the streak survives re-plans that keep the condition and
         * resets when the condition/pointer changes), the task-level condition text on a
         * legacy run.
         */
        fun exitConditionKey(): String {
            val p = pointer
            return if (p != null) {
                "subtask:" + DoneConditionChecker.render(p.active.done)
            } else {
                "task:" + (entity?.text ?: goalText)
            }
        }

        /**
         * The outcome-based site entry for the ACTIVE subtask (vision 04 §1 v1.4.0):
         * a `urlHost` subtask whose instruction names opening a site is satisfied BY
         * CODE through the best mechanism — deterministic + logged; the old cold-start,
         * folded under the ladder. When no URL resolves (or no launcher is wired) the
         * subtask proceeds under ordinary engine navigation (ART-2 intact).
         */
        suspend fun enterSubtask() {
            val p = pointer ?: return
            val entryPlan = SiteEntry.plan(goalText, p.active.instruction, p.active.done)
            if (entryPlan == null) {
                line("ladder: subtask ${p.activeOrdinal}/${p.total} - no site entry (engine navigates)")
                return
            }
            val launcher = browserLauncher
            if (launcher == null) {
                line(
                    "site-entry: ${entryPlan.mechanism.name.lowercase()} planned for " +
                        "${entryPlan.url} (${entryPlan.source}) but no launcher is wired - " +
                        "subtask ${p.activeOrdinal}/${p.total} proceeds under engine navigation",
                )
                return
            }
            line(
                "site-entry: VIEW intent for ${entryPlan.url} (${entryPlan.source}) - " +
                    "subtask ${p.activeOrdinal}/${p.total}",
            )
            launcher.launch(entryPlan.url)
            pendingLandingReconcile = true
            delay(config.restoreSettleMs)
            waitForBrowserReadiness()
        }

        /**
         * One bounded re-plan (vision 04 §1 v1.4.0): at most [MAX_REPLANS] per task,
         * metered into the SEPARATE writer budget; the owner card's Re-plan draws the
         * SAME cap. Returns false when the re-plan refused (invalid ladder — treated as
         * no capacity) so the caller proceeds down the failure path.
         */
        suspend fun replan(): Boolean {
            rePlansUsed += 1
            val generation = ladderGeneration + 1
            line(
                "ladder: re-plan $generation (bounded: ${MAX_REPLANS - rePlansUsed} left after " +
                    "this one) - asking the writer over the goal + failure context",
            )
            // doc 06 §2a: no secret ever enters a writer prompt — the goal text folds
            // the sanctioned-secret redaction before the writer sees a re-plan.
            val maskedGoal = redactionKeys().fold(goal) { acc, key -> acc.replace(key, TextSource.REDACTED) }
            val restructured = try {
                ladderSource.restructure(maskedGoal, generation)
            } catch (t: Throwable) {
                line("ladder: re-plan failed loudly (${t::class.simpleName}) - treated as no re-plan capacity")
                null
            }
            if (restructured == null) {
                line("ladder: re-plan REFUSED (no valid ladder) - treated as no re-plan capacity")
                return false
            }
            writerBudget.record(restructured.costMicroUsd).let { check ->
                if (check != WriterBudget.Check.OK) softWarnings.add("writer budget ${check.name}")
            }
            ladderGeneration = generation
            applyLadder(restructured.ladder, restructured.version)
            line("ladder: re-planned to ${restructured.version} (${restructured.ladder.subtasks.size} subtask(s))")
            publishLadderSurfaces()
            enterSubtask()
            return true
        }

        /**
         * The owner escalation card (vision 04 §1 v1.4.0 + the v1.5.0 guarantee), shared
         * by both failure entries: it discloses the ACTIVE subtask, the failure count,
         * and the remaining bounded re-plan capacity. Retry resets the failure streak AND
         * the stuck detector's escalations (the detector re-escalates from L1 — it never
         * re-trips straight back to L3); Re-plan draws the SAME bounded cap as the
         * automatic path; Stop — and the 30s timeout, which the presenter resolves as the
         * fail-safe Stop — ends the task with the honest cause.
         */
        suspend fun arbitrateSubtaskFailure(stuckCause: String?): RunResult? {
            val p = requireNotNull(pointer)
            val resolution = escalationCardPresenter.present(
                EscalationCard(
                    subtaskInstruction = p.active.instruction,
                    doneCondition = DoneConditionChecker.render(p.active.done),
                    failureCount = consecutiveSubtaskFailures,
                    rePlansRemaining = if (writerBudget.hasHardRoom()) {
                        (MAX_REPLANS - rePlansUsed).coerceAtLeast(0)
                    } else {
                        0 // the writer budget's hard cap is reached: no re-plan is lawful
                    },
                ),
            )
            return when (resolution) {
                EscalationResolution.RETRY -> {
                    consecutiveSubtaskFailures = 0
                    stuck.resetEscalations()
                    line("ladder: the owner said RETRY - the same subtask is re-attempted (stuck escalations reset)")
                    return null
                }

                EscalationResolution.REPLAN -> {
                    if (rePlansUsed < MAX_REPLANS && writerBudget.hasHardRoom() && replan()) {
                        line("ladder: the owner said RE-PLAN - generation $ladderGeneration is live")
                        return null
                    }
                    line("ladder: the owner said RE-PLAN but the bounded cap/budget is exhausted - stopping")
                    return finish(
                        TerminalState.STUCK_KILL,
                        "subtask failed $consecutiveSubtaskFailures consecutive times and the " +
                            "re-plan cap is exhausted (owner card: re-plan)",
                        stepIndex,
                    )
                }

                EscalationResolution.STOP -> {
                    val detail = if (stuckCause != null) {
                        "$stuckCause - the owner said stop (escalation card)"
                    } else {
                        "subtask failed $consecutiveSubtaskFailures consecutive times - " +
                            "the owner said stop (escalation card)"
                    }
                    return finish(TerminalState.STUCK_KILL, detail, stepIndex)
                }
            }
        }

        /**
         * The bounded failure path (vision 04 §1 v1.4.0): a subtask that cannot complete
         * (L2 give-up) counts one consecutive failure; below the card threshold
         * a bounded re-plan fires automatically when capacity remains; at
         * [SUBTASK_FAILURE_CARD_THRESHOLD] the OWNER arbitrates ("Retry / Re-plan / Stop";
         * 30s timeout = Stop — the fail-safe presenter default). Without re-plan capacity
         * the subtask KEEPS GOING under the loop's own machinery (the stuck detector and
         * the task budgets bound it) — the owner card, not the empty cap, is the
         * arbiter. Returns the task's terminal [RunResult] when the task must end, or
         * null when the loop continues (retry/re-plan/keep-going). The LEGACY path (no
         * ladder) keeps the old task-level STUCK_KILL.
         */
        suspend fun handleSubtaskFailureOrKill(cause: String): RunResult? {
            val p = pointer ?: return finish(TerminalState.STUCK_KILL, cause, stepIndex)
            consecutiveSubtaskFailures += 1
            line("ladder: subtask ${p.activeOrdinal}/${p.total} FAILED ($cause) - consecutive failures: $consecutiveSubtaskFailures")
            history.add("step $stepIndex: subtask ${p.activeOrdinal} failed - $cause")
            if (consecutiveSubtaskFailures >= SUBTASK_FAILURE_CARD_THRESHOLD) {
                return arbitrateSubtaskFailure(stuckCause = null)
            }
            // Below the card threshold: a bounded re-plan when capacity remains; otherwise
            // the loop continues — the card is the arbiter, never the empty cap.
            if (rePlansUsed < MAX_REPLANS && writerBudget.hasHardRoom() && replan()) return null
            return null
        }

        /**
         * The stuck L3 auto-kill interception (vision 04 §1 v1.5.0, owner-ratified after
         * UAT 10.1-a — the escalation card is a GUARANTEE, not a race): on a LADDER run
         * the kill never ends the task silently — the owner card arbitrates before any
         * kill lands. The L3 kill is itself a subtask failure (the ACTIVE subtask's
         * IS-DONE cannot be verified), so it counts one failure, then the card asks.
         * The LEGACY path (no ladder) keeps the direct task-level STUCK_KILL,
         * byte-stable.
         */
        suspend fun handleStuckKill(cause: String): RunResult? {
            val p = pointer ?: return finish(TerminalState.STUCK_KILL, cause, stepIndex)
            consecutiveSubtaskFailures += 1
            line(
                "ladder: subtask ${p.activeOrdinal}/${p.total} STUCK-KILL intercepted - " +
                    "the owner card is guaranteed ($cause) - consecutive failures: " +
                    "$consecutiveSubtaskFailures",
            )
            history.add("step $stepIndex: subtask ${p.activeOrdinal} stuck - $cause")
            return arbitrateSubtaskFailure(stuckCause = cause)
        }

        /**
         * The SHARED finish-verification path (vision 04 §3 v1.5.0): the anchored noul
         * consult, the escalation capture, the exit policy's three paths (incl. the
         * once-per-task completion card), and the ExitCheckRow write — ONE copy used by
         * BOTH the engine-initiated `finish` branch and the code-triggered IS-DONE check.
         * [trigger] names the asking path on the step log ("finish" = the engine chose
         * it; "code-triggered IS-DONE check" = the loop asked because the code guard was
         * already satisfied). Returns the verdict; the CALLER owns the entry-specific
         * bookkeeping (the engine's decision row + the anti-oscillation/stuck feed vs
         * the code check's silence). [state] and [obs] are the step's built state text
         * and observation — the check's code-read evidence.
         */
        suspend fun verifyFinishCheck(
            trigger: String,
            guardLevel: GuardLevel,
            noulQuestion: NoulQuestion,
            obs: StepObservation,
            state: StateTextBuilder.StateText,
            conditionKey: String = exitConditionKey(),
        ): ExitDecision {
            uiListener?.onThinking(true)
            val noul = engine.judge(state.text, noulQuestion)
            uiListener?.onThinking(false)
            // The noul escalation is captured IMMEDIATELY (the engine's single slot):
            // the anchored judge is its own decision, separate from the step's choose.
            val noulEscalation = readEscalation(noulQuestion.name)
            line(
                "decide: $trigger -> verification: noul=${noul.pTrue} (${band(noul.pTrue)}), " +
                    "guard=${guardLevel.name}, " +
                    "guard state: ${guards!!.describe()}, " +
                    "proposition=v${AnchoredNoul.ANCHORED_NOUL_VERSION}",
            )
            val verdict: ExitDecision = when (
                val first = exitPolicy.onFinishCheck(
                    conditionKey = conditionKey,
                    guardLevel = guardLevel,
                    noul = noul.pTrue,
                    guardDetail = guards!!.refusalDetail(),
                    // Phase 10C (doc 04 §3 v1.7.0): the Path A STRONG requirement applies to
                    // LADDER runs — the production shape where proxy conditions caused the
                    // battery's false completions; legacy scripted runs keep the v1.2.0 law.
                    ladderRun = ladder != null,
                )
            ) {
                is ExitDecision.AskOwner -> {
                    line(
                        "exit: ${first.refusedChecks} consecutive guard-pass refusals - " +
                            "completion card (max one per task, vision 04 §3 Path C)",
                    )
                    val resolution = completionCardPresenter.present(
                        CompletionCard(
                            goal = goal,
                            entity = entity?.text,
                            guardLevel = first.guardLevel,
                            noul = first.noul,
                            refusedChecks = first.refusedChecks,
                        ),
                    )
                    val cardFronted = frontedHarness(completionCardPresenter)
                    val cardVerdict: ExitDecision = when (resolution) {
                        CompletionResolution.CONFIRM_DONE -> {
                            completionCardResolution = resolution.name
                            line("exit: owner CONFIRMED done - accepting via OWNER_CONFIRM")
                            ExitDecision.Accept(ExitPath.OWNER_CONFIRM, first.guardLevel, first.noul)
                        }
                        CompletionResolution.KEEP_GOING -> {
                            completionCardResolution = resolution.name
                            ExitDecision.Refuse(
                                "the owner said keep going on the completion card",
                                first.guardLevel,
                                first.noul,
                            )
                        }
                        CompletionResolution.TIMEOUT -> {
                            completionCardResolution = resolution.name
                            ExitDecision.Refuse(
                                "the completion card timed out (= keep going, fail-safe)",
                                first.guardLevel,
                                first.noul,
                            )
                        }
                    }
                    // The keep-going resolutions continue the loop — on the
                    // no-overlay path Chrome is still backgrounded behind the
                    // harness, so it is re-fronted (VIEW intent for the current
                    // page) before the loop continues. CONFIRM_DONE ends the task —
                    // the harness's terminal auto-return fronts the harness; never
                    // restore.
                    if (cardFronted && cardVerdict is ExitDecision.Refuse) {
                        restoreBrowserForeground(
                            "the completion card",
                            "observation" to obs.url,
                            "last-known" to lastKnownPageUrl,
                        )
                    }
                    cardVerdict
                }
                else -> first
            }
            // Vision 05 §2 amendment 2026-10-06, rule a: EVERY finish check —
            // accepted or refused — logs exactly one insert-only ExitCheckRow; the
            // refusals are the decoy contrast that teaches exit calibration. Rule b:
            // `answer` is the PRIMARY's noul; on a primary hard failure (no primary
            // answer) the executed fallback answer stands in — the decision-row
            // precedent — and `fallbackAnswer` carries the executed judgment whenever
            // the check escalated.
            fun exitCheckRow(): ExitCheckRow {
                val escalated = noulEscalation?.escalated == true
                val primaryPTrue = (noulEscalation?.primary as? NoulResult)?.pTrue
                val fallbackPTrue = (noulEscalation?.fallback as? NoulResult)?.pTrue
                val answerPTrue = if (escalated) {
                    primaryPTrue ?: requireNotNull(fallbackPTrue) {
                        "an escalated finish check must carry the executed fallback noul"
                    }
                } else {
                    noul.pTrue
                }
                return ExitCheckRow(
                    rowId = ExitCheckRow.rowIdFor(taskId, stepIndex),
                    taskId = taskId,
                    stepIndex = stepIndex,
                    timestamp = clock.nowMs(),
                    stateText = state.text,
                    stateFormatVersion = state.formatVersion,
                    question = noulQuestion,
                    anchoredNoulVersion = AnchoredNoul.ANCHORED_NOUL_VERSION,
                    teacher = engineIdentity,
                    answer = ExitCheckAnswer(pTrue = answerPTrue, band = band(answerPTrue).toLoggedBand()),
                    escalated = escalated,
                    fallbackAnswer = fallbackPTrue?.let { ExitCheckFallbackAnswer(it) },
                    guardLevel = verdict.guardLevel.toExitCheckLevel(),
                    verdict = if (verdict is ExitDecision.Accept) {
                        ExitCheckVerdict.ACCEPTED
                    } else {
                        ExitCheckVerdict.REFUSED
                    },
                    acceptedPath = (verdict as? ExitDecision.Accept)?.path?.toLoggedPath(),
                )
            }
            logSink.writeExitCheck(exitCheckRow())
            return verdict
        }

        /**
         * The mid-ladder accept's shared advance (vision 04 §3 v1.4.0; used by BOTH the
         * engine-initiated finish and the v1.5.0 code-triggered check): the pointer
         * advances one VERIFIED subtask, the per-subtask exit discipline restarts, the
         * failure streak resets, the code-check rate bound re-arms (the ordinal
         * changed), the overlay surfaces re-publish, and the outcome-based site entry
         * runs for the NEW active subtask. The caller consumes the step.
         */
        suspend fun advanceAfterSubtaskAccept(verdict: ExitDecision.Accept, activePointer: SubtaskPointer) {
            val finishedOrdinal = activePointer.activeOrdinal
            activePointer.advance()
            exitPolicy = ExitPolicy() // fresh per-subtask exit discipline
            consecutiveSubtaskFailures = 0
            lastCodeCheck = null // the ordinal changed: the code-check rate bound re-arms
            codeCheckHash = null
            codeCheckCountOnHash = 0
            codeCheckStepsSince = 0
            line(
                "ladder: subtask $finishedOrdinal/${activePointer.total} IS-DONE verified " +
                    "(exit path ${verdict.path.name}, guard ${verdict.guardLevel.name}, " +
                    "noul=${verdict.noul}) - pointer advanced to " +
                    "${activePointer.activeOrdinal}/${activePointer.total}",
            )
            history.add(
                "step $stepIndex: subtask $finishedOrdinal verified done - now on " +
                    "subtask ${activePointer.activeOrdinal}",
            )
            publishLadderSurfaces()
            enterSubtask()
        }

        /**
         * The answer extraction at a GOAL_ACHIEVED terminal (Phase 10): ONE writer call
         * over the goal + the code-MASKED final state (doc 06 §2a) — shared by both
         * finish entries. A failed extraction never un-does an achieved goal: loud line,
         * the run report carries no answer, the task stands.
         */
        suspend fun runAnswerExtraction() {
            val extractor = answerExtractor ?: return
            // doc 06 §2a: the final page state folds the sanctioned-secret redaction
            // BEFORE the writer sees it.
            val finalState = buildString {
                append("title: ${lastPost?.title.orEmpty()}\n")
                append("url: ${lastPost?.url.orEmpty()}\n")
                lastPost?.excerpt?.take(EXTRACTION_STATE_MAX_LINES)
                    ?.forEach { append(it).append('\n') }
            }
            val maskedState = redactionKeys().fold(finalState) { acc, key ->
                acc.replace(key, TextSource.REDACTED)
            }
            val maskedGoal = redactionKeys().fold(goal) { acc, key ->
                acc.replace(key, TextSource.REDACTED)
            }
            try {
                val extracted = extractor.extract(maskedGoal, maskedState)
                if (extracted != null) {
                    writerBudget.record(extracted.costMicroUsd).let { check ->
                        if (check != WriterBudget.Check.OK) {
                            softWarnings.add("writer budget ${check.name}")
                        }
                    }
                    extractedAnswer = extracted
                    line(
                        "extract: answer phrased (${extracted.promptVersion}, " +
                            "${extracted.inputTokens}+${extracted.outputTokens} " +
                            "tokens, ${extracted.costMicroUsd} micro-USD) - rides the run report",
                    )
                }
            } catch (t: Throwable) {
                // A failed extraction never un-does an achieved goal: loud
                // line, the run report carries no answer, the task stands.
                line("extract: FAILED (${t::class.simpleName}) - the run report carries no answer")
            }
        }

        /**
         * The GOAL_ACHIEVED conclusion shared by both finish entries: the run report's
         * exit block + the terminal finish. The ENGINE entry additionally logs the
         * terminal DECISION row carrying the `exit` provenance (doc 05) — the
         * code-triggered entry has no choose to log (ART-2), so its provenance is the
         * accepted ExitCheckRow + this exit block.
         */
        fun concludeGoalAchieved(verdict: ExitDecision.Accept): RunResult {
            exitSummary = ExitSummary(
                path = verdict.path.name,
                guardLevel = verdict.guardLevel.name,
                noul = verdict.noul,
                finishChecks = exitPolicy.finishChecks,
                completionCardShown = exitPolicy.completionCardsShown > 0,
                completionCardResolution = completionCardResolution,
            )
            return finish(
                TerminalState.GOAL_ACHIEVED,
                "exit path ${verdict.path.name}: noul pTrue=${verdict.noul} with the " +
                    "${verdict.guardLevel.name} guard after ${exitPolicy.finishChecks} finish check(s)",
                stepIndex + 1,
            )
        }

        // -- GOAL RESTRUCTURE (Phase 10, vision 04 §1 v1.4.0): when a ladder source is
        //    wired, EVERY goal enters through the writer's ONE restructure call; the
        //    ladder SUBSUMES triage and the cold-start pre-step (site entry folds under
        //    the ladder's outcome-based mechanism). Invalid output = the clean
        //    pre-navigation refusal — nothing navigates. The default source is
        //    [LadderSource.None]: the legacy cold-start + triage path below stays
        //    byte-identical (the phase doc's non-ladder paths).
        if (ladderSource != LadderSource.None) {
            val restructured = ladderSource.restructure(goal, generation = 1)
            if (restructured == null) {
                line("ladder: restructure REFUSED - clean pre-navigation refusal (nothing navigates)")
                return finish(
                    TerminalState.LADDER_REFUSED,
                    "the goal could not be restructured into a valid task ladder - nothing navigated",
                    steps = 0,
                )
            }
            writerBudget.record(restructured.costMicroUsd).let { check ->
                if (check != WriterBudget.Check.OK) softWarnings.add("writer budget ${check.name}")
            }
            ladderGeneration = 1
            applyLadder(restructured.ladder, restructured.version)
            line(
                "ladder: ${restructured.version} - ${restructured.ladder.subtasks.size} subtask(s), " +
                    "class ${serialNameOf(LadderTaskClass.serializer(), restructured.ladder.taskClass)}, " +
                    "writer spend ${writerBudget.spentMicroUsd} micro-USD",
            )
            entity?.let { line("ladder: target entity \"${it.text}\" (code-extracted)") }
            line("guard state: ${guards!!.describe()}")
            publishLadderSurfaces()
            enterSubtask()
        } else {
            // -- COLD-START (vision 04 §1: deterministic code BEFORE triage and the first
            //    OBSERVE; NO engine is ever consulted for opening the browser) ---------
            if (browserProbe != null && browserLauncher != null) {
                val record = ColdStart.run(goal, startUrl, browserProbe, browserLauncher)
                record.lines.forEach { line(it) }
            }

            // -- TRIAGE (vision 04 §1: ONE choose over the goal text) ------------------
            line("triage: asking the engine over the goal text")
            uiListener?.onThinking(true)
            val triageChoice = engine.choose(goal, Triage.question())
            uiListener?.onThinking(false)
            when (val triage = Triage.interpret(triageChoice)) {
                is Triage.Result.Impossible -> {
                    triageLabel = triage.chosenId
                    line("triage: IMPOSSIBLE ('${triage.chosenId}') - explain and abort pre-navigation")
                    line(
                        "explanation: I can only attempt lookup, multi-step research, form-fill, " +
                            "or action goals in v1; \"$goal\" is outside that scope",
                    )
                    return finish(
                        TerminalState.TRIAGE_IMPOSSIBLE,
                        "triage answered '${triage.chosenId}' - no navigation attempted",
                        steps = 0,
                    )
                }
                is Triage.Result.Task -> {
                    triageClass = triage.triageClass
                    triageLabel = triage.triageClass.serial()
                    // Triage-time target-entity extraction (vision 04 §3 v1.2.0): pure code,
                    // once per task; anchors the STRONG guard and the noul proposition.
                    entity = TargetEntity.extract(goal)
                    // Task working memory (Phase 08G, vision 04 §1): action-multi tasks get
                    // the code-owned target tracker — the guard's WEAK predicate is its
                    // drained state, and the L1 stuck hint carries its progress lines.
                    val taskTracker = if (triage.triageClass == TriageClass.ACTION_MULTI) TargetTracker() else null
                    tracker = taskTracker
                    guards = CompletionGuards(triage.triageClass, entity, taskTracker)
                    line("triage: $triageLabel")
                    entity?.let { line("triage: target entity \"${it.text}\" (code-extracted)") }
                    // Plan proposal (Phase 08G, vision 04 §1): consulted ONCE at triage;
                    // context, never executor (ART-2). The default source returns null and
                    // this block stays silent — byte-identical to the pre-08G loop. (The
                    // v3 state text renders ladders instead; provenance keeps riding the rows.)
                    plan = planSource.propose(goal, triage.triageClass)
                    plan?.let {
                        line(
                            "plan: proposed v${it.version} with ${it.subgoals.size} subgoal(s) - " +
                                "provenance only, ART-2",
                        )
                        // Phase 9: the overlay surface shows the plan (context transparency,
                        // ART-2 — provenance, never executor).
                        uiListener?.onPlan(it.subgoals)
                    }
                    line("guard state: ${guards.describe()}")
                }
            }
        }

        // -- per-class budget profile (vision 04 §5): selected by the resolved triage
        //    class; explicit [BudgetLimits] overrides (tests) win over profile numbers;
        //    the three original classes resolve to the original defaults ---------------
        val profile = triageClass?.let { BudgetProfiles.forClass(it) }
        budgetProfile = profile
        val budget = TaskBudget(
            clock = clock,
            spendMeter = spendMeter,
            stepsSoft = config.budget.stepsSoft ?: profile?.stepsSoft ?: TaskBudget.STEPS_SOFT,
            stepsHard = config.budget.stepsHard ?: profile?.stepsHard ?: TaskBudget.STEPS_HARD,
            timeSoftMs = config.budget.timeSoftMs ?: profile?.timeSoftMs ?: TaskBudget.TIME_SOFT_MS,
            timeHardMs = config.budget.timeHardMs ?: profile?.timeHardMs ?: TaskBudget.TIME_HARD_MS,
            spendSoftMicroUsd = config.budget.spendSoftMicroUsd
                ?: profile?.spendSoftMicroUsd ?: TaskBudget.SPEND_SOFT_MICRO_USD,
            spendHardMicroUsd = config.budget.spendHardMicroUsd
                ?: profile?.spendHardMicroUsd ?: TaskBudget.SPEND_HARD_MICRO_USD,
        )

        while (true) {
            /**
             * The per-step budget gate (vision 04 §5; extracted in Phase 10C — `run`'s size
             * budget): a HARD rail ends the run cleanly, a SOFT rail warns once.
             */
            fun budgetStep(): RunResult? {
                when (val check = budget.check(stepIndex)) {
                    is TaskBudget.Check.Hard -> {
                        line("budget: HARD STOP - ${check.dimension.label}: ${check.detail}")
                        return finish(
                            TerminalState.BUDGET_EXHAUSTED,
                            "${check.dimension.label}: ${check.detail}",
                            stepIndex,
                        )
                    }
                    is TaskBudget.Check.Soft -> {
                        check.warnings.forEach { dimension ->
                            line("budget: SOFT warning - ${dimension.label} at its soft limit; continuing")
                            softWarnings.add(dimension.label)
                        }
                    }
                    TaskBudget.Check.Ok -> {}
                }
                return null
            }


            /**
             * Replaces the ACTIVE subtask's done-condition, preserving pointer progress
             * (Phase 10C): a fresh ladder + a fresh pointer advanced to the same verified
             * position (the pointer's advance law is preserved — completed subtasks stay
             * completed).
             */
            fun applyCondition(p: SubtaskPointer, replacement: DoneCondition, provenance: String) {
                val current = ladder ?: return
                val updated = current.copy(
                    subtasks = current.subtasks.mapIndexed { i, st ->
                        if (i == p.index) st.copy(done = replacement) else st
                    },
                )
                val fresh = SubtaskPointer(updated)
                repeat(p.index) { fresh.advance() }
                ladder = updated
                pointer = fresh
                line(
                    "honesty: subtask ${fresh.activeOrdinal}/${fresh.total} condition replaced by " +
                        "$provenance - '${DoneConditionChecker.render(replacement)}'",
                )
                history.add(
                    "step $stepIndex: condition replaced ($provenance) - " +
                        "'${DoneConditionChecker.render(replacement)}'",
                )
                publishLadderSurfaces()
            }

            /**
             * The landing-host reconciliation (Phase 10C, vision 04 §1 v1.7.0): after a
             * CODE navigation, a `urlHost` condition whose host the site redirected away
             * from is amended to the host actually reached when the two are ONE SITE FAMILY
             * (`DoneConditionChecker.sameSiteFamily` — the observed bbc.co.uk -> bbc.com).
             * A genuinely different landing host leaves the condition standing.
             */
            fun reconcileLandingHost(landedUrl: String?) {
                val p = pointer ?: return
                val done = p.active.done
                if (done !is DoneCondition.UrlHost) return
                val landedHost = DoneConditionChecker.normalizeUrl(landedUrl)?.substringBefore('/') ?: return
                if (done.host == landedHost) return
                if (!DoneConditionChecker.sameSiteFamily(done.host, landedHost)) {
                    line(
                        "site-entry: landed on '$landedHost' - not the same site family as the " +
                            "condition's host '${done.host}' (no amendment - the condition stands)",
                    )
                    return
                }
                line(
                    "site-entry: landed on '$landedHost' (one site family with the condition's " +
                        "'${done.host}') - the urlHost condition reconciles to the landing host",
                )
                applyCondition(p, DoneCondition.UrlHost(landedHost), "landing-host reconciliation")
            }

            /**
             * The condition-honesty gate (Phase 10C, vision 04 §1 v1.7.0 — owner-ratified
             * after the 2026-10-11 on-device battery). Fires at most once per (active
             * ordinal, page-hash) when the ACTIVE subtask's condition is WEAK-shaped and
             * already satisfied here AND the subtask names a DIFFERENT page to arrive at.
             * Order of remedies: ONE bounded writer repair → a code-derived URL identity →
             * a loud ladder refusal. Returns a terminal only on the refusal; null continues.
             */
            suspend fun honestyGate(o: StepObservation, cands: CandidateList): RunResult? {
                val p = pointer ?: return null
                val active = ladder ?: return null
                if (honestyChecked == p.index to o.screenHash) return null
                val evidence = ladderEvidence(o, cands)
                val condition = p.active.done
                if (!ConditionHonesty.proxySatisfied(condition, evidence)) {
                    honestyChecked = p.index to o.screenHash
                    return null
                }
                // The SUBTASK's own text resolves first (its instruction names the page the
                // subtask opens); the goal text is the fallback search space — a bare
                // goal-level domain would otherwise shadow the subtask's path URL.
                val named = (
                    SiteEntry.resolveFromText("", p.active.instruction)
                        ?: SiteEntry.resolveFromText(goalText, p.active.instruction)
                    )?.url
                if (!ConditionHonesty.namesDifferentPage(named, o.url)) {
                    // The condition is satisfied by the page the subtask NAMES (or nothing is
                    // named): a legitimately arrived outcome, not a proxy — the ordinary exit
                    // flow decides it (no silent weak acceptance exists: Path A needs STRONG).
                    honestyChecked = p.index to o.screenHash
                    return null
                }
                honestyChecked = p.index to o.screenHash
                line(
                    "honesty: subtask ${p.activeOrdinal}/${p.total} condition " +
                        "'${DoneConditionChecker.render(condition)}' is already satisfied here, but the " +
                        "subtask names $named - ONE bounded writer repair",
                )
                history.add(
                    "step $stepIndex: condition honesty - already-satisfied weak condition " +
                        "on subtask ${p.activeOrdinal}",
                )
                val repaired = if (writerBudget.hasHardRoom()) {
                    ladderSource.repairCondition(p.active.instruction, condition, o.url ?: "", o.title)
                } else {
                    line("honesty: no writer budget room for the repair - deriving a URL identity")
                    null
                }
                if (repaired != null) {
                    writerBudget.record(repaired.costMicroUsd)
                    if (!ConditionHonesty.proxySatisfied(repaired.done, evidence)) {
                        applyCondition(p, repaired.done, "writer repair (${repaired.promptVersion})")
                        return null
                    }
                    line(
                        "honesty: the repaired condition " +
                            "'${DoneConditionChecker.render(repaired.done)}' is ALSO satisfied here - " +
                            "deriving a URL identity",
                    )
                } else {
                    line("honesty: no repair available (unwired / refused) - deriving a URL identity")
                }
                val derived = named?.let {
                    DoneCondition.UrlContains(DoneConditionChecker.normalizeUrl(it) ?: it)
                }
                if (derived != null && !ConditionHonesty.proxySatisfied(derived, evidence)) {
                    applyCondition(p, derived, "code-derived urlContains")
                    return null
                }
                line(
                    "honesty: no honest condition could be produced for subtask ${p.activeOrdinal} " +
                        "- ladder REFUSED (nothing silently self-satisfies)",
                )
                return finish(
                    TerminalState.LADDER_REFUSED,
                    "condition honesty: subtask ${p.activeOrdinal} of '${active.summary}' starts on a " +
                        "page where its weak done-condition is already satisfied and no honest " +
                        "replacement could be derived",
                    stepIndex,
                )
            }

            // -- user cancel -----------------------------------------------------------
            if (isCancelled()) {
                return finish(TerminalState.USER_CANCEL, "cancelled before step $stepIndex", stepIndex)
            }

            // -- budget (hard = clean stop; soft = warn once) ---------------------------
            budgetStep()?.let { return it }

            /**
             * OBSERVE with the unusable-retry law (doc 03 §5; extracted in Phase 10C to keep
             * `run` under the JVM method-size limit): fresh reads until usable or the bound;
             * null = the tree is still unusable (the caller refuses gracefully).
             */
            suspend fun observeUsable(): StepObservation? {
                var o = observer.observe()
                notePageUrl(o)
                var unusableReads = 1
                while (!usable(o) && unusableReads < config.refuseAfterUnusableReads) {
                    unusableReads += 1
                    line(
                        "observe: unusable tree (${o.lines.size} lines, " +
                            "pageNodes=${o.pageNodeCount}) - fresh re-read",
                    )
                    delay(config.unusableRetryDelayMs)
                    o = observer.observe()
                    notePageUrl(o)
                }
                return if (usable(o)) o else null
            }

            /**
             * The step's canonical state text (extracted in Phase 10C: `run`'s size budget);
             * the lines moved wholesale (the sections + masking law are unchanged).
             */
            fun buildStepState(o: StepObservation, cands: CandidateList): StateTextBuilder.StateText =
                stateTextBuilder.build(
                    goal = goalText,
                    url = o.url,
                    title = o.title,
                    history = history + hints,
                    candidates = cands,
                    excerpt = o.excerpt,
                    activeSubtask = activeSubtaskLine()?.let { listOf(it) },
                    ladderProgress = ladderProgressLines(),
                    secrets = redactionKeys(),
                )

            /**
             * The report-subtask extraction (Phase 10C, doc 04 §1 v1.7.0): when the ACTIVE
             * subtask's done-condition is [DoneCondition.AnswerExtracted] and no answer has
             * been produced for it yet, ONE writer call phrases the answer over the
             * code-MASKED current state (doc 06 §2a) and the code-owned record flips so the
             * condition verifies (guard STRONG — never page content). A refused extraction
             * leaves the condition unsatisfied (the ordinary failure machinery applies).
             */
            suspend fun extractReportAnswer(st: StateTextBuilder.StateText) {
                val p = pointer ?: return
                if (p.active.done !is DoneCondition.AnswerExtracted) return
                if (reportAnswers.containsKey(p.index)) return
                val extractor = answerExtractor ?: run {
                    line("report: no extractor wired - the answer subtask cannot verify")
                    return
                }
                val maskedState = redactionKeys().fold(st.text) { acc, key ->
                    acc.replace(key, TextSource.REDACTED)
                }
                val maskedGoal = redactionKeys().fold(goal) { acc, key ->
                    acc.replace(key, TextSource.REDACTED)
                }
                try {
                    val extracted = extractor.extract(maskedGoal, maskedState)
                    if (extracted == null) {
                        line("report: the extraction refused - the answer subtask stays unverified")
                        return
                    }
                    writerBudget.record(extracted.costMicroUsd).let { check ->
                        if (check != WriterBudget.Check.OK) {
                            softWarnings.add("writer budget ${check.name}")
                        }
                    }
                    reportAnswers[p.index] = extracted
                    if (extractedAnswer == null) extractedAnswer = extracted
                    line(
                        "report: the answer for subtask ${p.activeOrdinal}/${p.total} was produced " +
                            "(${extracted.promptVersion}, ${extracted.costMicroUsd} micro-USD)",
                    )
                } catch (t: Throwable) {
                    line(
                        "report: the extraction failed loudly (${t::class.simpleName}) - " +
                            "the answer subtask stays unverified",
                    )
                }
            }

            // -- 1. OBSERVE (fresh; unusable tree -> graceful refusal, doc 03 §5) -------
            val obsOrNull = observeUsable()
            if (obsOrNull == null) {
                line("observe: tree still unusable - graceful refusal (vision 03 §5)")
                return finish(TerminalState.REFUSED, REFUSAL_MESSAGE, stepIndex)
            }
            var obs = obsOrNull

            // -- the landing-host reconciliation (Phase 10C, vision 04 §1 v1.7.0) --------
            if (pendingLandingReconcile) {
                pendingLandingReconcile = false
                reconcileLandingHost(obs.url)
            }

            // -- 2. ENUMERATE (deterministic buckets, doc 03 §3) -------------------------
            guards!!.seed(obs.url)
            // Task working memory (Phase 08G, vision 04 §1): the action-multi tracker
            // re-reads the page each step — re-identifies rows across scrolls and
            // navigation, registers newly discovered targets.
            tracker?.observe(obs)
            var candidates = CandidateEnumerator.enumerate(obs.nodes)
            // The finish pseudo-action is RETIRED from offered slates (vision 02 §3
            // v1.3.0): `verify-done` + the code-triggered checks own completion; the
            // finish bucket stays in the taxonomy for schema compatibility only. The
            // anti-oscillation window (v1.2.0) keeps metering refused done claims.
            candidates = candidates.withoutFinish()
            if (exitPolicy.finishExcluded(obs.screenHash)) {
                line("exit: the done-claim window is armed this decision (anti-oscillation)")
            }
            val state = buildStepState(obs, candidates)
            hints.clear() // the L1 hint rode this step's HISTORY block

            // -- CONDITION HONESTY (Phase 10C, vision 04 §1 v1.7.0) ------------------------
            // A WEAK-shaped done-condition already satisfied where its subtask starts is the
            // false-completion defect (the 2026-10-11 battery: "a News link is present" /
            // "the text 'Dinner' appears" were true on the page the subtask started from).
            // URL identities already true are LEGITIMATE (immediate verify+advance) and
            // never enter this gate.
            honestyGate(obs, candidates)?.let { return it }

            // -- REPORT-SUBTASK extraction (Phase 10C, doc 04 §1 v1.7.0) -------------------
            // The `answerExtracted` condition's evidence is the code-owned extraction
            // record: produce it once per report subtask before the checks judge.
            extractReportAnswer(state)

            // -- CODE-TRIGGERED IS-DONE check (vision 04 §3 v1.5.0) ------------------------
            // The IS-DONE evaluation is NOT gated on the engine volunteering `finish`
            // (the UAT 10.1-a defect: the engine never chose finish in 36 steps while the
            // ACTIVE subtask's urlHost condition was satisfied from step 0). When the
            // ACTIVE subtask's code done-condition is satisfied — the guard ALONE — the
            // loop proactively runs the SAME anchored noul check (identical question
            // construction, escalation handling, ExitCheckRow, exit paths). ART-4 is
            // untouched: code satisfaction triggers the QUESTION; the advance still
            // requires engine noul HIGH AND the guard. Rate-bounded: at most one check
            // per (page-hash, active ordinal) — a static page is asked once; the bound
            // re-arms on any page-hash change or pointer advance. The check CONSUMES its
            // step: no engine choose happened (ART-2 — no decision row is fabricated), so
            // it rides HISTORY + its ExitCheckRow and counts one meta-decision (the
            // zero-log-gaps invariant stays exact). A refused check does NOT withhold
            // candidates (the anti-oscillation window keeps applying to engine-initiated
            // finish only), does NOT feed the stuck detector, and does NOT count a
            // subtask failure — the condition holds; the engine just won't confirm.
            val codePointer = pointer
            if (codePointer != null && ladder != null) {
                codeCheckStepsSince += 1 // ticks every step; reset to 0 when a check runs
                val evidence = ladderEvidence(obs, candidates)
                val guardLevel = subtaskGuardLevel(codePointer, evidence)
                val checkKey = obs.screenHash to codePointer.index
                // v1.6.0 held-hash re-checks: the FIRST check at a page-hash always
                // fires; on an UNCHANGED hash whose guard stays satisfied, re-checks
                // fire every heldHashRecheckEverySteps steps, at most heldHashRecheckMax
                // per hash (the pure decision is [ExitPolicy.heldHashRecheckDue]; the
                // counters are the loop's).
                val firstAtHash = lastCodeCheck?.first != obs.screenHash
                val recheckDue = !firstAtHash &&
                    exitPolicy.heldHashRecheckDue(
                        stepsSinceLastCheck = codeCheckStepsSince,
                        checksOnThisHash = codeCheckCountOnHash,
                    )
                if (guardLevel != GuardLevel.NONE && (firstAtHash || recheckDue)) {
                    lastCodeCheck = checkKey
                    codeCheckHash = obs.screenHash
                    codeCheckCountOnHash = if (firstAtHash) 1 else codeCheckCountOnHash + 1
                    codeCheckStepsSince = 0
                    guards!!.record(
                        url = obs.url,
                        contentTextNodes = obs.contentTextNodes,
                        textTyped = false,
                        submitExecuted = false,
                    )
                    line(
                        "exit: code-triggered IS-DONE check on subtask " +
                            "${codePointer.activeOrdinal}/${codePointer.total} (guard " +
                            "${guardLevel.name} satisfied - the engine must still confirm, ART-4)",
                    )
                    val verdict = verifyFinishCheck(
                        trigger = "code-triggered IS-DONE check",
                        guardLevel = guardLevel,
                        noulQuestion = subtaskNoulQuestion(codePointer, obs),
                        obs = obs,
                        state = state,
                    )
                    when (verdict) {
                        is ExitDecision.Accept -> {
                            metaDecisions += 1
                            if (!codePointer.isFinal) {
                                advanceAfterSubtaskAccept(verdict, codePointer)
                                stepIndex += 1
                                continue // fresh OBSERVE + state over the NEW active subtask
                            }
                            // The FINAL subtask's code-triggered IS-DONE is the task's
                            // goal-achieved: full exit provenance rides the accepted
                            // ExitCheckRow + the report's exit block.
                            history.add(
                                "step $stepIndex: code-triggered check verified the FINAL subtask " +
                                    "${codePointer.activeOrdinal} done - goal achieved",
                            )
                            runAnswerExtraction()
                            return concludeGoalAchieved(verdict)
                        }
                        is ExitDecision.Refuse -> {
                            line("exit: code-triggered IS-DONE check REFUSED (ART-4) - ${verdict.reason}")
                            history.add(
                                "step $stepIndex: code-triggered done check refused - the engine " +
                                    "did not confirm the active subtask",
                            )
                            metaDecisions += 1
                            stepIndex += 1
                            // falls through: the step's DECIDE runs normally over the same
                            // subtask (the check consumed its own step index above)
                        }
                        is ExitDecision.AskOwner -> throw IllegalStateException(
                            "the exit policy re-asked the completion card - once-per-task is a law invariant",
                        )
                    }
                }
            }

            // -- 3. DECIDE — the decision cascade (Phase 10B, vision 04 §2 v1.6.0) --------
            // Stage-1: ONE choice over the FIXED 9-intent vocabulary on the INTENT STATE
            // VIEW (the canonical text stays the row's record — stage views never replace
            // it). Band routing (ART-5): HIGH executes the intent; UNCERTAIN runs the
            // bounded handshake (ONE assist per page-hash → ONE re-ask → code fallback);
            // LOW is the wrong-page signal. Stage-2 (target-bearing intents): ONE batched
            // score request over the bucket slate; code resolves the argmax — a flat
            // spread is NO-GOOD-TARGET, never a tap. The `finish` pseudo-action is
            // retired: `verify-done` + the R1 code-triggered checks own completion.
            var question: ChoiceQuestion = Intents.QUESTION
            var choice: ChoiceResult = ChoiceResult(
                Intents.VERIFY_DONE,
                Intents.IDS.associateWith { id -> if (id == Intents.VERIFY_DONE) 1.0 else 0.0 },
                1.0,
            )
            // The choose escalation is captured IMMEDIATELY (a later noul consult
            // overwrites the engine's single slot); it rides this step's row. Every
            // decide path reassigns all three before use — the initializations exist so
            // the step helpers below can capture them.
            var escalation: DecisionRecord? = null
            // The stage-2 products (target-bearing steps): the resolved target, the
            // executed slate's expected levels, and the TARGET STATE VIEW the batch rode
            // (the row's targetStateText — the score case's state law).
            var cascadeTarget: Candidate? = null
            var targetScores: Map<String, Double>? = null
            var targetViewText: String? = null
            // The writer-phrased typed text of a fill-field step (locked decision #4).
            var phraseText: String? = null

            /** The INTENT STATE VIEW of this step (optional assist block on a re-ask). */
            fun buildIntentView(assistText: String?): StageViews.StageView =
                stageViews.intentView(
                    subtask = subtaskInstruction(),
                    doneWhen = doneWhenText(),
                    host = Cascade.hostOf(obs.url),
                    title = obs.title,
                    excerpt = obs.excerpt,
                    assistText = assistText,
                )

            /**
             * One UNGATED cascade gesture step (go-back / scroll / the LOW wrong-page
             * signal — recovery actions are never gated, vision 06 §1): execute, settle,
             * post-observe, the row + live-log line + history digest + stuck footprint,
             * and the step advance with the stuck assessment. Shares the recovery
             * branch's exact mechanics. Returns the task's terminal [RunResult] when the
             * stuck L3 kill ended the task, null when the loop continues.
             */
            suspend fun ungatedGestureStep(
                verb: String,
                actionKey: String,
                loggedKind: LoggedActionKind,
                loggedCandidateId: String?,
                execute: () -> Boolean,
            ): RunResult? {
                val standIn = if (loggedKind == LoggedActionKind.BACK) Recovery.BACK else Recovery.SCROLL
                val gateAction = GateAction(
                    recoveryCandidate(candidates, standIn),
                    if (loggedKind == LoggedActionKind.BACK) ActionKind.BACK else ActionKind.SCROLL,
                    null,
                )
                val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
                check(RiskGate.classify(gateAction, pageContext) == null) {
                    "cascade gestures are never gated (vision 06 §1)"
                }
                val executed = execute()
                delay(config.settleMs)
                val post = observer.observe()
                notePageUrl(post)
                lastPost = post
                val outcome = when {
                    !executed -> Outcome.ERROR
                    post.lines == obs.lines -> Outcome.NO_CHANGE
                    else -> Outcome.PROGRESSED
                }
                logRow(
                    stepIndex, state, question, choice, candidates,
                    ExecutedAction(loggedCandidateId, loggedKind, ActionDisposition.EXECUTED),
                    gateEvent = null,
                    outcome = outcome,
                    escalation = escalation,
                    targetScores = targetScores,
                    targetStateText = targetViewText,
                )
                uiListener?.onStep(
                    LoopUiListener.StepFacts(
                        stepIndex = stepIndex,
                        verb = verb,
                        label = verb,
                        confidence = choice.confidence,
                        gateMarker = LoopUiListener.gateMarker(null),
                    ),
                )
                history.add("step $stepIndex: $verb -> ${serial(outcome)}")
                stuck.record(
                    StuckDetector.Footprint(
                        actionKey = actionKey,
                        screenHash = post.screenHash,
                        textLines = post.lines,
                        isError = outcome == Outcome.ERROR,
                    ),
                )
                stepIndex += 1
                if (assessStuck()) {
                    return handleStuckKill("still stuck after the cascade gesture - L3 auto-kill")
                }
                return null
            }



            /**
             * The stage-2 scoring of one live slate (vision 02 §3 v1.4.0): the masked
             * TARGET STATE VIEW, ONE batched score request, the code-side argmax/flat-spread
             * resolution (ART-5). Extracted from `run` in Phase 10C (the loop body must stay
             * under the JVM method-size limit; the lines moved wholesale, byte-identical).
             */
            suspend fun runStage2(intentId: String, slate: List<Candidate>): Stage2Outcome {
                val subtask = subtaskInstruction()
                val maskedSlate = slate.map {
                    it.copy(label = redactedLabel(it.label, redactionKeys()))
                }
                val view = stageViews.targetView(subtask, doneWhenText(), maskedSlate)
                val stage2 = TargetRubric.stage2Questions(
                    maskedSlate.map { it.id to it.label },
                    subtask,
                )
                uiListener?.onThinking(true)
                val results = engine.scoreBatch(view.text, stage2)
                uiListener?.onThinking(false)
                val expectedLevels = results
                    .mapKeys { it.key.removePrefix(TargetRubric.CANDIDATE_NAME_PREFIX) }
                    .mapValues { it.value.expectedLevel }
                val resolution = TargetRubric.resolve(expectedLevels)
                if (resolution.noGoodTarget) {
                    return Stage2Outcome.NoGoodTarget(resolution.reason ?: "no good target")
                }
                val target = slate.first { it.id == resolution.top.candidateId }
                line(
                    "decide: stage-2 target \"${redactedLabel(target.label, redactionKeys())}\" " +
                        "[${target.id}] (${resolution.top.expectedLevel} of ${slate.size} scored)",
                )
                return Stage2Outcome.Target(target, expectedLevels, view.text)
            }

            /**
             * The uncertainty handshake (vision 04 §2 v1.6.0; extracted in Phase 10C to keep
             * `run` under the JVM method-size limit — the lines moved wholesale): ONE writer
             * assist per page-hash (when wired and in budget) + ONE stage-1 re-ask with the
             * assist riding the view as labeled untrusted context; returns the decision of
             * record (the original [choice] when the handshake did not re-ask).
             */
            suspend fun handshakeReask(o: StepObservation): ChoiceResult {
                if (assistTriedHash == o.screenHash) {
                    line("decide: UNCERTAIN - the assist already ran on this page-hash - code fallback")
                    return choice
                }
                if (assistSource == null) {
                    line("decide: UNCERTAIN - no assist source wired - code fallback")
                    return choice
                }
                if (!writerBudget.hasHardRoom()) {
                    line("decide: UNCERTAIN - the writer budget's hard cap is reached - code fallback")
                    return choice
                }
                assistTriedHash = o.screenHash
                val subtask = subtaskInstruction()
                val doneWhen = doneWhenText()
                val pageMaterial = buildString {
                    append("title: ").append(o.title).append('\n')
                    append("url: ").append(o.url).append('\n')
                    o.excerpt.take(ASSIST_MATERIAL_MAX_LINES).forEach { append(it).append('\n') }
                }
                val maskedMaterial =
                    redactionKeys().fold(pageMaterial) { acc, key -> acc.replace(key, TextSource.REDACTED) }
                val assist = runAssist(subtask, doneWhen, maskedMaterial)
                if (assist == null) {
                    line("decide: assist skipped (refused output / honesty path) - code fallback")
                    return choice
                }
                line(
                    "handshake: assist applied (${assist.text.length} chars, " +
                        "${assist.promptVersion}) - ONE stage-1 re-ask with the " +
                        "ASSIST (untrusted) block",
                )
                uiListener?.onThinking(true)
                val reasked = engine.choose(buildIntentView(assist.text).text, question)
                uiListener?.onThinking(false)
                escalation = readEscalation(question.name)
                line(
                    "decide: re-ask intent '${reasked.chosenId}' (p=" +
                        "${Cascade.chosenProbability(reasked.probabilities, reasked.chosenId)}, " +
                        "margin=${Cascade.topMargin(reasked.probabilities)})" + escalationSuffix(escalation),
                )
                return reasked
            }


            /**
             * Phase 10C: one consumed step's NO-CHANGE row (nothing executed — the
             * empty-slate grace, and the stage-1 re-ask's no-op step). One row per decision
             * (doc 05 §2); [scores]/[viewText] carry the stage-2 evidence when the failing
             * step had it.
             */
            suspend fun logNoChangeRow(
                st: StateTextBuilder.StateText,
                q: ChoiceQuestion,
                ch: ChoiceResult,
                cands: CandidateList,
                scores: Map<String, Double>? = null,
                viewText: String? = null,
            ) {
                logRow(
                    stepIndex, st, q, ch, cands,
                    ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.REFUSED_BY_GUARD),
                    gateEvent = null,
                    outcome = Outcome.NO_CHANGE,
                    escalation = escalation,
                    targetScores = scores,
                    targetStateText = viewText,
                )
            }

            /**
             * The bounded stage-1 re-ask (Phase 10C, vision 04 §2 v1.7.0): the chosen
             * intent's slate is unusable (empty after the grace, or a flat stage-2 spread).
             * Instead of failing the subtask, the loop asks stage-1 ONCE MORE on this
             * page-hash with the FACT riding the question's instructions. Returns the new
             * answer only when it is a DIFFERENT intent and routes HIGH by the v1.7.0 band
             * (chosen probability + margin); null = no re-ask (already spent, the engine
             * repeats the dead-end intent, or the new answer routes below HIGH) — the
             * ordinary fallback applies.
             */
            suspend fun reaskIntent(
                o: StepObservation,
                bannedIntent: String,
                reason: String,
                baseQuestion: ChoiceQuestion,
            ): ChoiceResult? {
                if (noGoodTargetReaskHash == o.screenHash) return null
                noGoodTargetReaskHash = o.screenHash
                val note = "NOTE: your chosen intent '$bannedIntent' has no usable target on this " +
                    "page ($reason). Choose a DIFFERENT intent for this step."
                line("decide: stage-1 re-ask ($reason) - ONE bounded re-ask on this page-hash")
                val reQuestion = baseQuestion.copy(
                    instructions = baseQuestion.instructions + "\n\n" + note,
                )
                uiListener?.onThinking(true)
                val re = engine.choose(buildIntentView(null).text, reQuestion)
                uiListener?.onThinking(false)
                line(
                    "decide: re-ask intent '${re.chosenId}' (p=" +
                        "${Cascade.chosenProbability(re.probabilities, re.chosenId)}, " +
                        "margin=${Cascade.topMargin(re.probabilities)})",
                )
                if (re.chosenId == bannedIntent) {
                    line("decide: the re-ask repeated '$bannedIntent' - the ordinary fallback applies")
                    return null
                }
                val route = Cascade.route(
                    chosenProbability = Cascade.chosenProbability(re.probabilities, re.chosenId),
                    topMargin = Cascade.topMargin(re.probabilities),
                )
                if (route != Cascade.Route.HIGH) {
                    line("decide: the re-ask routed ${route.name} - the ordinary fallback applies")
                    return null
                }
                return re
            }


            /**
             * The give-up-shaped CODE FALLBACK (the recovery/stuck machinery): the
             * fallback row (FINISH/EXITED, the give-up precedent — optionally carrying
             * the executed stage-2 evidence), the history digest, the step advance, and
             * the bounded failure path (re-plan → escalation card on a ladder run; the
             * legacy task-level kill). Returns the terminal [RunResult] when the task
             * must end, null when the loop continues.
             */
            suspend fun cascadeFallbackStep(cause: String, withStage2: Boolean): RunResult? {
                logRow(
                    stepIndex, state, question, choice, candidates,
                    ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                    gateEvent = null,
                    outcome = Outcome.EXITED,
                    escalation = escalation,
                    targetScores = if (withStage2) targetScores else null,
                    targetStateText = if (withStage2) targetViewText else null,
                )
                history.add("step $stepIndex: cascade fallback - $cause")
                stepIndex += 1
                return handleSubtaskFailureOrKill(cause)
            }

            /**
             * The `verify-done` step (vision 04 §2/§3 v1.6.0): the completion CHOICE is
             * retired — this intent routes into the SHARED finish-verification path the
             * R1 code-triggered checks use (guard + anchored v3 noul + exit policy +
             * completion card + ExitCheckRow). Returns the terminal [RunResult] when the
             * task ended (goal achieved), null when the loop continues (mid-ladder
             * advance, or a refused check consumed the step).
             */
            suspend fun verifyDoneStep(): RunResult? {
                // Anti-oscillation (vision 04 §3 v1.2.0): a refused done claim withholds
                // the NEXT done claim for M=2 decisions unless the page hash changes. The
                // finish CHOICE is retired (the intent is always offerable), so the
                // WINDOW is enforced here: a verify-done inside the window is refused
                // WITHOUT the judge consult (no noul, no ExitCheckRow, no stuck feed,
                // no re-arm) — the claim is not evaluated, exactly like the retired
                // finish's exclusion from the offered candidates.
                if (exitPolicy.finishExcluded(obs.screenHash)) {
                    line("decide: verify-done withheld - the anti-oscillation window is armed on this page-hash")
                    history.add("step $stepIndex: verify-done withheld (anti-oscillation window)")
                    logRow(
                        stepIndex, state, question, choice, candidates,
                        ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.REFUSED_BY_GUARD),
                        gateEvent = null,
                        outcome = Outcome.NO_CHANGE,
                        escalation = escalation,
                    )
                    stepIndex += 1
                    return null
                }
                // The CURRENT observation is code-side evidence of what the screen holds
                // (the tap's post-observation may have raced the page load). Recording it
                // keeps the guard's judgment on code-read state, never on the self-report.
                guards!!.record(
                    url = obs.url,
                    contentTextNodes = obs.contentTextNodes,
                    textTyped = false,
                    submitExecuted = false,
                )
                // Phase 10 (vision 04 §3 v1.4.0): on a LADDER run the SAME discipline
                // applies at subtask granularity; v1.5.0: the guard/question construction
                // is the SHARED helpers also used by the code-triggered check — identical
                // by construction.
                val activePointer = pointer
                val guardLevel: GuardLevel
                val noulQuestion: NoulQuestion
                if (activePointer != null && ladder != null) {
                    guardLevel = subtaskGuardLevel(activePointer, ladderEvidence(obs, candidates))
                    noulQuestion = subtaskNoulQuestion(activePointer, obs)
                } else {
                    guardLevel = guards!!.levelAt(obs.title, obs.url)
                    noulQuestion = AnchoredNoul.question(
                        goalText,
                        entity,
                        requireNotNull(triageClass),
                        obs.title,
                        obs.url,
                    )
                }
                val verdict = verifyFinishCheck("verify-done", guardLevel, noulQuestion, obs, state)
                when (verdict) {
                    is ExitDecision.Accept -> {
                        val provenance = ExitProvenance(
                            path = verdict.path.toLoggedPath(),
                            guardLevel = verdict.guardLevel.toLoggedLevel(),
                            noul = verdict.noul,
                        )
                        // A MID-LADDER accept is the ACTIVE subtask's verified IS-DONE —
                        // the pointer advances and the loop continues over the next
                        // subtask (the task is NOT done; the row's `exit` provenance stays
                        // null — it is the TERMINAL row's field; the accepted check
                        // already wrote its ExitCheckRow). The FINAL subtask's IS-DONE is
                        // the task's goal-achieved.
                        val acceptedPointer = pointer
                        if (acceptedPointer != null && ladder != null && !acceptedPointer.isFinal) {
                            logRow(
                                stepIndex, state, question, choice, candidates,
                                ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                                gateEvent = null,
                                outcome = Outcome.PROGRESSED,
                                escalation = escalation,
                            )
                            advanceAfterSubtaskAccept(verdict, acceptedPointer)
                            stepIndex += 1
                            return null
                        }
                        runAnswerExtraction()
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = Outcome.EXITED,
                            escalation = escalation,
                            exit = provenance,
                        )
                        return concludeGoalAchieved(verdict)
                    }
                    is ExitDecision.Refuse -> {
                        line("decide: verify-done REFUSED (ART-4) - ${verdict.reason}")
                        history.add("step $stepIndex: verify-done refused - the goal is not verifiably achieved yet")
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.REFUSED_BY_GUARD),
                            gateEvent = null,
                            outcome = Outcome.NO_CHANGE,
                            escalation = escalation,
                        )
                        // Anti-oscillation: this refusal meters the done-claim window
                        // (vision 04 §3 v1.2.0 mechanics, unchanged).
                        exitPolicy.onRefusedFinish(obs.screenHash)
                        stuck.record(
                            StuckDetector.Footprint(
                                actionKey = "VERIFY_DONE",
                                screenHash = obs.screenHash,
                                textLines = obs.lines,
                                isError = false,
                            ),
                        )
                        // Sixth stuck signal: the exit conflict (guard level rides the hint).
                        stuck.recordRefusedFinish(verdict.guardLevel.name)
                        stepIndex += 1
                        if (assessStuck()) {
                            return handleStuckKill("repeated unsupported done claims - L3 auto-kill")
                        }
                        return null
                    }
                    is ExitDecision.AskOwner -> throw IllegalStateException(
                        "the exit policy re-asked the completion card - once-per-task is a law invariant",
                    )
                }
            }

            /**
             * The fill-field phrase-refusal step (vision 04 §2 v1.6.0): the step FAILS
             * TO RECOVERY — the row (TYPE/ERROR, the failed-dispatch precedent), the
             * history digest, the stuck footprint, and the stuck assessment. NEVER a
             * raw-goal fallback. Returns the terminal [RunResult] when the task must
             * end, null when the loop continues.
             */
            suspend fun phraseFailureStep(fieldLabel: String): RunResult? {
                line("fill-field: phrasing refused for \"$fieldLabel\" - the step FAILS to recovery (never raw-goal typing)")
                logRow(
                    stepIndex, state, question, choice, candidates,
                    ExecutedAction(cascadeTarget?.id, LoggedActionKind.TYPE, ActionDisposition.EXECUTED),
                    gateEvent = null,
                    outcome = Outcome.ERROR,
                    escalation = escalation,
                    targetScores = targetScores,
                    targetStateText = targetViewText,
                )
                history.add("step $stepIndex: fill-field phrasing refused - recovery")
                stuck.record(
                    StuckDetector.Footprint(
                        actionKey = "fill-field:${cascadeTarget?.id}",
                        screenHash = obs.screenHash,
                        textLines = obs.lines,
                        isError = true,
                    ),
                )
                stepIndex += 1
                if (assessStuck()) {
                    return handleStuckKill("repeated fill-field phrasing failures - L3 auto-kill")
                }
                return null
            }

            /**
             * The `open-site` step (vision 04 §2 v1.6.0): the code VIEW intent — a
             * resolved URL is opened directly ([SiteEntry] over the ACTIVE subtask; no
             * model target, never a tap). No URL resolved (or no launcher wired) is the
             * no-good-target fallback. Returns the terminal [RunResult] when the task
             * must end, null when the loop continues.
             */
            suspend fun openSiteStep(): RunResult? {
                val activePointer = pointer
                val entryPlan = if (activePointer != null && ladder != null) {
                    SiteEntry.plan(goalText, activePointer.active.instruction, activePointer.active.done)
                } else {
                    null
                }
                val launcher = browserLauncher
                if (entryPlan == null || launcher == null) {
                    line(
                        "open-site: no URL resolved " +
                            "(subtask ${if (activePointer != null) "done-condition" else "is non-ladder"}) " +
                            "or no launcher wired - no-good-target fallback, nothing typed or tapped",
                    )
                    return cascadeFallbackStep("open-site could not resolve a URL to open", withStage2 = false)
                }
                line("open-site: VIEW intent for ${entryPlan.url} (${entryPlan.source}) - code opens the URL directly")
                launcher.launch(entryPlan.url)
                pendingLandingReconcile = true
                delay(config.restoreSettleMs)
                waitForBrowserReadiness()
                delay(config.settleMs)
                val post = observer.observe()
                notePageUrl(post)
                lastPost = post
                val outcome = when {
                    post.lines == obs.lines -> Outcome.NO_CHANGE
                    else -> Outcome.PROGRESSED
                }
                logRow(
                    stepIndex, state, question, choice, candidates,
                    ExecutedAction(null, LoggedActionKind.VIEW, ActionDisposition.EXECUTED),
                    gateEvent = null,
                    outcome = outcome,
                    escalation = escalation,
                )
                uiListener?.onStep(
                    LoopUiListener.StepFacts(
                        stepIndex = stepIndex,
                        verb = Intents.OPEN_SITE,
                        label = redactedLabel(entryPlan.url, redactionKeys()),
                        confidence = choice.confidence,
                        gateMarker = LoopUiListener.gateMarker(null),
                    ),
                )
                history.add(
                    "step $stepIndex: open-site \"${redactedLabel(entryPlan.url, redactionKeys())}\" " +
                        "(${entryPlan.source}) -> ${serial(outcome)}",
                )
                stuck.record(
                    StuckDetector.Footprint(
                        actionKey = "open-site",
                        screenHash = post.screenHash,
                        textLines = post.lines,
                        isError = false,
                    ),
                )
                stepIndex += 1
                if (assessStuck()) {
                    return handleStuckKill("still stuck after the site entry - L3 auto-kill")
                }
                return null
            }

            /**
             * The code-path intent dispatch (vision 04 §2; extracted in Phase 10C for the
             * `run` method-size budget — the branches moved wholesale): verify-done,
             * open-site, go-back and scroll own their code paths and end the step; the
             * target-bearing intents fall through to the gate + execution below.
             */
            suspend fun dispatchIntent(intentId: String, cands: CandidateList): IntentDispatch =
                when (intentId) {
                    Intents.VERIFY_DONE ->
                        verifyDoneStep()?.let { IntentDispatch.Terminal(it) } ?: IntentDispatch.Consumed

                    Intents.OPEN_SITE ->
                        openSiteStep()?.let { IntentDispatch.Terminal(it) } ?: IntentDispatch.Consumed

                    Intents.GO_BACK ->
                        ungatedGestureStep(
                            verb = Intents.GO_BACK,
                            actionKey = "go-back",
                            loggedKind = LoggedActionKind.BACK,
                            loggedCandidateId = null,
                            execute = { executor.back() },
                        )?.let { IntentDispatch.Terminal(it) } ?: IntentDispatch.Consumed

                    Intents.SCROLL -> {
                        // The scroll BOUNCE (gate-105): the flat world offered both
                        // scroll-down and scroll-up candidates; the cascade's single
                        // scroll intent must not hammer one direction forever — a
                        // repeated scroll at a page end changes nothing and feeds the
                        // stuck detector toward a kill. Pick a direction DIFFERENT from
                        // the previous scroll candidate when both are enumerated
                        // (deterministic; falls back to the down-first pick).
                        val scrollSlate = cands.bucketed[Bucket.SCROLL].orEmpty()
                        val scrollCandidate = scrollSlate
                            .firstOrNull { it.id != lastScrollCandidateId }
                            ?: scrollSlate.firstOrNull { it.id.startsWith(SCROLL_DOWN_PREFIX) }
                            ?: scrollSlate.firstOrNull()
                        if (scrollCandidate == null) {
                            line("decide: scroll with no scroll candidate - no-good-target fallback")
                            cascadeFallbackStep("the scroll intent found no scroll candidate", withStage2 = false)
                                ?.let { return IntentDispatch.Terminal(it) }
                            return IntentDispatch.Consumed
                        }
                        val down = scrollCandidate.id.startsWith(CandidateEnumerator.SCROLL_DOWN_ID)
                        lastScrollCandidateId = scrollCandidate.id
                        ungatedGestureStep(
                            verb = Intents.SCROLL,
                            actionKey = "scroll:${scrollCandidate.id}",
                            loggedKind = LoggedActionKind.SCROLL,
                            loggedCandidateId = scrollCandidate.id,
                            execute = { executor.scroll(scrollCandidate.cx, scrollCandidate.cy, down) },
                        )?.let { return IntentDispatch.Terminal(it) }
                        IntentDispatch.Consumed
                    }

                    else -> IntentDispatch.FallThrough
                }

            if (recoveryArmed) {
                recoveryArmed = false
                question = RECOVERY_QUESTION
                uiListener?.onThinking(true)
                choice = engine.choose(state.text, question)
                uiListener?.onThinking(false)
                escalation = readEscalation(question.name)
                line("decide: recovery set -> '${choice.chosenId}'" + escalationSuffix(escalation))
            } else {
                question = Intents.QUESTION
                val pinned = forcedIntentNextStep
                if (pinned != null) {
                    // The pinned re-ask answer (Phase 10C): the engine already answered this
                    // page's stage-1 question on the previous step — execute its choice now.
                    forcedIntentNextStep = null
                    choice = ChoiceResult(pinned, mapOf(pinned to 1.0), 1.0)
                    escalation = null
                    line("decide: intent '$pinned' (the stage-1 re-ask's answer, pinned for this step)")
                } else {
                    uiListener?.onThinking(true)
                    choice = engine.choose(buildIntentView(null).text, question)
                    uiListener?.onThinking(false)
                    escalation = readEscalation(question.name)
                }
                    line(
                        "decide: intent '${choice.chosenId}' (p=" +
                            "${Cascade.chosenProbability(choice.probabilities, choice.chosenId)}, " +
                            "margin=${Cascade.topMargin(choice.probabilities)})" + escalationSuffix(escalation),
                    )

                // -- the uncertainty handshake (vision 04 §2 v1.6.0) ---------------------
                var routed = Cascade.route(
                    chosenProbability = Cascade.chosenProbability(choice.probabilities, choice.chosenId),
                    topMargin = Cascade.topMargin(choice.probabilities),
                )
                if (routed == Cascade.Route.UNCERTAIN) {
                    choice = handshakeReask(obs)
                    routed = Cascade.route(
                        chosenProbability = Cascade.chosenProbability(choice.probabilities, choice.chosenId),
                        topMargin = Cascade.topMargin(choice.probabilities),
                    )
                }

                // -- LOW: the wrong-page recovery signal (vision 04 §2 v1.6.0) ------------
                if (routed == Cascade.Route.LOW) {
                    line("decide: intent '${choice.chosenId}' LOW (p=${choice.confidence}) - wrong-page signal: go-back-leaning handling")
                    exitPolicy.noteDecision(obs.screenHash)
                    ungatedGestureStep(
                        verb = Intents.GO_BACK,
                        actionKey = "wrong-page:back",
                        loggedKind = LoggedActionKind.BACK,
                        loggedCandidateId = null,
                        execute = { executor.back() },
                    )?.let { return it }
                    continue
                }

                // -- still UNCERTAIN: the code fallback (the give-up machinery) -----------
                if (routed == Cascade.Route.UNCERTAIN) {
                    line(
                        "decide: still UNCERTAIN after the handshake - code fallback " +
                            "(recovery/stuck machinery -> re-plan -> escalation card)",
                    )
                    cascadeFallbackStep(
                        "the cascade stayed uncertain after the handshake (assist " +
                            "${if (assistTriedHash == obs.screenHash) "spent" else "unavailable"})",
                        withStage2 = false,
                    )?.let { return it }
                    continue
                }

                // -- HIGH: execute the intent --------------------------------------------
                val intentId = choice.chosenId
                if (intentId != Intents.VERIFY_DONE) exitPolicy.noteDecision(obs.screenHash)

                // Stage-2 for the target-bearing intents (ONE batched score request over
                // the bucket slate; browser-chrome excluded; code argmax; a flat spread
                // or an empty slate is NO-GOOD-TARGET — never a tap, vision 02 §3).
                if (Intents.needsTarget(intentId)) {
                    val slate = Cascade.slate(candidates, intentId)
                    if (slate.isEmpty()) {
                        consecutiveEmptySlates += 1
                        if (consecutiveEmptySlates <= EMPTY_SLATE_GRACE_STEPS) {
                            // The page's tree is still materializing (or the bucket is
                            // genuinely empty on a healthy page — the grace is short and
                            // bounded): give the next OBSERVE a chance instead of killing
                            // the task on the spot. The step still logs its row (one row
                            // per decision, doc 05 §2) with nothing executed.
                            line(
                                "decide: no stage-2 slate for '$intentId' (the " +
                                    "${Cascade.bucketFor(intentId)?.serialName} bucket is empty) - " +
                                    "empty-slate grace $consecutiveEmptySlates/$EMPTY_SLATE_GRACE_STEPS " +
                                    "(the page may still be materializing; observe-only step)",
                            )
                            history.add(
                                "step $stepIndex: empty slate for '$intentId' " +
                                    "(grace $consecutiveEmptySlates/$EMPTY_SLATE_GRACE_STEPS)",
                            )
                            logRow(
                                stepIndex, state, question, choice, candidates,
                                ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.REFUSED_BY_GUARD),
                                gateEvent = null,
                                outcome = Outcome.NO_CHANGE,
                                escalation = escalation,
                            )
                            stepIndex += 1
                            continue
                        }
                        line(
                            "decide: no stage-2 slate for '$intentId' (the " +
                                "${Cascade.bucketFor(intentId)?.serialName} bucket is empty after " +
                                "the browser-chrome exclusion) - no-good-target, never a tap",
                        )
                        // Phase 10C (vision 04 §2 v1.7.0): ONE bounded stage-1 re-ask with
                        // the fact, instead of failing the subtask on an unusable slate.
                        val reaskedEmpty = reaskIntent(
                            obs, intentId,
                            "the '${Cascade.bucketFor(intentId)?.serialName}' slate is empty",
                            question,
                        )
                        if (reaskedEmpty != null) {
                            forcedIntentNextStep = reaskedEmpty.chosenId
                            logNoChangeRow(state, question, choice, candidates)
                            stepIndex += 1
                            continue
                        }
                        cascadeFallbackStep(
                            "the '${Cascade.bucketFor(intentId)?.serialName}' slate was empty for '$intentId'",
                            withStage2 = false,
                        )?.let { return it }
                        continue
                    }
                    consecutiveEmptySlates = 0 // a live slate: the page is serving candidates again
                    when (val s2 = runStage2(intentId, slate)) {
                        is Stage2Outcome.NoGoodTarget -> {
                            line(
                                "decide: stage-2 NO-GOOD-TARGET (${s2.reason}) - " +
                                    "handshake/fallback, never a tap",
                            )
                            // Phase 10C (vision 04 §2 v1.7.0): ONE bounded stage-1 re-ask
                            // with the fact, instead of failing the subtask.
                            val reaskedFlat = reaskIntent(
                                obs, intentId,
                                "no good target on the ${Cascade.bucketFor(intentId)?.serialName} slate " +
                                    "(${s2.reason})",
                                question,
                            )
                            if (reaskedFlat != null) {
                                forcedIntentNextStep = reaskedFlat.chosenId
                                logNoChangeRow(state, question, choice, candidates, targetScores, targetViewText)
                                stepIndex += 1
                                continue
                            }
                            cascadeFallbackStep(
                                "no good target on the ${Cascade.bucketFor(intentId)?.serialName} slate " +
                                    "(${s2.reason})",
                                withStage2 = true,
                            )?.let { return it }
                            continue
                        }

                        is Stage2Outcome.Target -> {
                            cascadeTarget = s2.candidate
                            targetScores = s2.scores
                            targetViewText = s2.viewText
                        }
                    }
                }

                // fill-field: the content is WRITER-PHRASED (locked decision #4). The
                // phrase is fetched AFTER the gate resolves (below — a credential-typing
                // path never calls the writer at all: the card's secret is the payload),
                // and a phrase refusal FAILS THE STEP to recovery, never raw-goal typing.

                when (val dispatch = dispatchIntent(intentId, candidates)) {
                    is IntentDispatch.Terminal -> return dispatch.result
                    IntentDispatch.Consumed -> continue
                    IntentDispatch.FallThrough -> {}
                }
            }

            // Recovery-set answers trigger code-side recovery (vision 04 §4, level 2).
            if (question === RECOVERY_QUESTION) {
                // A recovery decision still meters the anti-oscillation window.
                exitPolicy.noteDecision(obs.screenHash)
                val recovery = enumFromSerialName(Recovery.serializer(), choice.chosenId)
                when (recovery) {
                    Recovery.BACK, Recovery.SCROLL -> {
                        val kind = if (recovery == Recovery.BACK) ActionKind.BACK else ActionKind.SCROLL
                        val gateAction = GateAction(recoveryCandidate(candidates, recovery), kind, null)
                        val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
                        check(RiskGate.classify(gateAction, pageContext) == null) {
                            "recovery actions are never gated (vision 06 §1)"
                        }
                        val executed = when (recovery) {
                            Recovery.BACK -> executor.back()
                            else -> candidates.bucketed[Bucket.SCROLL]?.firstOrNull()
                                ?.let { executor.scroll(it.cx, it.cy, it.id.startsWith(SCROLL_DOWN_PREFIX)) }
                                ?: false
                        }
                        delay(config.settleMs)
                        val post = observer.observe()
                        notePageUrl(post)
                        lastPost = post
                        val outcome = when {
                            !executed -> Outcome.ERROR
                            post.lines == obs.lines -> Outcome.NO_CHANGE
                            else -> Outcome.PROGRESSED
                        }
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, kind.toLoggedKind(), ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = outcome,
                            escalation = escalation,
                        )
                        // Phase 9: recovery steps stream to the live log too (recovery
                        // actions are never gated, so the marker is the ungated "—").
                        uiListener?.onStep(
                            LoopUiListener.StepFacts(
                                stepIndex = stepIndex,
                                verb = kind.name.lowercase(),
                                label = recovery.serial(),
                                confidence = choice.confidence,
                                gateMarker = LoopUiListener.gateMarker(null),
                            ),
                        )
                        history.add("step $stepIndex: recovery ${recovery.serial()} -> ${serial(outcome)}")
                        stuck.record(
                            StuckDetector.Footprint(
                                actionKey = "recovery:${recovery.serial()}",
                                screenHash = post.screenHash,
                                textLines = post.lines,
                                isError = outcome == Outcome.ERROR,
                            ),
                        )
                        stepIndex += 1
                        if (assessStuck()) {
                            // Phase 10 + vision 04 §1 v1.5.0: a LADDER run routes L3
                            // through the GUARANTEED owner card — the task-level kill is
                            // only for the legacy (non-ladder) path.
                            handleStuckKill("still stuck after the recovery action - L3 auto-kill")
                                ?.let { return it }
                        }
                    }

                    Recovery.REPHRASE -> {
                        // Phase-10 stub (phase doc §1: "rephrase/rescue degrade to no-ops
                        // here, loop must handle their absence"): deterministic narrow,
                        // no LLM call, no gesture, no log row — a goal-text meta-decision
                        // tracked in the run report and HISTORY.
                        metaDecisions += 1
                        goalText = phraseStub(goalText, "goal").ifBlank { goalText }
                        line("decide: rephrase -> Phase-10 stub; goal text is now \"$goalText\"")
                        history.add("step $stepIndex: recovery rephrase (stub) -> goal is now \"$goalText\"")
                        stepIndex += 1
                    }

                    Recovery.GIVE_UP -> {
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = Outcome.EXITED,
                            escalation = escalation,
                        )
                        // The give-up decision consumed its step (the row above carries
                        // this stepIndex — the next decision must not reuse it).
                        stepIndex += 1
                        // Phase 10: on a LADDER run the engine's give-up is a SUBTASK
                        // failure (the bounded re-plan / owner-card path); the legacy
                        // path keeps the task-level STUCK_KILL.
                        handleSubtaskFailureOrKill("engine chose give-up from the recovery set")
                            ?.let { return it }
                    }
                }
                continue
            }

            // A target-bearing intent reached the gate: the stage-2 argmax target is the
            // acting candidate (the verify-done/open-site/go-back/scroll intents already
            // continued above — they own their code paths).
            val candidate = requireNotNull(cascadeTarget) {
                "the cascade reached the gate without a stage-2 target - intent '${choice.chosenId}'"
            }

            // -- 4. GATE (before any execution, vision 06 §1-2) -----------------------------
            val kind = when (choice.chosenId) {
                Intents.FILL_FIELD -> ActionKind.TYPE
                else -> actionKindFor(candidate)
            }
            // Classification input for TYPE actions (the pre-08F behavior): the stub
            // phrase — a pure function, never a writer call. The WRITER phrase is
            // fetched after the gate resolves (the credential path never calls the
            // writer; a fall-through to any phrase there is a loud failure).
            val stubPhrase = if (kind == ActionKind.TYPE) phraseStub(goalText, candidate.label) else null
            val gateAction = GateAction(candidate, kind, stubPhrase)
            val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
            val codeClass: HClass? = RiskGate.classify(gateAction, pageContext)

            // Phase 8E (vision 06 §3, additive-only): when the code net is UNCERTAIN —
            // no confident H-class, fail-to-confirm territory — the loop consults the
            // primary engine with the §3 question. The verdict can only ADD H9
            // ([AdditiveEngineCheck]: suppression is unrepresentable); a confident code
            // class means the engine is never even asked. Both outcomes are logged with
            // the trigger source (code-net vs additive-engine); H9 then rides the
            // ordinary gate mechanics unchanged (card, 30s=deny, per-task same-site
            // trust).
            var hclass: HClass? = codeClass
            var gateTrigger = GATE_TRIGGER_CODE_NET
            if (codeClass == null &&
                RiskGate.mutationNet(gateAction, pageContext) == RiskGate.MutationNet.SUSPICIOUS
            ) {
                val verdict = AdditiveEngineCheck.evaluate(gateAction, pageContext.site) { proposition ->
                    // doc 06 §2a (PREUAT2-4 (A)): the consult proposition embeds the raw
                    // target label — a sanctioned post-injection echo must never reach
                    // ANY engine. Masked here with the same redaction seam as the decide
                    // question; the consult itself still runs (skipping it would fail
                    // the H9 net open), and the masking is loud on the step log.
                    val masked = redactedLabel(proposition, redactionKeys())
                    if (masked != proposition) {
                        line(
                            "gate: additive consult proposition carried a sanctioned secret - " +
                                "masked (${TextSource.REDACTED}) before the engine",
                        )
                    }
                    uiListener?.onThinking(true)
                    val consultPTrue = engine.judge(
                        state.text,
                        NoulQuestion(AdditiveEngineCheck.QUESTION_NAME, masked),
                    ).pTrue
                    uiListener?.onThinking(false)
                    band(consultPTrue)
                }
                hclass = AdditiveEngineCheck.resolve(codeClass, verdict)
                gateTrigger = GATE_TRIGGER_ADDITIVE_ENGINE
                line(
                    "gate: additive engine check on ${pageContext.site} -> " +
                        if (verdict == AdditiveEngineCheck.Verdict.AddH9) {
                            "engine non-LOW: H9 ADDED (gate rate raised, vision 06 §3)"
                        } else {
                            "engine LOW: no gate (logged for the audit trail)"
                        },
                )
            }

            var proceed = true
            var taskAborted = false
            var gateEvent: LoggedGateEvent? = null
            var disposition = ActionDisposition.EXECUTED
            // The most recent gate-card presentation's fronting state + page URL (the
            // 2026-10-08 restore seam): captured at each present() call site — the
            // trusted paths present no card and therefore restore nothing.
            var gateCardFronted = false
            var gateCardUrl: String? = null
            // RC2 secret binding (2026-10-08 pre-UAT2): the candidate id an
            // ApproveWithSecret approval was given FOR. Set only on the credential-card
            // approval; the EXECUTE site verifies the acting candidate matches before
            // injecting (fail loudly on mismatch — the wrong-TextSource precedent).
            var secretBoundTo: String? = null
            // The typed-text source (vision 04 §2 step 5, Phase 08F + the 10B fill-field
            // law): the writer phrase for fill-field typing (locked decision #4, fetched
            // after the gate resolves); the credential path REPLACES it with the card
            // secret ([TextSource.GateSuppliedSecret]) — or refuses loudly. Null for
            // every non-typing action.
            var textSource: TextSource? = null
            // doc 06 §2a: a TYPE action the gate resolved to a credential class types a
            // credential — the card's masked input is the secret's ONLY lawful source.
            val credentialTyping = hclass != null &&
                kind == ActionKind.TYPE &&
                hclass in CREDENTIAL_TYPING_CLASSES

            if (credentialTyping) {
                // Phase 08F (doc 06 §2a): the secret comes from the card, so the card is
                // presented for EVERY credential-typing action — per-task trust cannot
                // supply a secret (a trusted execution has no card, hence no lawful
                // TextSource). The resolution still flows through the TaskGate's legal
                // transitions below, so trust bookkeeping, the double-refusal abort, and
                // the gate logging are the gate's own — nothing bypasses or duplicates it.
                val h = hclass!!
                val card = GateCard.build(h, gateAction, pageContext)
                check(card.credentialTyping) {
                    "credential-typing action (${h.name}) must build the credential card variant"
                }
                val resolution = gatePresenter.present(card)
                gateCardFronted = frontedHarness(gatePresenter)
                gateCardUrl = card.url
                when (resolution) {
                    is GateResolution.ApproveWithSecret -> {
                        when (val request = taskGate.decide(GateEvent.REQUEST(h, pageContext.site))) {
                            is GateDecision.EXECUTE -> {
                                gateEvent = LoggedGateEvent(
                                    h.toLoggedHClass(),
                                    LoggedGateDecision.APPROVED,
                                    request.trustedFromTask,
                                )
                            }
                            GateDecision.ASK -> {
                                val approved = taskGate.decide(GateEvent.APPROVE)
                                val trusted = approved is GateDecision.EXECUTE && approved.trustedFromTask
                                gateEvent = LoggedGateEvent(
                                    h.toLoggedHClass(), LoggedGateDecision.APPROVED, trusted,
                                )
                            }
                            else -> throw IllegalStateException(
                                "gate REQUEST behind a secret-approved credential card resolved to " +
                                    "$request - TaskGate state machine misuse",
                            )
                        }
                        line(
                            "gate: owner APPROVED \"${card.classLabel}\" on ${card.site} - secret typed " +
                                "on the card (${TextSource.REDACTED}, never logged)",
                        )
                        cardSecrets.add(resolution.secret)
                        // RC2 binding: this approval is bound to THIS card's candidate;
                        // the execute site verifies before injecting.
                        secretBoundTo = candidate.id
                        textSource = TextSource.GateSuppliedSecret(resolution.secret)
                    }
                    GateResolution.APPROVED -> throw IllegalStateException(
                        "credential card for ${h.name} on ${pageContext.site} approved WITHOUT the " +
                            "card-supplied secret - wrong TextSource; failing closed, never falling " +
                            "through to the stub phrase",
                    )
                    GateResolution.DENIED -> {
                        val (d, e, abort) = resolveCredentialRefusal(taskGate, h, pageContext.site, timedOut = false)
                        disposition = d
                        gateEvent = e
                        taskAborted = abort
                        proceed = false
                        line("gate: owner DENIED the credential card - action skipped" +
                            if (abort) " (second refusal - task abort)" else "")
                    }
                    GateResolution.TIMEOUT -> {
                        val (d, e, abort) = resolveCredentialRefusal(taskGate, h, pageContext.site, timedOut = true)
                        disposition = d
                        gateEvent = e
                        taskAborted = abort
                        proceed = false
                        line("gate: credential card TIMED OUT (= deny, fail-safe)" +
                            if (abort) " (second refusal - task abort)" else "")
                    }
                }
            } else if (hclass != null) {
                line("gate: ${hclass.name} on ${pageContext.site} (trigger: $gateTrigger) - checking per-task trust")
                when (val gateDecision = taskGate.decide(GateEvent.REQUEST(hclass, pageContext.site))) {
                    is GateDecision.EXECUTE -> {
                        gateEvent = LoggedGateEvent(
                            hclass.toLoggedHClass(),
                            LoggedGateDecision.APPROVED,
                            gateDecision.trustedFromTask,
                        )
                        line("gate: trusted per-task - executing" +
                            if (gateDecision.trustedFromTask) " (trustedFromTask)" else "")
                    }
                    GateDecision.ASK -> {
                        val card = GateCard.build(hclass, gateAction, pageContext)
                        val resolution = gatePresenter.present(card)
                        gateCardFronted = frontedHarness(gatePresenter)
                        gateCardUrl = card.url
                        when (resolution) {
                            GateResolution.APPROVED -> {
                                val approved = taskGate.decide(GateEvent.APPROVE)
                                val trusted = approved is GateDecision.EXECUTE && approved.trustedFromTask
                                gateEvent = LoggedGateEvent(
                                    hclass.toLoggedHClass(), LoggedGateDecision.APPROVED, trusted,
                                )
                                line("gate: owner APPROVED \"${card.classLabel}\" on ${card.site}")
                            }
                            is GateResolution.ApproveWithSecret -> throw IllegalStateException(
                                "non-credential card for ${hclass.name} on ${pageContext.site} resolved " +
                                    "ApproveWithSecret - a secret was offered where the TextSource " +
                                    "expects a phrase; failing closed, never executing or falling through",
                            )
                            GateResolution.DENIED -> {
                                val (d, abort) = refusalOutcome(taskGate.decide(GateEvent.DENY), ActionDisposition.REFUSED_BY_OWNER)
                                disposition = d
                                taskAborted = abort
                                proceed = false
                                gateEvent = LoggedGateEvent(hclass.toLoggedHClass(), LoggedGateDecision.DENIED)
                                line("gate: owner DENIED - action skipped" +
                                    if (taskAborted) " (second refusal - task abort)" else "")
                            }
                            GateResolution.TIMEOUT -> {
                                val (d, abort) = refusalOutcome(taskGate.decide(GateEvent.TIMEOUT), ActionDisposition.GATE_TIMEOUT)
                                disposition = d
                                taskAborted = abort
                                proceed = false
                                gateEvent = LoggedGateEvent(hclass.toLoggedHClass(), LoggedGateDecision.TIMEOUT)
                                line("gate: card TIMED OUT (= deny, fail-safe)" +
                                    if (taskAborted) " (second refusal - task abort)" else "")
                            }
                        }
                    }
                    GateDecision.SKIP -> throw IllegalStateException(
                        "gate SKIP without a pending card - TaskGate state machine misuse",
                    )
                    GateDecision.ABORT_TASK -> throw IllegalStateException(
                        "gate ABORT_TASK outside a refusal - TaskGate state machine misuse",
                    )
                }
            }

            // The 2026-10-08 pre-UAT defect: on the no-overlay card path Chrome is still
            // backgrounded behind the harness after the resolution — restore it BEFORE
            // the loop continues (the EXECUTE gesture, or the post-refusal observation
            // when the card was denied). A task-ending resolution (the second-refusal
            // abort) never restores: the run is over and the harness's terminal
            // auto-return fronts the harness. Overlay/never-fronting presenters leave
            // both flags untouched — a byte-identical no-op.
            if (gateCardFronted && !taskAborted) {
                restoreBrowserForeground(
                    "the gate card",
                    "card" to gateCardUrl,
                    "observation" to obs.url,
                    "last-known" to lastKnownPageUrl,
                )
            }

            // The fill-field phrase fetch, AFTER the gate resolved (locked decision #4,
            // vision 04 §2 v1.6.0): a credential-typing path never reaches this (its
            // TextSource is the card's secret — the writer is never called on a
            // credential path); a refused gate needs no text; and a phrase refusal
            // FAILS THE STEP to recovery — never raw-goal typing.
            if (proceed && !taskAborted && kind == ActionKind.TYPE && textSource == null) {
                val fieldLabel = redactedLabel(candidate.label, redactionKeys())
                val phrase = runPhrase(subtaskInstruction(), fieldLabel)
                if (phrase == null) {
                    phraseFailureStep(fieldLabel)?.let { return it }
                    continue
                }
                phraseText = phrase.text
                textSource = TextSource.LlmPhrase(phrase.text)
                line("fill-field: phrased (${phrase.promptVersion}, ${phrase.text.length} chars)")
            }

            // -- 5. EXECUTE -------------------------------------------------------------------
            var execOk = true
            // RC1: the terminal text-entry failure cause (null when nothing failed or
            // the executor did not report one); the execute line renders it.
            var typeFailureCause: TextEntryFailure? = null
            if (proceed) {
                execOk = when (kind) {
                    ActionKind.TAP, ActionKind.SUBMIT -> executor.tap(candidate.cx, candidate.cy)
                    ActionKind.TYPE -> {
                        // Phase 08F (vision 04 §2 step 5): the TextSource is the ONLY
                        // lawful payload. A TYPE action without one, a secret on a
                        // non-credential action, or a credential action that somehow
                        // kept the stub phrase is a wiring bug — fail loudly, never
                        // fall through. The secret value is passed straight to the
                        // injection call and bound nowhere else (doc 06 §2a).
                        val source = requireNotNull(textSource) {
                            "TYPE action ${candidate.id} has no TextSource - the typed-text seam is unwired"
                        }
                        val text = when (source) {
                            is TextSource.StubPhrase -> source.text
                            is TextSource.LlmPhrase -> source.text
                            is TextSource.GateSuppliedSecret -> {
                                check(credentialTyping) {
                                    "a gate-supplied secret reached a non-credential typing action - wrong TextSource"
                                }
                                // RC2 binding check: the secret may only be injected
                                // into the candidate it was approved FOR.
                                requireSecretCandidateBinding(secretBoundTo, candidate.id)
                                source.value
                            }
                        }
                        // RC1: the executor reports the terminal failure cause; the
                        // execute line logs it as `-> FAILED (CAUSE)`.
                        executor.enterText(candidate.cx, candidate.cy, text) { typeFailureCause = it }
                    }
                    ActionKind.SCROLL -> executor.scroll(
                        candidate.cx,
                        candidate.cy,
                        candidate.id.startsWith(CandidateEnumerator.SCROLL_DOWN_ID),
                    )
                    ActionKind.BACK -> executor.back()
                    ActionKind.FINISH -> true
                }
                // Credential-sourced text renders [credential-redacted] in the step log
                // (doc 05 §2); the stub-phrase path keeps its pre-08F line unchanged.
                // The acted-on label masks at SOURCE too (PREUAT2-4 (B)): it is page
                // text that can carry a sanctioned post-injection echo, so the value
                // never transits the in-memory stepLog raw — the finish scrub stays as
                // the report boundary's second layer.
                val typedMarker =
                    if (kind == ActionKind.TYPE && textSource is TextSource.GateSuppliedSecret) {
                        " text=${TextSource.REDACTED}"
                    } else {
                        ""
                    }
                line(
                    executeLine(
                        kind = kind,
                        label = candidate.label,
                        cx = candidate.cx,
                        cy = candidate.cy,
                        dispatched = execOk,
                        typedMarker = typedMarker,
                        redact = redactionKeys(),
                        failureCause = typeFailureCause.takeIf { !execOk },
                    ),
                )
            }

            // Post-step fresh observation (outcome diff, guards, stuck hash).
            delay(config.settleMs)
            val post = observer.observe()
            notePageUrl(post)
            lastPost = post
            val outcome = when {
                !proceed -> Outcome.NO_CHANGE
                !execOk -> Outcome.ERROR
                post.lines == obs.lines -> Outcome.NO_CHANGE
                else -> Outcome.PROGRESSED
            }

            // -- 6. LOG (exactly one row per navigation decision) -------------------------------
            logRow(
                stepIndex, state, question, choice, candidates,
                ExecutedAction(
                    candidateId = if (kind == ActionKind.BACK || kind == ActionKind.FINISH) null else candidate.id,
                    kind = kind.toLoggedKind(),
                    disposition = disposition,
                ),
                gateEvent,
                outcome,
                escalation = escalation,
                // doc 05 §2 `typedSecret`: true when this step typed a credential
                // supplied via the gate card — the VALUE is never stored (the row
                // carries the flag, the step log renders [credential-redacted]).
                typedSecret = proceed && textSource is TextSource.GateSuppliedSecret,
                // The cascade's stage-2 evidence (doc 05 §2 v1.6.0): the executed slate +
                // the TARGET STATE VIEW the batch rode (the score case's state law).
                targetScores = targetScores,
                targetStateText = targetViewText,
            )
            // Phase 9: the live-log line (compact product facts) fires AT the LOG step.
            // The acted-on label masks through the SAME sanctioned-secret redaction fold
            // the execute line uses (doc 05 §2 / doc 06 §2a) — masked at source, so a
            // post-injection page echo can never reach the overlay through a label.
            // Phase 10B: the verb is the stage-1 INTENT (the compact line's format law
            // is untouched — the verb is data).
            uiListener?.onStep(
                LoopUiListener.StepFacts(
                    stepIndex = stepIndex,
                    verb = choice.chosenId,
                    label = redactedLabel(candidate.label, redactionKeys()),
                    confidence = choice.confidence,
                    gateMarker = LoopUiListener.gateMarker(gateEvent),
                ),
            )
            // The history digest quotes the acted-on label — page text that can carry a
            // sanctioned post-injection echo (the acted-on field's own a11y text IS the
            // typed secret on live Chrome). It renders [credential-redacted] here
            // (doc 05 §2); the state builder masks every section the same way. The verb
            // is the stage-1 intent.
            history.add(
                "step $stepIndex: ${choice.chosenId} \"${redactedLabel(candidate.label, redactionKeys())}\" -> " +
                    serial(outcome) +
                    (gateEvent?.let { " (gate ${it.decision.name.lowercase()})" } ?: ""),
            )

            guards!!.record(
                url = post.url,
                contentTextNodes = post.contentTextNodes,
                textTyped = proceed && execOk && kind == ActionKind.TYPE,
                submitExecuted = proceed && execOk && kind == ActionKind.SUBMIT,
                actionExecuted = proceed && execOk,
            )
            // Task working memory (Phase 08G, vision 04 §1): resolve the acted-on row —
            // an executed action marks the target PROCESSED; a gate refusal marks it
            // SKIPPED (the owner's denial is authoritative for that row); a FAILED
            // dispatch changes nothing (the row stays pending, retryable). A synthetic
            // or non-row candidate resolves to nothing.
            val stepTracker = tracker
            if (stepTracker != null) {
                obs.nodes.firstOrNull { it.id == candidate.id }?.let { node ->
                    when {
                        !proceed -> stepTracker.markSkipped(node)
                        execOk -> stepTracker.markProcessed(node)
                        else -> null
                    }
                }
            }
            stuck.record(
                StuckDetector.Footprint(
                    actionKey = "${kind.name}:${candidate.id}",
                    screenHash = post.screenHash,
                    textLines = post.lines,
                    isError = outcome == Outcome.ERROR,
                ),
            )
            stepIndex += 1

            if (taskAborted) {
                return finish(
                    TerminalState.GUARD_ABORT,
                    "the same high-stakes action (${gateEvent?.hclass}) was refused twice in this task",
                    stepIndex,
                )
            }
            if (assessStuck()) {
                // Phase 10 + vision 04 §1 v1.5.0: a LADDER run routes L3 through the
                // GUARANTEED owner card; the legacy path keeps the task-level STUCK_KILL.
                handleStuckKill("still stuck after hint and strategy switch - L3 auto-kill")
                    ?.let { return it }
            }
        }
    }

    // -- helpers -------------------------------------------------------------------------

    /** Step-log annotation for an escalated decision (ids only, never distributions). */
    private fun escalationSuffix(record: DecisionRecord?): String =
        if (record?.escalated == true) " [escalated: ${record.reason?.name?.lowercase()}]" else ""

    private fun usable(obs: StepObservation): Boolean =
        obs.lines.size >= config.minUsableNodes &&
            (obs.pageNodeCount == null || obs.pageNodeCount >= config.minPageNodes)

    /**
     * Observation readiness (2026-10-08 pre-UAT2 RC1): a usable tree AND the browser
     * being the ACTIVE window ([StepObservation.windowPackage] in the browser
     * allowlist). A readable-but-backgrounded Chrome must keep the wait spinning —
     * the focus-first injection cannot land in a background app.
     */
    private fun ready(obs: StepObservation): Boolean =
        usable(obs) && BrowserPackages.isBrowser(obs.windowPackage)

    /** A gate refusal resolves to SKIP, or to ABORT_TASK on the second refusal. */
    private fun refusalOutcome(decision: GateDecision, onSkip: ActionDisposition): Pair<ActionDisposition, Boolean> =
        when (decision) {
            GateDecision.SKIP -> onSkip to false
            GateDecision.ABORT_TASK -> ActionDisposition.TASK_ABORTED to true
            else -> throw IllegalStateException(
                "a gate refusal must resolve to SKIP or ABORT_TASK, got $decision",
            )
        }

    /**
     * A DENIED/TIMEOUT credential-card resolution (Phase 08F, doc 06 §2a) fed through
     * the gate's legal transitions. Untrusted: REQUEST → ASK, then the refusal event —
     * SKIP, or ABORT_TASK on the second same-(class, site) refusal. Trusted: the
     * REQUEST already auto-executes (no pending card), and the owner declined to type
     * the secret — there is no lawful [TextSource], so the action skips locally and the
     * refusal logs with `trustedFromTask=false` (the TaskGate records nothing because
     * nothing was pending; the next credential action asks again).
     */
    private fun resolveCredentialRefusal(
        taskGate: TaskGate,
        hclass: HClass,
        site: String,
        timedOut: Boolean,
    ): Triple<ActionDisposition, LoggedGateEvent, Boolean> {
        val loggedDecision = if (timedOut) LoggedGateDecision.TIMEOUT else LoggedGateDecision.DENIED
        val skipDisposition = if (timedOut) ActionDisposition.GATE_TIMEOUT else ActionDisposition.REFUSED_BY_OWNER
        return when (val request = taskGate.decide(GateEvent.REQUEST(hclass, site))) {
            is GateDecision.EXECUTE -> Triple(
                skipDisposition,
                LoggedGateEvent(hclass.toLoggedHClass(), loggedDecision, false),
                false,
            )
            GateDecision.ASK -> {
                val (d, abort) = refusalOutcome(
                    taskGate.decide(if (timedOut) GateEvent.TIMEOUT else GateEvent.DENY),
                    skipDisposition,
                )
                Triple(d, LoggedGateEvent(hclass.toLoggedHClass(), loggedDecision), abort)
            }
            else -> throw IllegalStateException(
                "gate REQUEST behind a refused credential card resolved to $request - " +
                    "TaskGate state machine misuse",
            )
        }
    }

    /** The action kind the executor will perform for this candidate (the gate's input). */
    private fun actionKindFor(candidate: Candidate): ActionKind = when {
        candidate.id == CandidateEnumerator.BACK_ID -> ActionKind.BACK
        candidate.id.startsWith(CandidateEnumerator.SCROLL_DOWN_ID) ||
            candidate.id.startsWith(CandidateEnumerator.SCROLL_UP_ID) -> ActionKind.SCROLL
        "edit" in candidate.kinds -> ActionKind.TYPE
        SUBMIT_TRAP.containsMatchIn("${candidate.label} ${candidate.id}".lowercase()) -> ActionKind.SUBMIT
        else -> ActionKind.TAP
    }

    /** A target-less stand-in for recovery actions (back/scroll are never gated). */
    private fun recoveryCandidate(candidates: CandidateList, recovery: Recovery): Candidate {
        val bucket = if (recovery == Recovery.BACK) Bucket.BACK else Bucket.SCROLL
        val example = candidates.bucketed[bucket]?.firstOrNull()
        return Candidate(
            id = "recovery-${recovery.serial()}",
            label = recovery.serial(),
            kinds = emptyList(),
            cx = example?.cx ?: 0,
            cy = example?.cy ?: 0,
            bucket = bucket,
            source = CandidateSource.SYNTHETIC,
            sourceOrder = 0,
            synthetic = true,
        )
    }

    private fun ActionKind.toLoggedKind(): LoggedActionKind = when (this) {
        ActionKind.TAP -> LoggedActionKind.TAP
        ActionKind.TYPE -> LoggedActionKind.TYPE
        ActionKind.SUBMIT -> LoggedActionKind.SUBMIT
        ActionKind.SCROLL -> LoggedActionKind.SCROLL
        ActionKind.BACK -> LoggedActionKind.BACK
        ActionKind.FINISH -> LoggedActionKind.FINISH
    }

    private fun ExitPath.toLoggedPath(): LoggedExitPath = when (this) {
        ExitPath.HIGH -> LoggedExitPath.HIGH
        ExitPath.SUSTAINED -> LoggedExitPath.SUSTAINED
        ExitPath.OWNER_CONFIRM -> LoggedExitPath.OWNER_CONFIRM
    }

    private fun GuardLevel.toLoggedLevel(): LoggedGuardLevel = when (this) {
        GuardLevel.WEAK -> LoggedGuardLevel.WEAK
        GuardLevel.STRONG -> LoggedGuardLevel.STRONG
        GuardLevel.NONE -> throw IllegalStateException(
            "a NONE guard can never ride an accepted exit - no path accepts without code evidence",
        )
    }

    /** The exit-check vocabulary carries NONE (a guard-failed finish is a logged refusal). */
    private fun GuardLevel.toExitCheckLevel(): ExitCheckGuardLevel = when (this) {
        GuardLevel.WEAK -> ExitCheckGuardLevel.WEAK
        GuardLevel.STRONG -> ExitCheckGuardLevel.STRONG
        GuardLevel.NONE -> ExitCheckGuardLevel.NONE
    }

    private fun HClass.toLoggedHClass(): navigator.core.log.LoggedHClass = when (this) {
        HClass.H1_PAYMENT -> navigator.core.log.LoggedHClass.H1
        HClass.H2_PURCHASE_CONFIRM -> navigator.core.log.LoggedHClass.H2
        HClass.H3_PASSWORD_CHANGE -> navigator.core.log.LoggedHClass.H3
        HClass.H4_LOGIN -> navigator.core.log.LoggedHClass.H4
        HClass.H5_ACCOUNT -> navigator.core.log.LoggedHClass.H5
        HClass.H6_UPLOAD -> navigator.core.log.LoggedHClass.H6
        HClass.H7_DOWNLOAD -> navigator.core.log.LoggedHClass.H7
        HClass.H8_CREDENTIAL_TYPING -> navigator.core.log.LoggedHClass.H8
        HClass.H9_PERSISTENT_MUTATION -> navigator.core.log.LoggedHClass.H9
    }

    private fun serial(outcome: Outcome): String = serialNameOf(Outcome.serializer(), outcome)

    private fun Recovery.serial(): String = serialNameOf(Recovery.serializer(), this)

    private fun TriageClass.serial(): String = serialNameOf(TriageClass.serializer(), this)

    companion object {
        const val GOAL_ACHIEVED_QUESTION = "goal-achieved"
        const val REFUSAL_MESSAGE = "I can't read this site"
        const val PARTIAL_RESULT_MAX_LINES = 5
        const val PARTIAL_RESULT_MAX_CHARS = 300
        const val SCROLL_DOWN_PREFIX = "${CandidateEnumerator.SCROLL_DOWN_ID}@"

        /** The gate-event trigger sources logged on the step log (Phase 8E, vision 06 §3):
         *  which part of the broad net fired — the code net alone, or the additive engine
         *  check (the engine may only add, and is consulted only when the code net is
         *  uncertain, so "both" is unrepresentable by construction). */
        const val GATE_TRIGGER_CODE_NET = "code-net"
        const val GATE_TRIGGER_ADDITIVE_ENGINE = "additive-engine"

        /**
         * Phase 10 (vision 04 §1 v1.4.0, ART-5): the bounded re-plan cap — at most 2
         * restructure calls per task AFTER the initial one (3 ladder generations max);
         * the owner card's Re-plan draws the SAME cap.
         */
        const val MAX_REPLANS = 2

        /**
         * Phase 10 (vision 04 §1 v1.4.0, ART-5): consecutive subtask failures before the
         * OWNER escalation card ("Retry / Re-plan / Stop"; 30s timeout = Stop).
         */
        const val SUBTASK_FAILURE_CARD_THRESHOLD = 3

        /** The extraction final-state excerpt cap (lines of page prose sent to the writer). */
        const val EXTRACTION_STATE_MAX_LINES = 12

        /**
         * The empty-slate grace (gate-105): consecutive observe-only steps granted when a
         * target-bearing intent's slate is empty (a partially-served tree materializing)
         * before the ordinary no-good-target fallback runs. Code constant (ART-5).
         */
        const val EMPTY_SLATE_GRACE_STEPS = 3

        /**
         * The handshake assist's page-material excerpt cap (Phase 10B, ART-5): the
         * observation lines the assist prompt may carry beside the title/url line — the
         * writer re-describes from bounded page material only.
         */
        const val ASSIST_MATERIAL_MAX_LINES = 12

        /** Taps that read as submissions (the gate's SUBMIT path: H1/H3/H4 submissions). */
        internal val SUBMIT_TRAP = Regex("submit|\\bsearch\\b|^go\\b|\\bgo\\b|\\bsend\\b")

        /** The stuck-recovery question (vision 03 §3 / vision 04 §4: the recovery set). */
        val RECOVERY_QUESTION: ChoiceQuestion = ChoiceQuestion(
            name = "recovery",
            options = Recovery.entries.map { recovery ->
                Option(
                    id = serialName(recovery),
                    label = recovery.name.lowercase().replace('_', '-'),
                    description = "recovery action chosen by the engine when stuck",
                )
            },
        )

        /**
         * Sanctioned-secret redaction for question payloads (doc 05 §2 via doc 06 §2a:
         * the value itself is NEVER stored — rows render [TextSource.REDACTED]). A
         * page-side echo of an injected secret rides candidate labels into the engine
         * payload and the row's question field; the 2026-10-07 post-injection deadlock
         * fix masks them exactly like the state text's untrusted block. Empty `redact`
         * (every non-credential task) keeps the labels byte-identical.
         */
        private fun redactedLabel(label: String, redact: List<String>): String =
            redact.fold(label) { acc, secret ->
                if (secret.isNotEmpty()) acc.replace(secret, TextSource.REDACTED) else acc
            }

        /**
         * The EXECUTE step-log line, built in exactly one place so its redaction law is
         * unit-pinnable (PREUAT2-4 (B)): the acted-on label is page text that can carry
         * a sanctioned post-injection echo, so it renders masked [TextSource.REDACTED]
         * at source — the value never transits the in-memory stepLog raw. The empty
         * `redact` default of every non-credential task keeps the line byte-identical.
         * A failed text entry renders its terminal cause (RC1, 2026-10-08):
         * `-> FAILED (WRONG_WINDOW)`; a failure without a reported cause stays plain
         * `-> FAILED`.
         */
        internal fun executeLine(
            kind: ActionKind,
            label: String,
            cx: Int,
            cy: Int,
            dispatched: Boolean,
            typedMarker: String,
            redact: List<String> = emptyList(),
            failureCause: TextEntryFailure? = null,
        ): String {
            val outcome = when {
                dispatched -> "dispatched"
                failureCause != null -> "FAILED (${failureCause.name})"
                else -> "FAILED"
            }
            return "execute: ${kind.name.lowercase()} \"${redactedLabel(label, redact)}\" at " +
                "($cx,$cy)$typedMarker -> $outcome"
        }

        /**
         * RC2 secret binding (2026-10-08 pre-UAT2): an ApproveWithSecret approval is
         * bound to the candidate id its card was built for; the execute site verifies
         * the acting candidate matches BEFORE any injection. A mismatch (or a secret
         * with no binding at all) is the wrong-TextSource class of wiring bug — it
         * fails loudly and injects nothing. Internal so the JVM tests can pin both
         * directions (the loop's single-pass wiring cannot diverge them naturally).
         */
        internal fun requireSecretCandidateBinding(boundId: String?, actingId: String) {
            check(boundId != null && boundId == actingId) {
                "a gate-supplied secret is bound to candidate [$boundId] but the acting " +
                    "candidate is [$actingId] - refusing to inject into an unbound target"
            }
        }

        /**
         * The shadow's two-stage bucketed form of this step's taxonomy (vision 02 §3 /
         * vision 05 §2 `questionShadow`): the row's shadow-question field keeps the
         * bucketed enumeration record (schema compatibility — the shadow itself answers
         * the cascade's forms since the Phase-10B migration).
         */
        fun shadowQuestion(candidates: CandidateList, redact: List<String> = emptyList()): BucketedQuestion =
            BucketedQuestion(
                name = "shadow-action",
                buckets = candidates.bucketed.mapValues { (_, list) ->
                    list.map { candidate ->
                        Option(
                            id = candidate.id,
                            label = redactedLabel(candidate.label, redact),
                            description = "bucket=${serialName(candidate.bucket)}",
                        )
                    }
                },
            )

        fun serialName(recovery: Recovery): String = serialNameOf(Recovery.serializer(), recovery)

        fun serialName(bucket: Bucket): String = serialNameOf(Bucket.serializer(), bucket)
    }
}

// -- loop-facing observation / logging types (pure JVM, fakeable) -----------------------

/**
 * One fresh observation of the browser (the loop's OBSERVE product). Pure JVM data so
 * fakes construct it without Robolectric.
 */
data class StepObservation(
    /** URL-bar read as a string (`hidden` / `unavailable` per vision 03 §1). */
    val url: String,
    /** Page title, as far as the shell exposes one (may be blank). */
    val title: String,
    /** Serialized tree lines (vision 03 §2). */
    val lines: List<String>,
    /** Observed nodes in deterministic read order — [CandidateEnumerator]'s input. */
    val nodes: List<ObservedNode>,
    /**
     * Node count of the page-content subtree (the WebView content root's descendants),
     * or null when the observer cannot tell. The unreadable-tree rule (vision 03 §5,
     * "one undifferentiated node") needs the CONTENT count: browser toolbar nodes must
     * not make a canvas-only page look readable.
     */
    val pageNodeCount: Int? = null,
    /**
     * The bounded non-candidate page prose (Phase 08H, vision 03 §1 content extraction):
     * [navigator.core.state.ContentExtractor]'s deterministic product over [nodes],
     * rendered inside the state text's `UNTRUSTED PAGE CONTENT` block. Empty when the
     * observer supplies none (fakes default to it).
     */
    val excerpt: List<String> = emptyList(),
    /**
     * The ACTIVE window's package (2026-10-08 pre-UAT2 RC1, additive default ""): the
     * focus half of observation readiness. A readable tree alone is NOT readiness —
     * Chrome's tree stays readable while Chrome sits in the background (the live-proven
     * injection failure 13ms after a "ready" probe). Empty means "unknown" (fakes),
     * which never passes the browser-window bar — set it on fakes that must pass.
     */
    val windowPackage: String = "",
) {
    /** Non-interactive, non-blank text nodes — the completion guards' content measure. */
    val contentTextNodes: Int
        get() = nodes.count { !it.clickable && !it.editable && it.text.isNotBlank() }

    /** Deterministic screen hash (the stuck detector's unchanged-screen signal). */
    val screenHash: Int
        get() = lines.hashCode()
}

/** The observation edge (the Android service adapter; fakes in tests). */
interface LoopObserver {
    suspend fun observe(): StepObservation
}

/**
 * The logging edge (vision 05 §2): exactly one row per navigation decision plus the
 * third row type's edge — exactly one [ExitCheckRow] per finish check, refusals
 * included (vision 05 §2 amendment 2026-10-06). The Room-backed implementation wraps
 * [navigator.app.log.DecisionLogger]; tests collect.
 */
interface DecisionLogSink {
    suspend fun write(row: DecisionLogRow)

    /** The exit-calibration corpus edge: insert-only, one row per finish check. */
    suspend fun writeExitCheck(row: ExitCheckRow)
}

/** How a task ended (vision 04 §3 exits + the documented pre/extra-navigation aborts). */
enum class TerminalState {
    /** noul HIGH + guard pass (ART-4). */
    GOAL_ACHIEVED,

    /** Budget hard stop: clean stop, partial results, everything logged. */
    BUDGET_EXHAUSTED,

    /** StuckDetector L3 (or engine give-up at L2; or the ladder escalation card's Stop). */
    STUCK_KILL,

    /** The same high-stakes action refused twice in one task. */
    GUARD_ABORT,

    /** Harness cancel. */
    USER_CANCEL,

    /** Graceful refusal for an unreadable tree (vision 03 §5 — "I can't read this site"). */
    REFUSED,

    /** Triage answered `impossible` — explain and abort before any navigation (vision 04 §1). */
    TRIAGE_IMPOSSIBLE,

    /**
     * Phase 10 (vision 04 §1 v1.4.0): the writer could not restructure the goal into a
     * valid task ladder within its bounded retry budget — the CLEAN PRE-NAVIGATION
     * REFUSAL. Nothing navigated, nothing was gated, no rows exist.
     */
    LADDER_REFUSED,
}

/**
 * The exit block of a GOAL_ACHIEVED run (vision 04 §3 v1.2.0; doc 05 v1.4.0 — the run
 * report's `exit` counterpart of the terminal row's provenance). Null for every other
 * terminal (nothing exited).
 */
data class ExitSummary(
    /** HIGH | SUSTAINED | OWNER_CONFIRM. */
    val path: String,
    /** WEAK | STRONG. */
    val guardLevel: String,
    /** The anchored noul of the accepting check. */
    val noul: Double,
    /** Finish checks the policy evaluated before accepting. */
    val finishChecks: Int,
    /** Whether the completion card was shown in this task (max one). */
    val completionCardShown: Boolean,
    /** CONFIRM_DONE | KEEP_GOING | TIMEOUT, when the card was shown. */
    val completionCardResolution: String?,
)

/** One task run's durable summary (the harness exports it as the run report). */
data class RunResult(
    val goalId: String?,
    val goal: String,
    val taskId: String,
    val triage: String,
    /**
     * The triage class serial name (vision 04 §1; Phase 8D run-report field), null
     * when triage did not resolve to a task class (impossible / failed pre-triage).
     */
    val triageClass: String? = null,
    /**
     * The per-class budget profile that governed this task (vision 04 §5; Phase 8D
     * run-report field), null when no profile was selected.
     */
    val budgetProfile: String? = null,
    val terminal: TerminalState,
    val terminalDetail: String,
    val steps: Int,
    val rowsLogged: Int,
    /** Recovery meta-decisions with no page action (the rephrase stub) — run-report only. */
    val metaDecisions: Int,
    /** steps - rowsLogged - metaDecisions; the "zero log gaps" invariant (Gate 6.2
     *  asserts 0): every NAVIGATION decision is logged exactly once. The rephrase
     *  recovery stub is a goal-text meta-decision (no page action, no row) and is
     *  excluded by the formula via [metaDecisions] so the invariant stays meaningful. */
    val logGaps: Int,
    val gateEvents: Int,
    val spendMicroUsd: Long,
    val elapsedMs: Long,
    val softWarnings: List<String>,
    val stepLog: List<String>,
    val partialResult: String,
    /** Exit provenance (vision 04 §3 v1.2.0): null unless the terminal is GOAL_ACHIEVED. */
    val exit: ExitSummary? = null,
    /**
     * The action-multi tracker's final counters (Phase 08G, vision 04 §1): the run
     * report's `targets` block on action-multi runs; null for every other class.
     */
    val targets: TargetTracker.Summary? = null,
    /**
     * The triage-time plan proposal (Phase 08G, vision 04 §1): provenance only (ART-2);
     * null on the deterministic default path (no plan until the Phase-10 writer).
     */
    val plan: ProposedPlan? = null,
    /**
     * Phase 10 (vision 04 §1 v1.4.0): the ACTIVE ladder's executed state at the
     * terminal — version/generation provenance, the summary, and per-subtask
     * done/active flags. Null on every non-ladder path (never ambiguous).
     */
    val ladder: LadderRunReport? = null,
    /**
     * Phase 10: the extracted answer (vision 04 §6) — content, not instructions (doc 06
     * §4); rides the run report until the Phase-11 result screen consumes it. Null when
     * no extractor was wired or extraction failed (the goal can still be GOAL_ACHIEVED).
     */
    val answer: ExtractedAnswer? = null,
    /**
     * Phase 10 (vision 04 §5 v1.4.0): the SEPARATE per-task writer budget's total
     * (integer micro-dollars, ART-5) — restructure + re-plans + extraction. beside
     * [spendMicroUsd], the decision spend; the two caps never mix.
     */
    val writerSpendMicroUsd: Long = 0,
)

/**
 * The stage-2 outcome of one live slate (Phase 10C extraction: the loop's `run` body stays
 * under the JVM method-size limit — top-level so it is a real type, not a local one).
 */
sealed interface Stage2Outcome {
    /** A usable argmax target: the candidate, the per-candidate expected levels, the served view. */
    data class Target(
        val candidate: Candidate,
        val scores: Map<String, Double>,
        val viewText: String,
    ) : Stage2Outcome

    /** The slate gave no actionable target (the resolution's reason, always present here). */
    data class NoGoodTarget(val reason: String)

 : Stage2Outcome
}

/**
 * One intent-dispatch outcome (Phase 10C extraction: the loop's `run` body stays under the
 * JVM method-size limit). [FallThrough] = a target-bearing intent continues into the gate.
 */
sealed interface IntentDispatch {
    data class Terminal(val result: RunResult) : IntentDispatch
    data object Consumed : IntentDispatch
    data object FallThrough : IntentDispatch
}
