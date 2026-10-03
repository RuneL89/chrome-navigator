package navigator.core.fixtures

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One accessibility-tree node as recorded by the observation layer (Phase 1).
 *
 * @param id stable node identifier within the dump (used by candidates and transcripts)
 * @param text visible text of the node (may be empty)
 * @param desc content description (may be empty)
 * @param clickable whether the node is clickable
 * @param editable whether the node accepts text entry
 * @param scrollable whether the node is scrollable
 * @param bounds on-screen bounds in Android a11y convention `"left,top,right,bottom"`
 *   (pixels, ints — `AccessibilityNodeInfo.getBoundsInScreen` flattening)
 * @param className Android class name of the node (e.g. `android.widget.EditText`)
 */
@Serializable
data class A11yNode(
    val id: String,
    val text: String,
    val desc: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: String,
    val className: String,
) {
    init {
        require(id.isNotBlank()) { "A11yNode.id must not be blank" }
        require(BOUNDS_FORMAT.matches(bounds)) {
            "A11yNode '$id': bounds must be \"left,top,right,bottom\" ints, was: \"$bounds\""
        }
    }

    companion object {
        private val BOUNDS_FORMAT = Regex("^-?\\d+,-?\\d+,-?\\d+,-?\\d+$")
    }
}

/**
 * Golden-fixture a11y dump (PHASE_00 §2.3): the exact schema Phase 1 records into.
 * Schema changes are a vision-level event (fixture immutability, vision 07 §2).
 *
 * @param capturedAt ISO-8601 capture timestamp with offset or `Z`
 * @param site site qualifier the dump belongs to (e.g. `google.com`)
 * @param url full URL captured (including the Chrome URL bar read)
 * @param title page title
 * @param nodes accessibility nodes in deterministic read order (ART-6)
 */
@Serializable
data class A11yDump(
    val capturedAt: String,
    val site: String,
    val url: String,
    val title: String,
    val nodes: List<A11yNode>,
) {
    init {
        require(capturedAt.isNotBlank()) { "A11yDump.capturedAt must not be blank" }
        try {
            OffsetDateTime.parse(capturedAt)
        } catch (e: DateTimeParseException) {
            throw FixtureValidationException(
                "A11yDump.capturedAt is not ISO-8601 with offset/Z: \"$capturedAt\"", e,
            )
        }
        require(site.isNotBlank()) { "A11yDump.site must not be blank" }
        require(url.isNotBlank()) { "A11yDump.url must not be blank" }
        require(nodes.isNotEmpty()) { "A11yDump '$url' must contain at least one node" }
        val ids = nodes.map { it.id }
        require(ids.size == ids.toSet().size) {
            "A11yDump '$url' has duplicate node ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
    }
}
