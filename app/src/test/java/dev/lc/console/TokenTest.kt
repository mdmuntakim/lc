package dev.lc.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class TokenTest {

    @Test
    fun `generated tokens are 256-bit base64url and unique`() {
        val a = Tokens.generate()
        val b = Tokens.generate()
        assertEquals(43, a.length)
        assertTrue(a.none { it == '+' || it == '/' || it == '=' })
        assertTrue(a != b)
    }

    @Test
    fun `verification is exact and constant-time safe`() {
        val box = TokenBox("abc123")
        assertTrue(box.verify("abc123"))
        assertFalse(box.verify("abc124"))
        assertFalse(box.verify(null))
        assertFalse(box.verify(""))
        // different length must not throw or pass
        assertFalse(box.verify("abc123456789"))
    }

    @Test
    fun `rotation replaces the token`() {
        val box = TokenBox(null)
        val t1 = box.rotate()
        assertTrue(box.verify(t1))
        val t2 = box.rotate()
        assertTrue(box.verify(t2))
        assertFalse(box.verify(t1))
        assertEquals(43, t2.length)
    }

    @Test
    fun `rate limiter enforces the per-window budget`() {
        var now = 0L
        val rl = RateLimiter(clock = { now }, windowMs = 60_000, maxRequests = 3)
        assertTrue(rl.allow("1.2.3.4"))
        assertTrue(rl.allow("1.2.3.4"))
        assertTrue(rl.allow("1.2.3.4"))
        assertFalse(rl.allow("1.2.3.4"))
        // other IPs unaffected
        assertTrue(rl.allow("5.6.7.8"))
        // window passes, budget resets
        now = 61_000
        assertTrue(rl.allow("1.2.3.4"))
    }

    @Test
    fun `auth guard locks out after repeated failures and unlocks on success`() {
        var now = 0L
        val g = AuthGuard(clock = { now }, maxFailures = 3, failureWindowMs = 60_000, lockoutMs = 120_000)
        g.recordFailure("ip")
        g.recordFailure("ip")
        assertFalse(g.isLocked("ip"))
        g.recordFailure("ip")
        assertTrue(g.isLocked("ip"))
        // lockout expires
        now = 200_000
        assertFalse(g.isLocked("ip"))
        // success clears the counter
        g.recordFailure("ip2")
        g.recordSuccess("ip2")
        g.recordFailure("ip2")
        assertFalse(g.isLocked("ip2"))
    }
}
