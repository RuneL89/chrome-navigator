package navigator.app.harness

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * The harness's static test-page server (Gate 6.2): serves `assets/harness/pages/` over
 * the device's loopback interface, so the scripted goals navigate real http:// URLs
 * ("static local test page", phase doc Gate 6.2) with zero host-side tooling. BOUND to
 * loopback only — nothing off-device can reach it; it serves harness assets and nothing
 * else, and it is a debug-harness fixture, not product code.
 */
class HarnessPagesServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
) {
    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        val socket = ServerSocket()
        // Bind the IPv4 loopback explicitly: Android resolves the loopback address to
        // ::1, while browsers fetch http://127.0.0.1:... over IPv4.
        socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port))
        socket.soTimeout = ACCEPT_TIMEOUT_MS
        serverSocket = socket
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    break // socket closed by stop()
                }
                try {
                    handle(client)
                } catch (e: IOException) {
                    // per-connection IO failure: the browser retries, never crashes the server
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "harness-pages-server").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    /** One GET/HEAD request -> one response, connection closed (pages are static). */
    private fun handle(socket: Socket) {
        socket.setSoTimeout(SOCKET_TIMEOUT_MS)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        val clean = path.substringBefore('?').removePrefix("/")
        if (clean.contains("..") || clean.contains('\\')) {
            respond(socket, 400, "text/plain", "bad request".toByteArray())
            return
        }
        val assetPath = PAGES_ASSET_ROOT + if (clean.isEmpty()) "index.html" else clean
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: IOException) {
            respond(socket, 404, "text/plain", "no such page: $clean".toByteArray())
            return
        }
        respond(socket, 200, contentType(assetPath), bytes, headOnly = requestLine.startsWith("HEAD"))
    }

    private fun respond(
        socket: Socket,
        status: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false,
    ) {
        val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun contentType(assetPath: String): String = when (assetPath.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private companion object {
        const val DEFAULT_PORT = 8099
        const val PAGES_ASSET_ROOT = "harness/pages/"
        const val ACCEPT_TIMEOUT_MS = 500
        const val SOCKET_TIMEOUT_MS = 5_000
    }
}
