package navigator.app.observe

import android.content.Context
import android.content.pm.ApplicationInfo
import navigator.core.fixtures.A11yDump
import navigator.core.fixtures.A11yNode
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Debug capture harness (Phase-1 tooling): records one live observation as an
 * `A11yDump` (Phase-0 schema, navigator.core.fixtures) to app-private storage for
 * `adb pull` + commit as real golden fixtures (vision 07 §2 — fixtures are committed
 * verbatim, never edited afterwards).
 *
 * Enabled only when (a) the app is debuggable AND (b) an explicit intent drives the
 * capture (CaptureActivity's `capture` action). Never active in normal navigation.
 */
object CaptureMode {

    /** Nodes carry raw text (fixtures are ground truth; 40-char truncation is presentation). */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /**
     * Captures the service's current observation into the captures directory.
     *
     * @return the written file, or null when capture is unavailable (release build,
     *   service not connected, or unusable tree — the refusal law of vision 03 §5 keeps
     *   Phase 1 from recording empty observations).
     */
    fun capture(context: Context, site: String, url: String, title: String): File? {
        if (!isDebuggable(context)) return null
        val service = NavigatorAccessibilityService.instance ?: return null
        val observation = service.observe()
        if (observation.nodes.isEmpty()) return null

        val dump = A11yDump(
            capturedAt = java.time.OffsetDateTime.now().toString(),
            site = site,
            url = url,
            // Fixture metadata must be filled (Gate 1.3); a blank page title is recorded
            // as an explicit placeholder rather than an empty string.
            title = title.ifBlank { "(untitled)" },
            device = deviceString(context),
            nodes = observation.nodes.map { emitted ->
                val snap = emitted.snapshot
                A11yNode(
                    id = "n${emitted.index}",
                    text = snap.text,
                    desc = snap.desc,
                    clickable = snap.clickable,
                    editable = snap.editable,
                    scrollable = snap.scrollable,
                    isPassword = snap.isPassword,
                    bounds = snap.bounds.asFixtureString,
                    className = snap.className,
                    // Phase 10D Stage 3 probe (additive): the label sources, so a captured
                    // dump answers WHERE (or whether) a field's name exists in the tree.
                    hint = snap.hint,
                    context = snap.context,
                    childText = snap.children
                        .joinToString(" ") { it.text.ifBlank { it.desc } }
                        .trim()
                        .take(CHILD_TEXT_PROBE_CAP),
                )
            },
        )
        return writeDump(context, dump)
    }

    /** Existing capture files, oldest first. */
    fun captures(context: Context): List<File> =
        capturesDir(context).listFiles()?.filter { it.isFile && it.extension == "json" }
            .orEmpty().sortedBy { it.name }

    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Capture provenance (optional fixture `device` field). */
    private fun deviceString(context: Context): String = with(context) {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    }

    /** External files dir → pullable via `adb pull /sdcard/Android/data/navigator.app/files/captures/`. */
    private fun capturesDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "captures").apply { mkdirs() }
    }

    private fun writeDump(context: Context, dump: A11yDump): File? = try {
        val sanitized = dump.site.replace(Regex("[^A-Za-z0-9_-]"), "-").ifBlank { "site" }
        val file = File(
            capturesDir(context),
            "capture-$sanitized-${System.currentTimeMillis()}.json",
        )
        file.writeText(json.encodeToString(A11yDump.serializer(), dump))
        file
    } catch (_: java.io.IOException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    /** The probe's child-text cap (Stage 3 diagnostic only — never a production label). */
    private const val CHILD_TEXT_PROBE_CAP = 160
}
