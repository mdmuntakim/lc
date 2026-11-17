package dev.lc.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionAuditTest {

    @Test
    fun `sessions expire after the ttl`() {
        var now = 0L
        val sm = SessionManager(clock = { now }, ttlMs = 1000)
        val sid = sm.create()
        assertTrue(sm.validate(sid))
        now = 500
        assertTrue(sm.validate(sid))
        now = 1600
        assertFalse(sm.validate(sid))
        assertFalse(sm.validate("bogus"))
        assertFalse(sm.validate(null))
    }

    @Test
    fun `invalidate and clear work`() {
        val sm = SessionManager()
        val sid = sm.create()
        assertTrue(sm.validate(sid))
        sm.invalidate(sid)
        assertFalse(sm.validate(sid))
        val other = sm.create()
        sm.clear()
        assertFalse(sm.validate(other))
    }

    @Test
    fun `csrf token is derived from the session and stable`() {
        val a = Sessions.csrfToken("session-1")
        val b = Sessions.csrfToken("session-1")
        val c = Sessions.csrfToken("session-2")
        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(32, a.length)
    }

    @Test
    fun `audit log records and clears`() {
        val f = File.createTempFile("audit", ".jsonl")
        try {
            val log = AuditLog(f)
            log.record("echo hi", listOf("echo", "hi"), "allowlist", "completed", 0, 12, "hi\n")
            log.record("su", listOf("su"), "blocked", "blocked", null, 0, "never allowed")
            val entries = log.entries()
            assertEquals(2, entries.size)
            // newest first
            assertEquals("blocked", entries[0].optString("status"))
            assertEquals("echo hi", entries[1].optString("command"))
            assertEquals(0, entries[1].optInt("exit"))
            assertEquals(12, entries[1].optLong("duration_ms"))
            log.clear()
            assertEquals(0, log.entries().size)
            assertFalse(f.exists())
        } finally {
            f.delete()
        }
    }

    @Test
    fun `audit preview is truncated`() {
        val f = File.createTempFile("audit", ".jsonl")
        try {
            val log = AuditLog(f)
            log.record("yes", listOf("yes"), "advanced", "truncated", null, 1,
                "y".repeat(20_000))
            val e = log.entries()[0]
            val preview = e.optString("output_preview")
            assertEquals(AuditLog.PREVIEW_CHARS + 1, preview.length)
            assertTrue(preview.endsWith("…"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `history dedupes, caps and clears`() {
        val f = File.createTempFile("history", ".jsonl")
        try {
            val h = HistoryStore(f, cap = 3)
            h.add("ls")
            h.add("df -h")
            h.add("ls")
            h.add("uptime")
            h.add("free")
            val items = h.entries()
            // dedupe: second "ls" moves to the end; cap 3 evicts "df -h"
            assertEquals(listOf("free", "uptime", "ls"), items)
            h.clear()
            assertEquals(0, h.entries().size)
        } finally {
            f.delete()
        }
    }
}
