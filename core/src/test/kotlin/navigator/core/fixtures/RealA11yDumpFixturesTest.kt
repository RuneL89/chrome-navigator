package navigator.core.fixtures

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Gate 1.3 validator: every a11y-dump fixture in `fixtures/a11y-dumps/` — the Phase-0
 * seed AND every Phase-1 real capture — must load under the strict loader. A malformed
 * fixture is a test failure, never a silent default (core DOX; vision 07 §2: fixtures
 * are committed verbatim and immutable).
 */
class RealA11yDumpFixturesTest {

    private val dumpsDir: Path = Path.of(
        RealA11yDumpFixturesTest::class.java.getResource("/fixtures/a11y-dumps")!!.toURI(),
    )

    @Test
    fun `every a11y dump fixture loads strictly`() {
        val files = dumpsDir.toFile().listFiles { f -> f.isFile && f.extension == "json" }!!
            .map { it.name }.sorted()
        assertTrue(
            files.size >= 7,
            "expected the seed plus >=6 Phase-1 real captures, found: $files",
        )
        val loaded = files.map { name ->
            FixtureLoaders.loadA11yDumpFile(dumpsDir.resolve(name)).also { dump ->
                // capture metadata must be filled (Gate 1.3 pass criteria)
                assertTrue(dump.site.isNotBlank(), "$name: empty site")
                assertTrue(dump.url.isNotBlank(), "$name: empty url")
                assertTrue(dump.title.isNotBlank(), "$name: empty title")
                assertTrue(dump.nodes.isNotEmpty(), "$name: no nodes")
            }
        }
        println("validated ${loaded.size} a11y-dump fixtures: $files")
    }
}
