package dev.lc.console

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory bearer sessions.
 *
 * A session is an opaque random id created after a successful token login.
 * Cookies: `lc_session` (HttpOnly) and `lc_csrf` (readable by the page JS so it
 * can echo the CSRF header). Sessions expire after [ttlMs] of inactivity.
 *
 * Sessions are kept in the service process only: stopping the console or
 * restarting it logs every client out and they re-pair with the token. This is
 * deliberate — nothing sensitive is written to disk for the web layer.
 */
class SessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {

    data class Session(val createdAt: Long, @Volatile var lastSeen: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun create(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val sid = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        sessions[sid] = Session(now, now)
        return sid
    }

    /** True when the id is live and inside the TTL; refreshes lastSeen on success. */
    fun validate(sid: String?): Boolean {
        if (sid.isNullOrEmpty()) return false
        val s = sessions[sid] ?: return false
        val now = clock()
        if (now - s.lastSeen > ttlMs) {
            sessions.remove(sid)
            return false
        }
        s.lastSeen = now
        return true
    }

    fun invalidate(sid: String?) {
        if (sid != null) sessions.remove(sid)
    }

    fun clear() = sessions.clear()

    companion object {
        const val DEFAULT_TTL_MS: Long = 12L * 60 * 60 * 1000
    }
}
