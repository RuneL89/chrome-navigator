package navigator.app.harness.webview.dom

/**
 * V2 S1 — the DOM action layer: JS builders for the lane's action vocabulary plus the
 * pure decision logic around them.
 *
 * Every builder embeds [DomJs.core] and locates its element through the SAME walk the
 * snapshot used, with the identity guard (`nvFind`): the element must still carry the
 * role/tag/type/label the snapshot recorded, else the script reports `mismatch` and the
 * lane re-snapshots instead of dispatching blind.
 *
 * Click law (FR-13, AC-S1.3): the PRIMARY click is `element.click()` — one call, no
 * coordinate math. The ESCALATION is a TRUSTED touch dispatch (`WebView.dispatchTouchEvent`
 * at the element's center, the P0-prototype-proven trusted path), used ONLY when the
 * control registers down-events and the plain click produced no verified effect.
 * JS-synthesized pointer events stay out of the escalation path: the prototype measured
 * them untrusted.
 *
 * Text law (FR-14/15, AC-S1.4): text entry goes through the NATIVE value setter plus
 * `input`/`change` dispatch — the React/Vue-safe shape — and every write is verified by
 * READ-BACK of the framework's own state (the property value / checked / selected
 * option), returned by the SAME script.
 *
 * Fill-only law (FR-16): there is deliberately NO submit builder — the executor refuses
 * submit-classified actions before dispatch, the owner's tap is the only submit.
 */
object DomActions {

    // -- click ----------------------------------------------------------------------------

    /**
     * The plain click (FR-13): locate through the identity-guarded walk, `click()`, and
     * report what HAPPENED — never a success claim. `hasDownHandler` is the escalation
     * signal for the planner.
     */
    fun clickJs(id: Int, role: String, tag: String, type: String, label: String): String = """
    (function() {
      try {
        ${DomJs.core}
        var hit = nvFind($id, "$role", "$tag", "$type", ${jsParam(label)});
        if (!hit.ok) return hit;
        var el = hit.el;
        el.click();
        var down = typeof el.onpointerdown === 'function' || typeof el.onmousedown === 'function' ||
          el.hasAttribute('onmousedown') || el.hasAttribute('onpointerdown');
        return { ok: true, clicked: true, hasDownHandler: down };
      } catch (e) {
        return { ok: false, clicked: false, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()

    /**
     * The click-path decision (pure, L1): plain JS click first; the trusted-touch
     * escalation ONLY for down-driven controls whose plain click reported no verified
     * effect. The caller passes the POSTCONDITION's word, not the dispatch's.
     */
    sealed interface ClickPath {
        /** element.click() was the right (or the only) mechanism. */
        data object JsClick : ClickPath

        /** Escalate: trusted `WebView.dispatchTouchEvent` at the element center (CSS px). */
        data class TrustedTouch(val cssX: Int, val cssY: Int) : ClickPath
    }

    fun planClick(element: DomElement, effectVerified: Boolean, hasDownHandler: Boolean): ClickPath =
        if (effectVerified) {
            ClickPath.JsClick
        } else if (hasDownHandler) {
            ClickPath.TrustedTouch(element.bounds.centerX, element.bounds.centerY)
        } else {
            ClickPath.JsClick
        }

    // -- text entry (React/Vue-safe) --------------------------------------------------------

    /**
     * Native-setter text entry (FR-14, AC-S1.4): `Object.getOwnPropertyDescriptor(
     * HTMLInputElement.prototype,'value').set.call(el, text)` — bypasses React's value
     * tracking — then `input` + `change` events, then the READ-BACK (the framework's own
     * state) rides in the same result so no second round trip is needed. Textarea uses
     * HTMLTextAreaElement's descriptor.
     */
    fun textJs(id: Int, role: String, tag: String, type: String, label: String, text: String): String = """
    (function() {
      try {
        ${DomJs.core}
        var hit = nvFind($id, "$role", "$tag", "$type", ${jsParam(label)});
        if (!hit.ok) return hit;
        var el = hit.el;
        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
        var requested = ${jsParam(text)};
        if (desc && desc.set) { desc.set.call(el, requested); } else { el.value = requested; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { ok: true, requested: requested, value: el.value };
      } catch (e) {
        return { ok: false, value: null, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()

    /** Select by visible option text (FR-15): native set + input/change + read-back. */
    fun selectJs(id: Int, role: String, tag: String, type: String, label: String, optionText: String): String = """
    (function() {
      try {
        ${DomJs.core}
        var hit = nvFind($id, "$role", "$tag", "$type", ${jsParam(label)});
        if (!hit.ok) return hit;
        var el = hit.el;
        var requested = ${jsParam(optionText)};
        var target = null;
        for (var i = 0; i < el.options.length; i++) {
          if ((el.options[i].text || '').trim() === requested) { target = i; break; }
        }
        if (target === null) {
          for (var j = 0; j < el.options.length; j++) {
            if ((el.options[j].text || '').trim().indexOf(requested) !== -1) { target = j; break; }
          }
        }
        if (target === null) return { ok: false, selected: null, error: 'option not found' };
        var desc = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'selectedIndex');
        if (desc && desc.set) { desc.set.call(el, target); } else { el.selectedIndex = target; }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        var opt = el.selectedOptions && el.selectedOptions[0];
        return { ok: true, requested: requested, selected: opt ? (opt.text || '').trim() : null };
      } catch (e) {
        return { ok: false, selected: null, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()

    /** Checkbox/radio flip (FR-15): checked set + input/change + read-back. */
    fun checkJs(id: Int, role: String, tag: String, type: String, label: String, checked: Boolean): String = """
    (function() {
      try {
        ${DomJs.core}
        var hit = nvFind($id, "$role", "$tag", "$type", ${jsParam(label)});
        if (!hit.ok) return hit;
        var el = hit.el;
        el.checked = $checked;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { ok: true, requested: $checked, checked: el.checked === true };
      } catch (e) {
        return { ok: false, checked: null, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()

    /**
     * Scroll the element into view (FR-17): locates WITHOUT the label check (container
     * labels are noisy) and returns the new scroll offset so the caller verifies the
     * viewport actually moved before counting progress.
     */
    fun scrollJs(id: Int, role: String, tag: String, type: String, label: String): String = """
    (function() {
      try {
        ${DomJs.core}
        var hit = nvFind($id, "$role", "$tag", "$type", ${jsParam(label)});
        if (!hit.ok) return hit;
        var el = hit.el;
        el.scrollIntoView({ block: 'center', behavior: 'instant' });
        return { ok: true, scrollY: Math.round(window.scrollY), scrollX: Math.round(window.scrollX) };
      } catch (e) {
        return { ok: false, scrollY: null, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()

    // -- read-back verification (pure, L1) ---------------------------------------------------

    /**
     * The text-entry verdict (AC-S1.4): the write verified only when the page's own
     * read-back equals the requested text — the framework's state, not the dispatch.
     */
    fun verifyText(requested: String, readBack: String?): Boolean = readBack == requested

    /** The check verdict: the read-back state equals the requested state. */
    fun verifyCheck(requested: Boolean, readBack: Boolean?): Boolean = readBack == requested

    /** The select verdict: the selected option text equals (exact) the requested text. */
    fun verifySelect(requested: String, readBack: String?): Boolean = readBack == requested.trim()

    /**
     * A click's POSTCONDITION verdict (pure, L1) — only evidence counts (FR-35): the URL
     * navigated, or the page text changed beyond the trimmed-noise threshold, or the
     * clicked element's own state flipped (checked/selected/value). A dispatch report of
     * `clicked: true` alone is NEVER a verdict.
     */
    fun verifyClickEffect(
        urlBefore: String,
        urlAfter: String,
        textBefore: String,
        textAfter: String,
        elementStateBefore: String,
        elementStateAfter: String,
    ): Boolean {
        if (urlAfter != urlBefore) return true
        if (textAfter.trim() != textBefore.trim()) return true
        if (elementStateAfter != elementStateBefore) return true
        return false
    }

    /** Minimal JSON string escaping for values embedded in generated JS. */
    fun jsonEscape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                else -> append(c)
            }
        }
    }

    /** A string embedded into generated JS as a JSON literal. */
    private fun jsParam(text: String): String = "\"${jsonEscape(text)}\""
}
