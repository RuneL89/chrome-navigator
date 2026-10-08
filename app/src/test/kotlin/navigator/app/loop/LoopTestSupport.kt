package navigator.app.loop

import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.enumerate.ObservedNode
import navigator.core.log.DecisionLogRow
import navigator.core.log.ExitCheckRow

/**
 * Shared fakes for the Gate 6.1 loop tests — Robolectric-free: every Android edge of the
 * loop ([LoopObserver], [ActionExecutor], [GatePresenter], [DecisionLogSink], [Clock])
 * is an interface, so the whole step order runs on the plain JVM.
 */

/** Mutable test clock. */
class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** Ordered call journal shared by the fakes (step-order assertions). */
class Journal {
    val entries = mutableListOf<String>()

    fun add(entry: String) = entries.add(entry)

    fun firstIndexOf(entry: String): Int = entries.indexOfFirst { it == entry }

    fun countOf(entry: String): Int = entries.count { it == entry }
}

/** Pops queued observations in order, repeating the last one when exhausted. */
class FakeObserver(
    private val observations: List<StepObservation>,
    private val journal: Journal? = null,
) : LoopObserver {
    private var index = 0

    override suspend fun observe(): StepObservation {
        journal?.add("observe")
        return if (index < observations.size) observations[index++] else observations.last()
    }
}

/** Records every gesture; always succeeds unless told to fail once. */
class FakeExecutor(private val journal: Journal) : ActionExecutor {
    val calls = mutableListOf<String>()
    private var failNext = false

    /** The terminal cause the armed text-entry failure reports (RC1); null = silent. */
    private var pendingTextCause: TextEntryFailure? = null

    fun failNextCall(): Unit {
        failNext = true
    }

    /** Arms the NEXT text entry to FAIL, reporting [cause] through the cause sink. */
    fun failNextTextWith(cause: TextEntryFailure) {
        pendingTextCause = cause
        failNext = true
    }

    private fun record(call: String): Boolean {
        calls.add(call)
        journal.add(call)
        val ok = !failNext
        failNext = false
        return ok
    }

    override fun tap(x: Int, y: Int): Boolean = record("tap($x,$y)")

    override fun scroll(x: Int, y: Int, down: Boolean): Boolean = record("scroll($x,$y,$down)")

    override fun back(): Boolean = record("back")

    override fun enterText(x: Int, y: Int, text: String): Boolean = record("text($x,$y,\"$text\")")

    override fun enterText(x: Int, y: Int, text: String, cause: (TextEntryFailure) -> Unit): Boolean {
        val ok = record("text($x,$y,\"$text\")")
        val reported = pendingTextCause
        pendingTextCause = null
        if (!ok && reported != null) cause(reported)
        return ok
    }
}

/** Collects the rows the loop writes (the Room sink's test double). */
class CollectingLogSink : DecisionLogSink {
    val rows = mutableListOf<DecisionLogRow>()

    /** The exit-check rows (vision 05 §2 amendment): one per finish check. */
    val exitChecks = mutableListOf<ExitCheckRow>()

    override suspend fun write(row: DecisionLogRow) {
        require(rows.none { it.rowId == row.rowId }) { "duplicate rowId ${row.rowId}" }
        rows.add(row)
    }

    override suspend fun writeExitCheck(row: ExitCheckRow) {
        require(exitChecks.none { it.rowId == row.rowId }) { "duplicate exit-check rowId ${row.rowId}" }
        require(exitChecks.none { it.taskId == row.taskId && it.stepIndex == row.stepIndex }) {
            "duplicate exit-check key (${row.taskId}, step ${row.stepIndex}) - exactly one per finish check"
        }
        exitChecks.add(row)
    }
}

/**
 * Wraps a [DecisionEngine] and records every noul question it is ASKED (name +
 * proposition) before delegating — the additive-consult masking pins assert what the
 * engine would have RECEIVED, not what the loop built (PREUAT2-4 (A): doc 06 §2a, the
 * secret is never sent to any engine). $0: the delegate is the scripted stub.
 */
class PropositionRecordingEngine(
    private val delegate: DecisionEngine,
    val judgedQuestions: MutableList<NoulQuestion> = mutableListOf(),
) : DecisionEngine {
    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
        delegate.choose(state, question)

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        judgedQuestions.add(question)
        return delegate.judge(state, question)
    }

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        delegate.score(state, question)
}

// -- observation builders -------------------------------------------------------------

/** One node spec for [obs]. */
data class N(
    val text: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val button: Boolean = false,
    /** The reader-captured secret-field flag (2026-10-08 pre-UAT2 H8-gap fix). */
    val password: Boolean = false,
    /** The browser-chrome marker (2026-10-08 pre-UAT2 RC2): the omnibox/URL-bar subtree. */
    val browserChrome: Boolean = false,
)

/**
 * Builds a [StepObservation] from node specs. Node ids are `n<index>` in spec order;
 * bounds are deterministic (row i sits at y=i*100) so candidate ranking is stable.
 * [windowPackage] defaults to an allowlisted browser — the LIVE shape (every real loop
 * observation is read from an allowlisted browser root); readiness-focused tests pass
 * the wrong-package values explicitly.
 */
fun obs(
    url: String,
    vararg nodes: N,
    pageNodes: Int? = null,
    title: String = "",
    windowPackage: String = "com.android.chrome",
): StepObservation {
    val observed = nodes.mapIndexed { index, spec ->
        ObservedNode(
            id = "n$index",
            text = spec.text,
            desc = "",
            clickable = spec.clickable,
            editable = spec.editable,
            scrollable = spec.scrollable,
            left = 0,
            top = index * 100,
            right = 100,
            bottom = index * 100 + 40,
            className = if (spec.button) "android.widget.Button" else "android.view.View",
            sourceOrder = index,
            isPassword = spec.password,
            browserChrome = spec.browserChrome,
        )
    }
    val lines = observed.map { node ->
        val kinds = listOfNotNull(
            "tap".takeIf { node.clickable },
            "edit".takeIf { node.editable },
            "password".takeIf { node.isPassword },
            "scroll".takeIf { node.scrollable },
        ).joinToString(" ")
        "[n${node.sourceOrder}] \"${node.text}\"${if (kinds.isEmpty()) "" else " $kinds"} " +
            "(${node.centerX},${node.centerY})"
    }
    return StepObservation(
        url = url,
        title = title,
        lines = lines,
        nodes = observed,
        pageNodeCount = pageNodes,
        windowPackage = windowPackage,
    )
}

// -- loop wiring -------------------------------------------------------------------------

/**
 * Assembles the loop with fakes; exposes everything a Gate 6.1 scenario asserts on.
 * [completionCardPresenter] defaults to the auto keep-going mode (fail-safe: an
 * unattended completion card never exits — vision 04 §3 Path C); the Phase-8J card
 * scenarios inject [ScriptedCompletionCardPresenter]s.
 */
class LoopFixture(
    script: List<ScriptedAnswer>,
    observations: List<StepObservation>,
    presenter: GatePresenter,
    config: NavigationLoop.Config = NavigationLoop.Config(settleMs = 0),
    spendMeter: SpendMeter = SpendMeter(),
    /** Wall-clock ms burned per observation (drives the time-budget tests). */
    private val clockAdvancePerObserve: Long = 0,
    /** External journal so presenter lambdas can share the fakes' timeline. */
    val journal: Journal = Journal(),
    completionCardPresenter: CompletionCardPresenter = AutoKeepGoingCompletionPresenter,
    /** Phase-8D cold-start seams (vision 04 §1); null skips the pre-step. */
    val browserProbe: BrowserProbe? = null,
    val browserLauncher: BrowserLauncher? = null,
    /** The scripted start URL handed to the loop's cold-start derivation. */
    val startUrl: String? = null,
    /** Phase-08G plan-proposal seam (vision 04 §1); defaults to NO plan. */
    val planSource: PlanSource = PlanSource.None,
    /**
     * Optional engine decorator (the additive-consult masking pins wrap the stub to
     * capture the question the engine RECEIVED); the identity default leaves every
     * existing fixture byte-identical.
     */
    engineDecorator: (DecisionEngine) -> DecisionEngine = { it },
) {
    val clock = FakeClock()
    val sink = CollectingLogSink()
    val executor = FakeExecutor(journal)
    private val stubCallLog = mutableListOf<String>()

    val engine = engineDecorator(StubEngine(StubScript(script), stubCallLog))

    val engineCalls: List<String> get() = stubCallLog

    val loop = NavigationLoop(
        engine = engine,
        observer = object : LoopObserver {
            private var index = 0
            override suspend fun observe(): StepObservation {
                journal.add("observe")
                if (clockAdvancePerObserve > 0) clock.advance(clockAdvancePerObserve)
                return if (index < observations.size) observations[index++] else observations.last()
            }
        },
        executor = executor,
        gatePresenter = presenter,
        completionCardPresenter = completionCardPresenter,
        logSink = sink,
        spendMeter = spendMeter,
        config = config,
        browserProbe = browserProbe,
        browserLauncher = browserLauncher,
        planSource = planSource,
    )
}

fun choose(question: String, chosen: String): ScriptedAnswer =
    ScriptedAnswer(question = question, chosen = chosen)

fun chooseByLabel(question: String, labelContains: String): ScriptedAnswer =
    ScriptedAnswer(question = question, labelContains = labelContains)

fun noul(pTrue: Double): ScriptedAnswer =
    ScriptedAnswer(question = "goal-achieved", pTrue = pTrue)

fun scriptOf(vararg answers: ScriptedAnswer): List<ScriptedAnswer> = answers.toList()
