package navigator.core.wire

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A transport-layer error (timeout, connection failure). The ONLY error class the retry
 * and failover law reacts to (vision 02 §2): retried up to [SystemOneClient.maxRetries]
 * times with exponential backoff, then failed over to the fallback config (one hop).
 */
class WireTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A content problem: non-2xx HTTP status, malformed body, missing required field, unknown
 * question name, or a distribution outside the declared names. NEVER retried and NEVER
 * failed over — no answer-shopping (vision 02 §2); it fails loudly instead.
 */
class WireProtocolException(message: String) : RuntimeException(message)

/** One outbound provider request. The API key travels only in headers — never logged. */
class WireHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/** One provider response. Non-2xx codes are content problems, not transport errors. */
class WireHttpResponse(
    val code: Int,
    val body: String,
)

/**
 * Injectable HTTP transport (phase 3 §2.1): production uses [OkHttpWireTransport]; the
 * wire gates use OkHttp against MockWebServer, so no gate ever touches a live provider.
 * Only this interface and [SystemOneClient] speak to providers — ART-2's single gateway.
 */
interface WireTransport {
    suspend fun execute(request: WireHttpRequest): WireHttpResponse
}

/**
 * OkHttp-backed [WireTransport] (vision 01 §3 stack). Blocking calls are confined to
 * `Dispatchers.IO`.
 *
 * OkHttp's internal transparent connection retry is DISABLED: the retry law (vision 02 §2
 * — max 2 retries, transport errors only, never on content) must be the only source of
 * retries, otherwise request counts stop being auditable.
 */
class OkHttpWireTransport(
    private val client: OkHttpClient = defaultClient(),
) : WireTransport {

    constructor(callTimeoutMs: Long) : this(
        OkHttpClient.Builder()
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    override suspend fun execute(request: WireHttpRequest): WireHttpResponse =
        withContext(Dispatchers.IO) {
            // Content-Type rides in the request headers (transport-neutral); OkHttp takes
            // it from the request body, so pass it there and keep other headers as-is.
            val contentType = request.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value ?: "application/json; charset=utf-8"
            val builder = Request.Builder().url(request.url)
            for ((name, value) in request.headers) {
                if (!name.equals("Content-Type", ignoreCase = true)) builder.header(name, value)
            }
            builder.post(request.body.toRequestBody(contentType.toMediaType()))
            try {
                client.newCall(builder.build()).execute().use { response ->
                    WireHttpResponse(response.code, response.body?.string() ?: "")
                }
            } catch (e: IOException) {
                // URL only — the key is in a header and never appears in errors/logs.
                throw WireTransportException(
                    "transport error calling ${request.url}: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 15_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
