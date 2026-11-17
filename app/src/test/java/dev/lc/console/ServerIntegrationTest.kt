package dev.lc.console

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Exercises the real HTTP stack (LcHttpServer + ConsoleApi) over sockets on
 * 127.0.0.1: authentication, CSRF, rate limiting, lockout, allowlist blocking,
 * command execution, history and the audit log — the server-side acceptance
 * criteria that can be verified without a device.
 */
class ServerIntegrationTest {

    private data class Resp(val status: Int, val headers: Map<String, List<String>>, val body: String)

    private lateinit var server: LcHttpServer
    private lateinit var audit: AuditLog
    private lateinit var history: HistoryStore
    private lateinit var sessions: SessionManager
    private lateinit var api: ConsoleApi
    private var auditFile: File? = null
    private var historyFile: File? = null
    private var advanced = false

    // Fresh port per server instance: restarting on the same port can race
    // with a lingering connection from the previous instance.
    private var port = ServerSocket(0).use { it.localPort }

    private val token = "test-token-0123456789abcdefghijklmnopqrstuv"

    @Before
    fun setUp() {
        auditFile = File.createTempFile("audit", ".jsonl")
        historyFile = File.createTempFile("history", ".jsonl")
        audit = AuditLog(auditFile!!)
        history = HistoryStore(historyFile!!)
        sessions = SessionManager()
        startServer()
    }

    private fun startServer() {
        if (this::server.isInitialized) server.stop()
        port = ServerSocket(0).use { it.localPort }
        val box = TokenBox(token)
        api = ConsoleApi(
            tokens = box,
            sessions = sessions,
            rateLimiter = RateLimiter(),
            authGuard = AuthGuard(),
            audit = audit,
            history = history,
            jobs = JobManager(audit, history, object : JobManager.Config {
                override fun advanced(): Boolean = advanced
                override fun timeoutSec(): Int = 10
            }, outputCapBytes = 64 * 1024),
            config = object : ConsoleApi.Config {
                override fun advanced(): Boolean = advanced
                override fun lanEnabled(): Boolean = false
                override fun port(): Int = port
                override fun timeoutSec(): Int = 10
            }
        )
        server = LcHttpServer(port, "127.0.0.1", null) { api.handle(it) }
        server.start()
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) server.stop()
        auditFile?.delete()
        historyFile?.delete()
    }

    // ---- tiny http client --------------------------------------------------

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookie: String? = null
    ): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        headers.forEach { conn.setRequestProperty(it.key, it.value) }
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        }
        val status = conn.responseCode
        val text = if (status < 400) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        return Resp(status, conn.headerFields.filterKeys { it != null }, text)
    }

    private fun setCookies(resp: Resp): List<String> = resp.headers["Set-Cookie"] ?: emptyList()

    /** One successful login; returns the cookie header and the CSRF value. */
    private fun loginPair(): Pair<String, String> {
        val resp = request(
            "POST", "/api/login", """{"token":"$token"}"""
        )
        assertEquals(200, resp.status)
        val set = setCookies(resp)
        val cookie = set.joinToString("; ") { it.substringBefore(";") }
        val csrf = set.first { it.startsWith("lc_csrf=") }
            .substringAfter("=").substringBefore(";")
        return cookie to csrf
    }

    // ---- the tests ---------------------------------------------------------

    @Test
    fun `missing token returns 401 before any command handling`() {
        val r = request("GET", "/")
        assertEquals(401, r.status)
        assertTrue(r.headers["Cache-Control"]!!.first().contains("no-store"))
        assertEquals(401, request("POST", "/api/run", """{"command":"ls"}""").status)
    }

    @Test
    fun `wrong token returns 401`() {
        assertEquals(401, request("POST", "/api/login", """{"token":"wrong"}""").status)
        assertEquals(
            401,
            request("GET", "/api/me", headers = mapOf("Authorization" to "Bearer wrong")).status
        )
    }

    @Test
    fun `correct token unlocks the console`() {
        val (cookie, _) = loginPair()
        val r = request("GET", "/", cookie = cookie)
        assertEquals(200, r.status)
        assertTrue(r.headers["Content-Type"]!!.first().startsWith("text/html"))
        assertTrue(r.body.contains("LC console"))
        assertTrue(r.body.contains("/api/run"))
        assertEquals(200, request("GET", "/api/me", cookie = cookie).status)
    }

    @Test
    fun `responses carry no-store headers`() {
        val r = request("GET", "/")
        assertEquals("no-store", r.headers["Cache-Control"]!!.first())
        assertEquals("no-cache", r.headers["Pragma"]!!.first())
        assertEquals("nosniff", r.headers["X-Content-Type-Options"]!!.first())
    }

    @Test
    fun `cookie-authenticated state change without CSRF header is rejected`() {
        val (cookie, csrf) = loginPair()
        assertEquals(
            403,
            request("POST", "/api/history/clear", body = "{}", cookie = cookie).status
        )
        assertEquals(
            200,
            request("POST", "/api/history/clear", body = "{}",
                headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie).status
        )
    }

    @Test
    fun `bearer token works without CSRF for scripted use`() {
        assertEquals(
            200,
            request("POST", "/api/history/clear", body = "{}",
                headers = mapOf("Authorization" to "Bearer $token")).status
        )
    }

    @Test
    fun `allowlisted commands run and return output`() {
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"uname"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        assertEquals(200, start.status)
        val id = JSONObject(start.body).getString("id")
        val result = waitForResult(cookie, id)
        assertEquals("completed", result.getString("status"))
        assertEquals(0, result.getInt("exit"))
    }

    @Test
    fun `non-allowlisted command is blocked with 403 and audited`() {
        val (cookie, csrf) = loginPair()
        val r = request("POST", "/api/run", """{"command":"touch /tmp/lc-should-not-exist"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        assertEquals(403, r.status)
        assertTrue(audit.entries().any {
            it.optString("mode") == "blocked" && it.optString("command").contains("touch")
        })
    }

    @Test
    fun `command history and audit are served and clearable`() {
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"uptime"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        val id = JSONObject(start.body).getString("id")
        waitForResult(cookie, id)

        val hist = JSONObject(request("GET", "/api/history", cookie = cookie).body)
        assertTrue((0 until hist.getJSONArray("items").length()).any {
            hist.getJSONArray("items").getString(it) == "uptime"
        })
        assertTrue(
            JSONObject(request("GET", "/api/audit", cookie = cookie).body)
                .getJSONArray("items").length() > 0
        )

        request("POST", "/api/history/clear", body = "{}",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        request("POST", "/api/audit/clear", body = "{}",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        assertEquals(0, JSONObject(request("GET", "/api/history", cookie = cookie).body)
            .getJSONArray("items").length())
        assertEquals(0, JSONObject(request("GET", "/api/audit", cookie = cookie).body)
            .getJSONArray("items").length())
    }

    @Test
    fun `sse stream delivers stdout and the done event`() {
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"uname"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        val id = JSONObject(start.body).getString("id")

        val conn = URL("http://127.0.0.1:$port/api/stream?id=$id").openConnection()
            as HttpURLConnection
        conn.setRequestProperty("Cookie", cookie)
        conn.readTimeout = 20000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(text.contains("event: out"))
        assertTrue(text.contains("Linux"))
        assertTrue(text.contains("event: done"))
        assertTrue(text.contains("\"status\":\"completed\""))
    }

    @Test
    fun `running job can be cancelled`() {
        advanced = true
        startServer()
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"sleep 30"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        val id = JSONObject(start.body).getString("id")
        assertEquals(200, request("POST", "/api/cancel", """{"id":"$id"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie).status)
        assertEquals("cancelled", waitForResult(cookie, id).getString("status"))
        advanced = false
        startServer()
    }

    @Test
    fun `advanced mode runs non-allowlisted commands but still forbids su`() {
        advanced = true
        startServer()
        val (cookie, csrf) = loginPair()
        assertEquals(200, request("POST", "/api/run", """{"command":"sleep 0.2"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie).status)
        assertEquals(403, request("POST", "/api/run", """{"command":"su"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie).status)
        advanced = false
        startServer()
    }

    @Test
    fun `timeout is enforced end to end`() {
        advanced = true
        startServer()
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"sleep 30"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        val id = JSONObject(start.body).getString("id")
        assertEquals("timeout", waitForResult(cookie, id, timeoutMs = 20000).getString("status"))
        advanced = false
        startServer()
    }

    @Test
    fun `output cap is enforced end to end`() {
        advanced = true
        startServer()
        val (cookie, csrf) = loginPair()
        val start = request("POST", "/api/run", """{"command":"yes"}""",
            headers = mapOf("X-LC-CSRF" to csrf), cookie = cookie)
        val id = JSONObject(start.body).getString("id")
        val result = waitForResult(cookie, id, timeoutMs = 20000)
        assertTrue(
            "expected truncated, got $result",
            result.getString("status") == "truncated" && result.getBoolean("truncated")
        )
        advanced = false
        startServer()
    }

    @Test
    fun `rate limiter returns 429 on hammering`() {
        var last = 0
        for (i in 0 until 300) {
            last = request("GET", "/api/me").status
            if (last == 429) break
        }
        assertEquals(429, last)
    }

    @Test
    fun `lockout after repeated login failures`() {
        var last = 0
        for (i in 0 until 10) {
            last = request("POST", "/api/login", """{"token":"nope$i"}""").status
        }
        // after 5 failures the key is locked: login attempts return 429, not 401
        assertEquals(429, last)
    }

    @Test
    fun `unknown api path is 404`() {
        val (cookie, _) = loginPair()
        assertEquals(404, request("GET", "/api/nothing", cookie = cookie).status)
    }

    // ---- helpers -----------------------------------------------------------

    private fun getResult(cookie: String, id: String): JSONObject =
        JSONObject(request("GET", "/api/result?id=$id", cookie = cookie).body)

    private fun waitForResult(cookie: String, id: String, timeoutMs: Long = 15000): JSONObject {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val r = getResult(cookie, id)
            if (r.has("status") && r.getString("status") != "running") return r
            Thread.sleep(100)
        }
        throw AssertionError("job $id did not finish in time")
    }
}
