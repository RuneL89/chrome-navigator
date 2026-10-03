package navigator.core.testsupport

import navigator.core.enumerate.ObservedNode
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump
import navigator.core.fixtures.FixtureLoaders
import java.nio.file.Path

/**
 * Test-support access to the golden a11y-dump fixtures (Phase-0 seed + the six Phase-1
 * real captures) and helpers for synthesizing observed trees. Fixtures are immutable
 * (vision 07 §2) — read-only here.
 */
object TestFixtures {

    val dumpsDir: Path = Path.of(
        TestFixtures::class.java.getResource("/fixtures/a11y-dumps")!!.toURI(),
    )

    /** Every committed a11y-dump fixture, sorted by name: (name, dump) pairs. */
    fun loadAll(): List<Pair<String, A11yDump>> =
        dumpsDir.toFile().listFiles { f -> f.isFile && f.extension == "json" }!!
            .map { it.name }.sorted()
            .map { name -> name to FixtureLoaders.loadA11yDumpFile(dumpsDir.resolve(name)) }

    /** A dump's nodes mapped into the enumerator's input type (source order = read order). */
    fun observed(name: String): List<ObservedNode> =
        loadAll().first { it.first == name }.second.observedNodes()

    /** Convenience builder for synthetic observed trees (default: a clickable nav link). */
    fun node(
        id: String,
        top: Int = 0,
        left: Int = 0,
        width: Int = 100,
        height: Int = 50,
        clickable: Boolean = true,
        editable: Boolean = false,
        scrollable: Boolean = false,
        text: String = "",
        desc: String = "",
        className: String = "android.view.View",
        sourceOrder: Int = 0,
    ): ObservedNode = ObservedNode(
        id = id,
        text = text,
        desc = desc,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        left = left,
        top = top,
        right = left + width,
        bottom = top + height,
        className = className,
        sourceOrder = sourceOrder,
    )
}
