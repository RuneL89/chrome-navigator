package navigator.app.harness.webview.dom

/**
 * V2 S1 — the DOM snapshot script (FR-11, AC-S1.1): injected via `evaluateJavascript`,
 * it walks the interactive/visible nodes and returns ONE JSON object
 * `{snapshotVersion, page:{url,title}, elements:[{id,role,label,valueState,required,bounds:{x,y,w,h},tag,type}], text}`.
 *
 * Contract notes (from the P0 prototype round):
 * - The script RETURNS AN OBJECT (not a stringified string) — `evaluateJavascript` then
 *   hands the callback the object's JSON text, which the Kotlin side parses once.
 * - Ids are the array index of [DomJs]'s shared walk — snapshot-local by construction,
 *   dispatchable by the action scripts through the same walk (see [DomJs.nvFind]-shaped
 *   identity guards).
 * - The walk is breadth-capped at [MAX_ELEMENTS] and the text excerpt at [DomSnapshot.TEXT_CAP]
 *   so a 500+-node page stays inside the ≤300 ms budget measured in L3.
 * - Invisible nodes (zero-area rects, hidden attributes) are skipped — the writer never
 *   proposes on what no user could see.
 */
object ExtractJs {
    const val MAX_ELEMENTS = 500

    /** Version tag riding every snapshot (evidence provenance). */
    const val SNAPSHOT_VERSION = DomJs.SNAPSHOT_VERSION

    val snapshot: String = """
    (function() {
      try {
        ${DomJs.core}
        var cap = ${MAX_ELEMENTS};
        var visible = nvVisible();
        var out = [];
        for (var i = 0; i < visible.length && out.length < cap; i++) {
          var el = visible[i];
          var r = el.getBoundingClientRect();
          var tag = (el.tagName || '').toLowerCase();
          var type = (el.getAttribute('type') || '').toLowerCase();
          var role = nvRole(el, tag, type);
          var label = nvLabel(el, tag);
          out.push({
            id: out.length,
            role: role,
            label: label || '',
            valueState: nvValueState(el, tag, type),
            required: el.hasAttribute('required') || el.getAttribute('aria-required') === 'true',
            bounds: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
            tag: tag,
            type: type
          });
        }
        var text = (document.body && document.body.innerText || '').replace(/\s+/g, ' ').trim();
        if (text.length > ${DomSnapshot.TEXT_CAP}) text = text.substring(0, ${DomSnapshot.TEXT_CAP});
        return {
          snapshotVersion: '$SNAPSHOT_VERSION',
          page: { url: location.href, title: document.title || '' },
          elements: out,
          text: text
        };
      } catch (e) {
        return { snapshotVersion: '$SNAPSHOT_VERSION', error: String(e && e.message || e), page: { url: location.href, title: '' }, elements: [], text: '' };
      }
    })()
    """.trimIndent()
}
