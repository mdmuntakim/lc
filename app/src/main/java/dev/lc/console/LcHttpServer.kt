package dev.lc.console

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocketFactory

/** One parsed HTTP request handed to the handler. */
class HttpExchange(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? {
        val raw = header("cookie") ?: return null
        for (part in raw.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name) return kv[1]
        }
        return null
    }

    fun jsonBody(): org.json.JSONObject? = try {
        org.json.JSONObject(String(body, Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    fun textBody(): String = String(body, Charsets.UTF_8)
}

/** Response built by the handler; [stream] turns the response into an open stream (SSE). */
class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray = ByteArray(0),
    /** Extra headers as pairs, so repeated names like Set-Cookie survive. */
    val extraHeaders: List<Pair<String, String>> = emptyList(),
    val stream: ((OutputStream) -> Unit)? = null
) {
    companion object {
        fun json(status: Int, obj: Any): HttpResponse =
            HttpResponse(status, "application/json; charset=utf-8",
                obj.toString().toByteArray(Charsets.UTF_8))

        fun html(status: Int, page: String): HttpResponse =
            HttpResponse(status, "text/html; charset=utf-8",
                page.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Minimal HTTP/1.1 server, one request per connection (`Connection: close`).
 * Hand-rolled on purpose: no third-party server dependency, and the whole
 * auth/exec surface can be exercised by plain JVM tests over real sockets.
 *
 * [sslFactory] non-null means TLS (opt-in LAN mode); otherwise plain TCP.
 * Every response carries no-store/nosniff/referrer headers; nothing external
 * is ever referenced by the served pages.
 */
class LcHttpServer(
    val port: Int,
    val bindAddress: String,
    private val sslFactory: SSLServerSocketFactory?,
    private val handler: (HttpExchange) -> HttpResponse
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "lc-http").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = port
        private set

    fun start() {
        check(running.compareAndSet(false, true)) { "server already running" }
        val socket = try {
            val addr = InetAddress.getByName(bindAddress)
            if (sslFactory != null) {
                // create unbound so reuseAddress can be set before binding
                val s = sslFactory.createServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(addr, port), BACKLOG)
                s
            } else {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(addr, port), BACKLOG)
                s
            }
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        serverSocket = socket
        boundPort = socket.localPort
        val acceptor = Thread({
            try {
                while (running.get()) {
                    val client = socket.accept()
                    executor.submit { safeHandle(client) }
                }
            } catch (_: Exception) {
                // closed while stopping
            }
        }, "lc-accept")
        acceptor.isDaemon = true
        acceptor.start()
    }

    private fun safeHandle(socket: Socket) {
        try {
            socket.use { handle(it) }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = HEADER_TIMEOUT_MS
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) { writeSimpleError(socket, 400, "bad request line"); return }
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val te = headers["transfer-encoding"]
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            writeSimpleError(socket, 411, "chunked bodies are not accepted"); return
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY_BYTES) {
            writeSimpleError(socket, 413, "body too large"); return
        }
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }

        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = HashMap<String, String>()
        if (qIdx >= 0) {
            for (pair in target.substring(qIdx + 1).split("&")) {
                if (pair.isEmpty()) continue
                val kv = pair.split("=", limit = 2)
                query[URLDecoder.decode(kv[0], "UTF-8")] =
                    if (kv.size == 2) URLDecoder.decode(kv[1], "UTF-8") else ""
            }
        }

        val exchange = HttpExchange(
            method, path, query, headers, body,
            socket.inetAddress?.hostAddress ?: "unknown"
        )

        val response = try {
            handler(exchange)
        } catch (e: Exception) {
            HttpResponse.json(500, org.json.JSONObject().put("error", "internal error"))
        }

        writeResponse(socket, response)
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        socket.soTimeout = 0
        val out = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        head.append("Server: LC\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Pragma: no-cache\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("Connection: close\r\n")
        for ((k, v) in response.extraHeaders) head.append("$k: $v\r\n")
        if (response.stream != null) {
            head.append("Content-Type: ${response.contentType}\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
            try {
                response.stream(out)
                out.flush()
            } catch (_: Exception) {
                // client went away mid-stream
            }
            return
        }
        head.append("Content-Type: ${response.contentType}\r\n")
        head.append("Content-Length: ${response.body.size}\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun writeSimpleError(socket: Socket, status: Int, message: String) {
        try {
            writeResponse(socket, HttpResponse.json(status,
                org.json.JSONObject().put("error", message)))
        } catch (_: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < MAX_HEADER_LINE) {
            val c = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return null
            } catch (_: java.io.IOException) {
                return null
            }
            if (c < 0) return null
            if (c == '\n'.code) {
                val bytes = buf.toByteArray()
                return if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
                } else {
                    String(bytes, Charsets.ISO_8859_1)
                }
            }
            buf.write(c)
        }
        return null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        429 -> "Too Many Requests"; 500 -> "Internal Server Error"; else -> "Status $status"
    }

    companion object {
        private const val BACKLOG = 50
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_HEADER_LINE = 16 * 1024
        private const val HEADER_TIMEOUT_MS = 30_000
    }
}
