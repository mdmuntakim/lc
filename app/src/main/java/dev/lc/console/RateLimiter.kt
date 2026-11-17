package dev.lc.console

import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window per-key (per-IP) request rate limiter.
 *
 * Default budget: 240 requests per minute per client, which is generous for
 * interactive use but stops scripted hammering.
 */
class RateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS
) {

    private data class Window(var start: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String): Boolean {
        val now = clock()
        // Prune stale entries occasionally to keep memory bounded.
        if (windows.size > MAX_TRACKED_KEYS) {
            windows.entries.removeIf { now - it.value.start > windowMs }
        }
        val w = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.start >= windowMs) Window(now, 0) else existing
        } ?: return false
        synchronized(w) {
            if (w.count >= maxRequests) return false
            w.count++
            return true
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 60_000
        const val DEFAULT_MAX_REQUESTS = 240
        private const val MAX_TRACKED_KEYS = 512
    }
}

/**
 * Lockout after repeated authentication failures, per IP.
 * [maxFailures] failures inside [failureWindowMs] lock the key for [lockoutMs].
 */
class AuthGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS
) {

    private data class Failures(var windowStart: Long, var count: Int)

    private val failures = ConcurrentHashMap<String, Failures>()
    private val locked = ConcurrentHashMap<String, Long>() // key -> until

    fun isLocked(key: String): Boolean {
        val until = locked[key] ?: return false
        if (clock() >= until) {
            locked.remove(key, until)
            return false
        }
        return true
    }

    fun recordFailure(key: String) {
        val now = clock()
        val f = failures.compute(key) { _, existing ->
            if (existing == null || now - existing.windowStart >= failureWindowMs) Failures(now, 0)
            else existing
        } ?: return
        synchronized(f) {
            f.count++
            if (f.count >= maxFailures) {
                locked[key] = now + lockoutMs
                failures.remove(key)
            }
        }
    }

    fun recordSuccess(key: String) {
        failures.remove(key)
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_FAILURE_WINDOW_MS: Long = 5 * 60_000
        const val DEFAULT_LOCKOUT_MS: Long = 5 * 60_000
    }
}
