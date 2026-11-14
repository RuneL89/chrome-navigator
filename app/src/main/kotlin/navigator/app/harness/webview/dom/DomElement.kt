package navigator.app.harness.webview.dom

import kotlinx.serialization.Serializable

/**
 * V2 S1 — one interactive element of a DOM snapshot (FR-11): the CLEAN element list the
 * writer reasons over. `id` is SNAPSHOT-LOCAL (the array index at extraction time) — it
 * is never reused across snapshots; stable targeting re-queries semantically (FR-12,
 * [ElementResolver]). Coordinates are VIEWPORT CSS pixels at extraction time.
 */
@Serializable
data class DomElement(
    val id: Int,
    val role: String,
    val label: String,
    /** Value/state at extraction: "" when empty, the text content for text fields, "checked"/"unchecked", the selected option. */
    val valueState: String,
    val required: Boolean,
    val bounds: Bounds,
    val tag: String,
    val type: String,
)

/** Viewport CSS-px rectangle of the element at extraction time. */
@Serializable
data class Bounds(val x: Int, val y: Int, val w: Int, val h: Int) {
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
    val visible: Boolean get() = w > 0 && h > 0
}

/** The page-level half of a snapshot (FR-09 evidence + the writer's page text). */
@Serializable
data class PageMeta(
    val url: String,
    val title: String,
)

/**
 * A full DOM snapshot: page meta, the interactive element list, and a bounded
 * visible-text excerpt for the writer. Text is capped at [TEXT_CAP] chars — the writer
 * reasons over an excerpt, never the whole page.
 */
@Serializable
data class DomSnapshot(
    val page: PageMeta,
    val elements: List<DomElement>,
    val text: String,
) {
    companion object {
        const val TEXT_CAP = 4000
    }

    fun elementById(id: Int): DomElement? = elements.firstOrNull { it.id == id }
}

/** The action vocabulary the lane executes (FR-13/14/15/17). SUBMIT is refused (FR-16). */
enum class DomAction {
    CLICK,
    TEXT,
    SELECT,
    CHECK,
    UNCHECK,
    SCROLL_TO,
    ;

    companion object {
        fun of(raw: String): DomAction? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
