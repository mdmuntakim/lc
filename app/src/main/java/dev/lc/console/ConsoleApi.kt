package dev.lc.console

import org.json.JSONArray
import org.json.JSONObject

/**
 * The console HTTP API: routing, authentication, CSRF, rate limiting and the
 * endpoint set. Context-free so the entire surface is covered by JVM tests.
 *
 * Auth model:
 *  - `Authorization: Bearer <token>` — for scripted use, CSRF-free.
 *  - Or the HttpOnly `lc_session` cookie obtained from POST /api/login;
 *    state-changing requests then need the `X-LC-CSRF` header echoing the
 *    `lc_csrf` cookie (double-submit).
 *  - Everything except POST /api/login and the static pages requires auth.
 *  - Wrong credentials count towards a per-IP lockout (5 failures → 5 min).
 *  - Per-IP rate limit on every request.
 */
class ConsoleApi(
    private val tokens: TokenBox,
    private val sessions: SessionManager,
    private val rateLimiter: RateLimiter,
    private val authGuard: AuthGuard,
    private val audit: AuditLog,
    private val history: HistoryStore,
    private val jobs: JobManager,
    private val config: Config
) {

    interface Config : JobManager.Config {
        /** LAN exposure flag (TLS). */
        fun lanEnabled(): Boolean

        /** Listening port. */
        fun port(): Int
    }

    fun handle(ex: HttpExchange): HttpResponse {
        if (!rateLimiter.allow(ex.remoteIp)) {
            return tooMany("rate limit: too many requests from your address")
        }

        return when {
            ex.path == "/" && ex.method == "GET" -> rootPage(ex)
            ex.path == "/api/login" && ex.method == "POST" -> login(ex)
            ex.path.startsWith("/api/") -> apiCall(ex)
            else -> HttpResponse.json(404, JSONObject().put("error", "not found"))
        }
    }

    // ---- pages -------------------------------------------------------------

    private fun rootPage(ex: HttpExchange): HttpResponse {
        val authorized = authorize(ex).ok
        return if (authorized) {
            HttpResponse.html(200, ConsoleHtml.CONSOLE_PAGE)
        } else {
            HttpResponse.html(401, ConsoleHtml.LOGIN_PAGE)
        }
    }

    // ---- auth --------------------------------------------------------------

    private class AuthResult(val ok: Boolean, val error: HttpResponse?) {
        companion object {
            val OK = AuthResult(true, null)
            fun fail(r: HttpResponse) = AuthResult(false, r)
        }
    }

    private fun authorize(ex: HttpExchange): AuthResult {
        if (authGuard.isLocked(ex.remoteIp)) {
            return AuthResult.fail(tooMany("locked out after repeated failures, try again later"))
        }

        val bearer = ex.header("authorization")
        if (bearer != null) {
            val presented = bearer.removePrefix("Bearer").trim()
            if (tokens.verify(presented)) return AuthResult.OK
            authGuard.recordFailure(ex.remoteIp)
            return AuthResult.fail(unauthorized("invalid token"))
        }

        val sid = ex.cookie(SESSION_COOKIE)
        if (sid != null) {
            if (!sessions.validate(sid)) {
                authGuard.recordFailure(ex.remoteIp)
                return AuthResult.fail(unauthorized("session expired, log in again"))
            }
            if (ex.method == "POST") {
                val csrfCookie = ex.cookie(CSRF_COOKIE) ?: ""
                if (csrfCookie.isEmpty() || !Tokens.equal(csrfCookie, ex.header("x-lc-csrf"))) {
                    return AuthResult.fail(
                        HttpResponse.json(403, JSONObject().put("error", "missing or bad CSRF token"))
                    )
                }
            }
            return AuthResult.OK
        }

        return AuthResult.fail(unauthorized("pairing token required"))
    }

    private fun login(ex: HttpExchange): HttpResponse {
        if (authGuard.isLocked(ex.remoteIp)) {
            return tooMany("locked out after repeated failures, try again later")
        }
        val body = ex.jsonBody()
        val presented = body?.optString("token") ?: ""
        if (tokens.verify(presented)) {
            authGuard.recordSuccess(ex.remoteIp)
            val sid = sessions.create()
            val secure = if (config.lanEnabled()) "; Secure" else ""
            return withCookies(
                HttpResponse.json(200, JSONObject().put("ok", true)),
                listOf(
                    "$SESSION_COOKIE=$sid; Path=/; HttpOnly; SameSite=Strict$secure",
                    "${CSRF_COOKIE}=${Sessions.csrfToken(sid)}; Path=/; SameSite=Strict$secure"
                )
            )
        }
        authGuard.recordFailure(ex.remoteIp)
        return unauthorized("wrong token")
    }

    private fun logout(ex: HttpExchange, sid: String?): HttpResponse {
        sessions.invalidate(sid)
        return withCookies(
            HttpResponse.json(200, JSONObject().put("ok", true)),
            listOf(
                "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict",
                "${CSRF_COOKIE}=; Path=/; Max-Age=0; SameSite=Strict"
            )
        )
    }

    // ---- api ---------------------------------------------------------------

    private fun apiCall(ex: HttpExchange): HttpResponse {
        // login is handled above; everything else needs auth
        val auth = authorize(ex)
        if (!auth.ok) return auth.error!!

        val sid = ex.cookie(SESSION_COOKIE)

        return when (ex.path) {
            "/api/me" -> me()
            "/api/logout" -> if (ex.method == "POST") logout(ex, sid) else methodNotAllowed()
            "/api/run" -> if (ex.method == "POST") run(ex) else methodNotAllowed()
            "/api/result" -> result(ex)
            "/api/stream" -> stream(ex)
            "/api/cancel" -> if (ex.method == "POST") cancel(ex) else methodNotAllowed()
            "/api/history" -> historyItems()
            "/api/history/clear" -> if (ex.method == "POST") {
                history.clear()
                HttpResponse.json(200, JSONObject().put("ok", true))
            } else methodNotAllowed()
            "/api/audit" -> auditItems()
            "/api/audit/clear" -> if (ex.method == "POST") {
                audit.clear()
                HttpResponse.json(200, JSONObject().put("ok", true))
            } else methodNotAllowed()
            else -> HttpResponse.json(404, JSONObject().put("error", "not found"))
        }
    }

    private fun me(): HttpResponse {
        val bind = if (config.lanEnabled()) {
            "https://0.0.0.0:${config.port()} (TLS, all interfaces)"
        } else {
            "http://127.0.0.1:${config.port()}"
        }
        return HttpResponse.json(
            200, JSONObject()
                .put("mode", if (config.advanced()) "advanced" else "allowlist")
                .put("lan", config.lanEnabled())
                .put("port", config.port())
                .put("timeout_sec", config.timeoutSec())
                .put("bind", bind)
        )
    }

    private fun run(ex: HttpExchange): HttpResponse {
        val body = ex.jsonBody()
        val command = body?.optString("command") ?: ""
        if (command.isBlank()) {
            return HttpResponse.json(400, JSONObject().put("error", "empty command"))
        }
        val (job, reason) = jobs.start(command)
        if (job == null) {
            return HttpResponse.json(403, JSONObject().put("error", reason))
        }
        return HttpResponse.json(200, JSONObject().put("id", job.id))
    }

    private fun result(ex: HttpExchange): HttpResponse {
        val job = jobs.get(ex.query["id"] ?: "")
            ?: return HttpResponse.json(404, JSONObject().put("error", "unknown job"))
        return if (job.result != null) {
            HttpResponse.json(200, job.result!!)
        } else {
            HttpResponse.json(200, JSONObject().put("status", "running"))
        }
    }

    private fun stream(ex: HttpExchange): HttpResponse {
        val job = jobs.get(ex.query["id"] ?: "")
            ?: return HttpResponse.json(404, JSONObject().put("error", "unknown job"))
        return HttpResponse(
            200, "text/event-stream; charset=utf-8",
            extraHeaders = listOf("X-Accel-Buffering" to "no"),
            stream = { out ->
                val listener: (JSONObject) -> Unit = { ev ->
                    val data = ev.optJSONObject("data") ?: JSONObject()
                    val frame = "event: ${ev.optString("type")}\n" +
                        "id: ${ev.optInt("seq")}\n" +
                        "data: ${data}\n\n"
                    out.write(frame.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                try {
                    // replay what happened before the stream opened
                    job.eventsSince(0).forEach(listener)
                    var last = job.lastSeq()
                    while (job.result == null) {
                        val pending = job.eventsSince(last)
                        if (pending.isNotEmpty()) {
                            pending.forEach(listener)
                            last = pending.last().optInt("seq")
                        } else {
                            synchronized(job.monitor) {
                                job.monitor.wait(15000)
                            }
                        }
                    }
                    // final events emitted after the wait loop
                    job.eventsSince(last).forEach(listener)
                    out.write("retry: 5000\n\n".toByteArray(Charsets.UTF_8))
                } finally {
                    job.subscriberDied(listener)
                }
            }
        )
    }

    private fun cancel(ex: HttpExchange): HttpResponse {
        val id = ex.jsonBody()?.optString("id") ?: ex.query["id"] ?: ""
        val ok = jobs.cancel(id)
        return HttpResponse.json(
            if (ok) 200 else 404,
            JSONObject().put("ok", ok).put("error", if (ok) JSONObject.NULL else "unknown job")
        )
    }

    private fun historyItems(): HttpResponse =
        HttpResponse.json(200, JSONObject().put("items", JSONArray(history.entries())))

    private fun auditItems(): HttpResponse =
        HttpResponse.json(200, JSONObject().put("items", JSONArray(audit.entries())))

    // ---- helpers -----------------------------------------------------------

    private fun unauthorized(msg: String): HttpResponse =
        withHeaders(
            HttpResponse.json(401, JSONObject().put("error", msg)),
            listOf("WWW-Authenticate" to "Bearer realm=\"lc-console\"")
        )

    private fun tooMany(msg: String): HttpResponse =
        withHeaders(
            HttpResponse.json(429, JSONObject().put("error", msg)),
            listOf("Retry-After" to "30")
        )

    private fun withHeaders(r: HttpResponse, headers: List<Pair<String, String>>): HttpResponse =
        HttpResponse(r.status, r.contentType, r.body, r.extraHeaders + headers)

    private fun withCookies(r: HttpResponse, cookies: List<String>): HttpResponse =
        withHeaders(r, cookies.map { "Set-Cookie" to it })

    private fun methodNotAllowed(): HttpResponse =
        HttpResponse.json(405, JSONObject().put("error", "method not allowed"))

    companion object {
        const val SESSION_COOKIE = "lc_session"
        const val CSRF_COOKIE = "lc_csrf"
    }
}

/** CSRF token derived from the session id: same-lifetime, per-session value. */
object Sessions {
    fun csrfToken(sid: String): String {
        val bytes = sid.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 32)
    }
}
