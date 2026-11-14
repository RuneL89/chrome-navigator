package navigator.app.harness.webview

import navigator.app.harness.webview.dom.Bounds
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.PageMeta

/** Shared snapshot builders for the lane's L1/L2 suites. */
object SnapshotFixtures {
    fun element(
        id: Int,
        role: String = "button",
        label: String = "",
        valueState: String = "",
        required: Boolean = false,
        x: Int = 0,
        y: Int = 0,
        w: Int = 100,
        h: Int = 40,
        tag: String = "button",
        type: String = "",
    ): DomElement = DomElement(
        id = id,
        role = role,
        label = label,
        valueState = valueState,
        required = required,
        bounds = Bounds(x, y, w, h),
        tag = tag,
        type = type,
    )

    fun snapshot(
        url: String = "https://shop.test/page",
        title: String = "Shop",
        text: String = "A page",
        elements: List<DomElement> = emptyList(),
    ): DomSnapshot = DomSnapshot(PageMeta(url, title), elements, text)

    /**
     * A 500-element fixture (AC-S1.1's load shape): buttons and links interleaved,
     * realistic labels, real bounds.
     */
    fun bigSnapshot(count: Int = 500): DomSnapshot {
        val elements = (0 until count).map { i ->
            val role = if (i % 2 == 0) "button" else "link"
            element(
                id = i,
                role = role,
                label = if (i % 2 == 0) "Action button $i" else "Navigate to page $i",
                x = (i % 5) * 120,
                y = (i / 5) * 60,
                tag = if (role == "button") "button" else "a",
            )
        }
        return snapshot(url = "https://shop.test/catalog", elements = elements, text = "catalog page ".repeat(300))
    }

    /** A scripted WebView (shared: the loop + purchase-flow suites): the FIRST page is current. */
    class FakeWebView(
        private val pages: ArrayDeque<DomSnapshot>,
        var autoNavigate: Boolean = false,
    ) : LaneWebView {
        val clickLog = mutableListOf<String>()
        val textLog = mutableListOf<String>()
        val loadLog = mutableListOf<String>()

        override val view: android.view.View get() = error("fakes carry no view")

        override suspend fun load(url: String): String {
            loadLog.add(url)
            if (autoNavigate && pages.size > 1) pages.removeFirst()
            return pages.first().page.url
        }

        override suspend fun currentUrl(): String = pages.first().page.url
        override suspend fun snapshot(): DomSnapshot = pages.first()

        override suspend fun evaluate(js: String): String = when {
            js.contains("el.click()") -> {
                clickLog.add(js)
                if (autoNavigate && pages.size > 1) pages.removeFirst()
                "{\"ok\":true,\"clicked\":true,\"hasDownHandler\":false}"
            }

            js.contains("value: el.value") -> {
                textLog.add("text")
                "{\"ok\":true,\"requested\":\"typed\",\"value\":\"typed\"}"
            }

            js.contains("el.checked") -> "{\"ok\":true,\"requested\":true,\"checked\":true}"
            else -> "{\"ok\":true}"
        }

        override suspend fun dispatchTrustedTouch(cssX: Int, cssY: Int) {
            clickLog.add("trusted-touch")
        }

        override suspend fun goBack() {}
        override fun destroy() {}
    }
}
