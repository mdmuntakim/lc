package dev.lc.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistTest {

    @Test
    fun `tokenize honours quotes`() {
        assertEquals(listOf("ls", "-la", "/sdcard"), Allowlist.tokenize("ls -la /sdcard"))
        assertEquals(listOf("sh", "-c", "echo a  b"), Allowlist.tokenize("sh -c 'echo a  b'"))
        assertEquals(listOf("getprop", "ro.build.version.release"),
            Allowlist.tokenize("getprop \"ro.build.version.release\""))
        assertEquals(listOf("echo", "a\"b"), Allowlist.tokenize("echo \"a\\\"b\""))
    }

    @Test
    fun `tokenize rejects malformed quoting`() {
        assertNull(Allowlist.tokenize("sh -c 'unterminated"))
        assertNull(Allowlist.tokenize("echo \"open"))
    }

    @Test
    fun `allowlisted command passes`() {
        assertNull(Allowlist.check(listOf("ls", "-l")))
        assertNull(Allowlist.check(listOf("df", "-h")))
        assertNull(Allowlist.check(listOf("uptime")))
        assertNull(Allowlist.check(listOf("ping", "-c", "1", "127.0.0.1")))
        assertNull(Allowlist.check(listOf("top", "-n", "1")))
    }

    @Test
    fun `non-allowlisted command is blocked`() {
        assertNotNull(Allowlist.check(listOf("touch", "/data/x")))
        assertNotNull(Allowlist.check(listOf("sh")))
        assertNotNull(Allowlist.check(listOf("cat", "/data/data/dev.lc.console/files/audit.jsonl")))
    }

    @Test
    fun `root commands are blocked in every mode`() {
        assertNotNull(Allowlist.check(listOf("su")))
        assertNotNull(Allowlist.checkForbidden(listOf("su")))
        assertNotNull(Allowlist.checkForbidden(listOf("ls", "/system/bin/su")))
        assertNull(Allowlist.checkForbidden(listOf("sh", "-c", "echo hi")))
    }

    @Test
    fun `ping and top need their limit flags`() {
        assertNotNull(Allowlist.check(listOf("ping", "127.0.0.1")))
        assertNotNull(Allowlist.check(listOf("top")))
        assertNull(Allowlist.check(listOf("ping", "-c", "1", "127.0.0.1")))
        assertNull(Allowlist.check(listOf("top", "-n", "1")))
    }

    @Test
    fun `empty command is blocked`() {
        assertNotNull(Allowlist.check(emptyList()))
        assertTrue(Allowlist.tokenize("   ").isNullOrEmpty() || Allowlist.tokenize("   ")!!.isEmpty())
        assertFalse(Allowlist.tokenize("")!!.isNotEmpty())
    }
}
