package navigator.core.wire

/**
 * Deterministic in-memory [WireTransport] for the transport-law gates (Gate 3.2 and the
 * JevEngine failover test): no sockets, no wall-clock timing, per-endpoint attempt counts.
 *
 * It replaces the earlier NO_RESPONSE + real-call-timeout harness for the transport-failure
 * cases. Root cause of that flake (verifier-reproduced: `primary.requestCount` expected 3,
 * was 2): under a loaded test JVM the client's call timeout could fire while an attempt's
 * connection was still in the server's accept backlog; OkHttp cancelled the call and the
 * socket died before MockWebServer dispatched it, so the SERVER-side `requestCount`
 * undercounted the client's attempts (the client was correct — `usedFallback=true` proves
 * exactly 3 primary [WireTransportException]s). The law's attempt counting is now asserted
 * against the transport the client actually drove, with zero timing dependence.
 *
 * The handler returns the response for a call or throws [WireTransportException] to
 * simulate a transport failure — the ONLY error class the retry/failover law reacts to
 * (vision 02 §2). The real-IOException → [WireTransportException] mapping of the production
 * [OkHttpWireTransport] stays covered by the connection-refused test in
 * [FailoverRetryLawTest]; Gate 3.1/3.3 keep OkHttp against MockWebServer for real request
 * bytes and real HTTP statuses.
 */
class CountingWireTransport(
    private val handler: (call: Call) -> WireHttpResponse,
) : WireTransport {

    /** One recorded [WireTransport.execute] invocation. */
    class Call(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        /** 1-based number of the call to this [url] — the per-endpoint attempt counter. */
        val callNumber: Int,
    )

    /** Every call, in order. */
    val calls = mutableListOf<Call>()

    /** How many times [url] was called: the client's true attempt count for an endpoint. */
    fun callsTo(url: String): Int = calls.count { it.url == url }

    override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
        val call = Call(
            url = request.url,
            headers = request.headers,
            body = request.body,
            callNumber = callsTo(request.url) + 1,
        )
        calls += call
        return handler(call)
    }
}
