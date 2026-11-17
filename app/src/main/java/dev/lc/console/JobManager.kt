package dev.lc.console

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One executed command: event log for SSE subscribers plus final result. */
class Job(
    val id: String,
    val command: String,
    val argv: List<String>,
    val mode: String
) {
    private val lock = Any()
    private val events = ArrayList<JSONObject>()
    private var seq = 0

    /** Notified on every emitted event; SSE streams wait on this. */
    val monitor = java.lang.Object()

    val subscribers = CopyOnWriteArrayList<(JSONObject) -> Unit>()

    @Volatile
    var runner: CommandRunner? = null

    @Volatile
    var status: String = "running"

    @Volatile
    var result: JSONObject? = null

    val preview = StringBuilder()

    val startedAtMs: Long = System.currentTimeMillis()

    fun emit(type: String, data: JSONObject) {
        val event: JSONObject
        synchronized(lock) {
            event = JSONObject().put("seq", ++seq).put("type", type).put("data", data)
            events.add(event)
        }
        for (s in subscribers) {
            try { s(event) } catch (_: Exception) { /* subscriber gone */ }
        }
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun appendPreview(text: String) {
        synchronized(preview) {
            if (preview.length < AuditLog.PREVIEW_CHARS) preview.append(text)
        }
    }

    fun previewText(): String = synchronized(preview) { preview.toString() }

    fun eventsSince(afterSeq: Int): List<JSONObject> = synchronized(lock) {
        events.filter { it.optInt("seq") > afterSeq }
    }

    fun lastSeq(): Int = synchronized(lock) { seq }

    fun subscriberDied(listener: (JSONObject) -> Unit) {
        subscribers.remove(listener)
    }
}

/**
 * Owns running and recently finished jobs. Enforces the allowlist (or advanced
 * mode), the timeout and the output cap, records history and the audit log.
 */
class JobManager(
    private val audit: AuditLog,
    private val history: HistoryStore,
    private val config: Config,
    private val outputCapBytes: Long = DEFAULT_OUTPUT_CAP_BYTES,
    private val maxJobs: Int = 20
) {

    interface Config {
        fun advanced(): Boolean
        fun timeoutSec(): Int
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val lock = Any()
    private val ids = AtomicLong(0)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "lc-exec").apply { isDaemon = true }
    }

    /** Returns (job, "") on success, or (null, reason) when the command is blocked. */
    fun start(command: String): Pair<Job?, String> {
        val argv = Allowlist.tokenize(command) ?: run {
            recordBlocked(command, "malformed quoting")
            return null to "malformed quoting"
        }
        var mode = "advanced"
        if (!config.advanced()) {
            val reason = Allowlist.check(argv)
            if (reason != null) {
                recordBlocked(command, reason)
                return null to reason
            }
            mode = "allowlist"
        } else {
            // Advanced mode lifts the allowlist, never the no-root rule.
            val reason = Allowlist.checkForbidden(argv)
            if (reason != null) {
                recordBlocked(command, reason)
                return null to reason
            }
        }

        val job = Job(
            id = "j${ids.incrementAndGet()}",
            command = command,
            argv = argv,
            mode = mode
        )
        synchronized(lock) {
            jobs[job.id] = job
            while (jobs.size > maxJobs) {
                val oldest = jobs.entries.firstOrNull { it.value.status != "running" }
                if (oldest == null) break
                jobs.remove(oldest.key)
            }
        }
        history.add(command)

        val timeoutSec = config.timeoutSec()
        executor.submit {
            val runner = CommandRunner(
                argv = argv,
                workingDir = null,
                timeoutMs = timeoutSec * 1000L,
                outputCapBytes = outputCapBytes,
                onOut = {
                    job.appendPreview(it)
                    job.emit("out", JSONObject().put("text", it))
                },
                onErr = {
                    job.appendPreview(it)
                    job.emit("err", JSONObject().put("text", it))
                }
            )
            job.runner = runner
            val result = runner.run()
            val resultJson = JSONObject()
                .put("status", result.status)
                .put("exit", result.exitCode ?: JSONObject.NULL)
                .put("duration_ms", result.durationMs)
                .put("truncated", result.truncated)
                .put(
                    "spawn_error",
                    result.spawnError ?: JSONObject.NULL
                )
            job.status = result.status
            job.result = resultJson
            job.emit("done", resultJson)
            audit.record(
                command = command,
                argv = argv,
                mode = mode,
                status = result.status,
                exit = result.exitCode,
                durationMs = result.durationMs,
                outputPreview = job.previewText()
            )
        }
        return job to ""
    }

    private fun recordBlocked(command: String, reason: String) {
        audit.record(
            command = command,
            argv = Allowlist.tokenize(command) ?: listOf(command),
            mode = "blocked",
            status = "blocked",
            exit = null,
            durationMs = 0,
            outputPreview = reason
        )
    }

    fun get(id: String): Job? = synchronized(lock) { jobs[id] }

    fun cancel(id: String): Boolean {
        val job = get(id) ?: return false
        job.runner?.cancel()
        return true
    }

    fun cancelAll() {
        synchronized(lock) { jobs.values.toList() }.forEach { it.runner?.cancel() }
    }

    companion object {
        /** Output cap: 1 MiB per command, enforced by [CommandRunner]. */
        const val DEFAULT_OUTPUT_CAP_BYTES: Long = 1024L * 1024
    }
}
