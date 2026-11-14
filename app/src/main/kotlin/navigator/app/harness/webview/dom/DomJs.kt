package navigator.app.harness.webview.dom

/**
 * V2 S1 — the shared JS core for every injected script. Every `evaluateJavascript` call
 * is standalone, so the snapshot walk (selector, visibility filters, role/label/state
 * functions) lives here ONCE and both the snapshot script and the action scripts embed
 * it — the snapshot's array index and the action script's lookup index are then the SAME
 * walk, which is what makes a snapshot-local id dispatchable at all. Each action script
 * verifies the element's identity (role/tag/type/label) before dispatching and reports
 * `mismatch` instead of acting blind when the DOM shifted under the id.
 */
object DomJs {
    const val SNAPSHOT_VERSION = "dom-snapshot-v1"

    /** The interactive-element selector — identical in snapshot and action walks. */
    const val SELECTOR: String =
        "'a, button, input, select, textarea, summary, " +
            "[role=\"button\"], [role=\"link\"], [role=\"checkbox\"], [role=\"radio\"], " +
            "[role=\"searchbox\"], [role=\"combobox\"], [role=\"tab\"], [role=\"menuitem\"], " +
            "[onclick], [contenteditable=\"true\"], [contenteditable=\"\"]'"

    /**
     * The core: `nvVisible()` returns the filtered interactive nodes in snapshot order;
     * `nvRole/nvLabel/nvValueState` compute the snapshot's fields. Functions are
     * name-spaced `nv*` so concurrent injections cannot collide with page scripts.
     */
    val core: String = """
    var NV_SELECTOR = $SELECTOR;
    function nvVisible() {
      var nodes = document.querySelectorAll(NV_SELECTOR);
      var visible = [];
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var r = el.getBoundingClientRect();
        if (!r || r.width < 1 || r.height < 1) continue;
        var style = window.getComputedStyle(el);
        if (!style || style.visibility === 'hidden' || style.display === 'none') continue;
        if (el.getAttribute('aria-hidden') === 'true' || el.hasAttribute('disabled')) continue;
        visible.push(el);
      }
      return visible;
    }
    function nvRole(el, tag, type) {
      var role = el.getAttribute('role');
      if (role) return role;
      if (tag === 'a') return 'link';
      if (tag === 'button' || tag === 'summary') return 'button';
      if (tag === 'select') return 'combobox';
      if (tag === 'textarea') return 'textbox';
      if (tag === 'input') {
        if (type === 'checkbox') return 'checkbox';
        if (type === 'radio') return 'radio';
        if (type === 'button' || type === 'submit' || type === 'reset') return 'button';
        if (type === 'search') return 'search';
        return 'textbox';
      }
      if (el.hasAttribute('onclick') || el.isContentEditable) return 'button';
      return 'generic';
    }
    function nvLabel(el, tag) {
      var l = el.getAttribute('aria-label');
      if (l && l.trim()) return l.trim().substring(0, 200);
      var labelledby = el.getAttribute('aria-labelledby');
      if (labelledby) {
        var owner = document.getElementById(labelledby);
        if (owner && owner.innerText && owner.innerText.trim()) return owner.innerText.trim().substring(0, 200);
      }
      if (tag === 'input' || tag === 'textarea' || tag === 'select') {
        var id = el.getAttribute('id');
        if (id) {
          var labelEl = document.querySelector('label[for="' + (id.replace(/\"/g, '')) + '"]');
          if (labelEl && labelEl.innerText && labelEl.innerText.trim()) return labelEl.innerText.trim().substring(0, 200);
        }
        var ph = el.getAttribute('placeholder');
        if (ph && ph.trim()) return ph.trim().substring(0, 200);
        var nameAttr = el.getAttribute('name');
        if (nameAttr && nameAttr.trim()) return nameAttr.trim().substring(0, 200);
      }
      if (el.innerText && el.innerText.trim()) return el.innerText.trim().replace(/\s+/g, ' ').substring(0, 200);
      return '';
    }
    function nvValueState(el, tag, type) {
      if (tag === 'input' && (type === 'checkbox' || type === 'radio')) return el.checked ? 'checked' : 'unchecked';
      if (tag === 'select') {
        var opt = el.selectedOptions && el.selectedOptions[0];
        return opt ? (opt.text || '').trim() : '';
      }
      var v = el.value;
      if (typeof v === 'string' && v.length) return v.substring(0, 200);
      return '';
    }
    /**
     * The identity-guarded lookup: the element at snapshot index [idx] of the SAME walk,
     * only when role/tag/type/label still match what the snapshot recorded. Returns
     * {ok, el} on match, {ok:false, mismatch:true, got:{...}} when the DOM shifted,
     * {ok:false, missing:true} when the index is out of range.
     */
    function nvFind(idx, role, tag, type, label) {
      var visible = nvVisible();
      if (idx < 0 || idx >= visible.length) return { ok: false, missing: true };
      var el = visible[idx];
      var t = (el.tagName || '').toLowerCase();
      var ty = (el.getAttribute('type') || '').toLowerCase();
      var r = nvRole(el, t, ty);
      var l = nvLabel(el, t);
      var match = t === tag && ty === type && r === role &&
        (l || '').trim().replace(/\s+/g, ' ').toLowerCase() === (label || '').trim().replace(/\s+/g, ' ').toLowerCase();
      if (!match) {
        return { ok: false, mismatch: true, got: { role: r, label: l, tag: t, type: ty } };
      }
      return { ok: true, el: el };
    }
    """.trimIndent()

    /**
     * Locates by id WITHOUT the label check (the identity the writer proposed rides in
     * full; this variant is for scroll, where labels on containers are noisy).
     */
    fun findWithoutLabel(idx: Int): String = """
    (function() {
      try {
        ${core}
        var visible = nvVisible();
        if ($idx < 0 || $idx >= visible.length) return { found: false, missing: true };
        var el = visible[$idx];
        return { found: true, marker: true };
      } catch (e) {
        return { found: false, error: String(e && e.message || e) };
      }
    })()
    """.trimIndent()
}
