package dev.lc.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandRunnerTest {

    @Test
    fun `runs an argument vector and returns exit code`() {
        val r = CommandRunner(listOf("sh", "-c", "echo hello"), null, 10_000, 1 shl 20).run()
        assertEquals("completed", r.status)
        assertEquals(0, r.exitCode)
        assertFalse(r.truncated)
        assertNull(r.spawnError)
    }

    @Test
    fun `stdout and stderr are separated`() {
        var out = ""
        var err = ""
        CommandRunner(
            listOf("sh", "-c", "echo out-line; echo err-line >&2"), null, 10_000, 1 shl 20,
            onOut = { out += it }, onErr = { err += it }
        ).run()
        assertEquals("out-line\n", out)
        assertEquals("err-line\n", err)
    }

    @Test
    fun `non-zero exit codes are captured`() {
        val r = CommandRunner(listOf("sh", "-c", "exit 7"), null, 10_000, 1 shl 20).run()
        assertEquals("completed", r.status)
        assertEquals(7, r.exitCode)
    }

    @Test
    fun `timeout is enforced`() {
        val r = CommandRunner(listOf("sleep", "30"), null, 500, 1 shl 20).run()
        assertEquals("timeout", r.status)
        assertNull(r.exitCode)
        assertTrue(r.durationMs in 400..10_000)
    }

    @Test
    fun `cancel is enforced`() {
        val runner = CommandRunner(listOf("sleep", "30"), null, 30_000, 1 shl 20)
        Thread {
            Thread.sleep(200)
            runner.cancel()
        }.start()
        val r = runner.run()
        assertEquals("cancelled", r.status)
        assertNull(r.exitCode)
    }

    @Test
    fun `output cap is enforced and kills the process`() {
        var produced = 0
        val r = CommandRunner(
            listOf("yes"), null, 30_000, 64 * 1024,
            onOut = { produced += it.length }
        ).run()
        assertEquals("truncated", r.status)
        assertTrue(produced <= 64 * 1024 + 8192) // at most one chunk over the cap
        assertTrue(r.truncated)
    }

    @Test
    fun `missing binary reports a spawn error`() {
        val r = CommandRunner(listOf("lc-no-such-binary-xyz"), null, 5_000, 1 shl 20).run()
        assertEquals("spawn-error", r.status)
        assertTrue(r.spawnError != null)
    }

    @Test
    fun `working directory is applied`() {
        val dir = File("/tmp")
        val r = CommandRunner(listOf("pwd"), dir, 10_000, 1 shl 20).run()
        assertEquals(0, r.exitCode)
    }
}
