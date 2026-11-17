package dev.lc.console

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Outcome of one command execution. */
class CommandResult(
    /** Exit code when the process exited on its own; null otherwise. */
    val exitCode: Int?,
    /** completed | timeout | cancelled | truncated | spawn-error */
    val status: String,
    val durationMs: Long,
    val truncated: Boolean,
    val spawnError: String?
)

/**
 * Runs one command as an argument vector, streaming stdout/stderr chunks to
 * [onOut]/[onErr], with a hard timeout and an output cap. LC only ever runs
 * commands as its own Android UID: no root, no su, no helper binaries.
 *
 * Injected [clock]/[sleep] hooks make the class fully unit-testable on the JVM.
 */
class CommandRunner(
    private val argv: List<String>,
    private val workingDir: File?,
    private val timeoutMs: Long,
    private val outputCapBytes: Long,
    private val onOut: (String) -> Unit = {},
    private val onErr: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun run(): CommandResult {
        val startedAt = clock()
        val proc = try {
            ProcessBuilder(argv)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            return CommandResult(
                null, "spawn-error", clock() - startedAt, false,
                e.message ?: "failed to start ${argv.firstOrNull() ?: ""}"
            )
        }

        var truncated = false
        val produced = java.util.concurrent.atomic.AtomicLong(0)
        val readerDone = CountDownLatch(2)

        fun reader(isErr: Boolean, stream: java.io.InputStream) = thread(isDaemon = true) {
            val buf = CharArray(4096)
            try {
                InputStreamReader(stream, Charsets.UTF_8).use { r ->
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        val s = String(buf, 0, n)
                        if (produced.get() >= outputCapBytes) {
                            truncated = true
                            break
                        }
                        produced.addAndGet(s.toByteArray(Charsets.UTF_8).size.toLong())
                        if (isErr) onErr(s) else onOut(s)
                    }
                }
            } catch (_: Exception) {
                // stream torn down while the process is being killed
            } finally {
                readerDone.countDown()
            }
        }

        reader(false, proc.inputStream)
        reader(true, proc.errorStream)

        val deadline = startedAt + timeoutMs
        var status = "completed"
        try {
            while (true) {
                if (!proc.isAlive) break
                if (truncated) {
                    status = "truncated"
                    proc.destroyForcibly()
                    break
                }
                if (cancelled) {
                    status = "cancelled"
                    proc.destroyForcibly()
                    break
                }
                if (clock() >= deadline) {
                    status = "timeout"
                    proc.destroyForcibly()
                    break
                }
                sleep(50)
            }
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            proc.destroyForcibly()
        }

        // Drain remaining output (readers stop at EOF or at the cap).
        readerDone.await(5, TimeUnit.SECONDS)

        val exit = if (status == "completed") {
            try { proc.exitValue() } catch (_: IllegalThreadStateException) { null }
        } else null

        val duration = clock() - startedAt
        // When the cap cut a run short the process was killed on purpose, so the
        // status is "truncated" even if the shell exited first.
        val finalStatus = if (truncated && status == "completed") "truncated" else status
        return CommandResult(exit, finalStatus, duration, truncated, null)
    }
}
