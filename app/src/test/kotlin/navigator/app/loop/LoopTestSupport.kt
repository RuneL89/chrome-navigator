package navigator.app.loop

import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
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

    fun failNextCall(): Unit {
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

// -- observation builders -------------------------------------------------------------

/** One node spec for [obs]. */
data class N(
    val text: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val button: Boolean = false,
)

/**
 * Builds a [StepObservation] from node specs. Node ids are `n<index>` in spec order;
 * bounds are deterministic (row i sits at y=i*100) so candidate ranking is stable.
 */
fun obs(
    url: String,
    vararg nodes: N,
    pageNodes: Int? = null,
    title: String = "",
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
        )
    }
    val lines = observed.map { node ->
        val kinds = listOfNotNull(
            "tap".takeIf { node.clickable },
            "edit".takeIf { node.editable },
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
) {
    val clock = FakeClock()
    val sink = CollectingLogSink()
    val executor = FakeExecutor(journal)
    private val stubCallLog = mutableListOf<String>()

    val engine = StubEngine(StubScript(script), stubCallLog)

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
