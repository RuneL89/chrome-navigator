package navigator.core.fixtures

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Strict golden-fixture loaders (PHASE_00 §2.3). Unknown or missing fields fail
 * loudly — a malformed fixture must be a test failure, never a silent default.
 * Golden fixtures are immutable once committed (vision 07 §2).
 */
object FixtureLoaders {
    /** Strict by default: unknown keys rejected, no lenient parsing, no coercion. */
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Parses and validates an a11y-dump fixture from JSON text. */
    fun loadA11yDump(text: String): A11yDump = parse(text) { json.decodeFromString<A11yDump>(it) }

    /** Parses and validates an a11y-dump fixture from a file. */
    fun loadA11yDumpFile(path: Path): A11yDump = loadA11yDump(read(path))

    /** Parses and validates a wire-transcript fixture from JSON text. */
    fun loadWireTranscript(text: String): WireTranscript =
        parse(text) { json.decodeFromString<WireTranscript>(it) }

    /** Parses and validates a wire-transcript fixture from a file. */
    fun loadWireTranscriptFile(path: Path): WireTranscript = loadWireTranscript(read(path))

    private fun read(path: Path): String = try {
        Files.readString(path)
    } catch (e: java.io.IOException) {
        throw FixtureValidationException("cannot read fixture file $path", e)
    }

    private inline fun <T> parse(text: String, decode: (String) -> T): T = try {
        decode(text)
    } catch (e: FixtureValidationException) {
        throw e
    } catch (e: SerializationException) {
        // NB: SerializationException extends IllegalArgumentException — keep this
        // branch ahead of the generic IllegalArgumentException catch.
        throw FixtureValidationException("fixture failed strict schema validation: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw FixtureValidationException("fixture failed validation: ${e.message}", e)
    } catch (e: Exception) {
        throw FixtureValidationException("fixture parsing failed: ${e.message}", e)
    }
}
