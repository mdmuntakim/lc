package dev.lc.console

import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Local audit log: one JSON object per line in an app-private file.
 * Records timestamp, command, mode, exit code/status, duration and a truncated
 * output preview. Viewable and clearable in the app and on the web console.
 * Nothing is ever sent off the device.
 */
class AuditLog(private val file: File) {

    private val lock = Any()

    fun record(
        command: String,
        argv: List<String>,
        mode: String,
        status: String,
        exit: Int?,
        durationMs: Long,
        outputPreview: String
    ) {
        val entry = JSONObject()
            .put("ts", Instant.now().toString())
            .put("command", command)
            .put("argv", org.json.JSONArray(argv))
            .put("mode", mode)
            .put("status", status)
            .put("exit", exit ?: JSONObject.NULL)
            .put("duration_ms", durationMs)
            .put(
                "output_preview",
                if (outputPreview.length > PREVIEW_CHARS) {
                    outputPreview.substring(0, PREVIEW_CHARS) + "…"
                } else outputPreview
            )
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                file.appendText(entry.toString() + "\n")
            } catch (_: Exception) {
                // audit must never break command handling
            }
        }
    }

    /** Newest-first list of at most [limit] entries. */
    fun entries(limit: Int = 200): List<JSONObject> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return emptyList()
        }
        lines.filter { it.isNotBlank() }.takeLast(limit)
            .mapNotNull { line ->
                try { JSONObject(line) } catch (_: Exception) { null }
            }
            .asReversed()
    }

    fun clear() = synchronized(lock) {
        try { file.delete() } catch (_: Exception) {}
    }

    companion object {
        const val PREVIEW_CHARS = 4096
    }
}

/**
 * Console command history, newest first, capped. Also an app-private JSONL
 * file so it clears with the app data.
 */
class HistoryStore(private val file: File, private val cap: Int = 100) {

    private val lock = Any()

    fun add(command: String) = synchronized(lock) {
        if (command.isBlank()) return
        try {
            file.parentFile?.mkdirs()
            val existing = if (file.exists()) {
                file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                    try { JSONObject(line).optString("c").takeIf { s -> s.isNotEmpty() } }
                    catch (_: Exception) { null }
                }
            } else emptyList()
            val next = (existing.filter { it != command } + command).takeLast(cap)
            file.writeText(next.joinToString("\n") { JSONObject().put("c", it).toString() } + "\n")
        } catch (_: Exception) {
        }
    }

    fun entries(limit: Int = 50): List<String> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            file.readLines().filter { it.isNotBlank() }.takeLast(limit)
                .mapNotNull { line ->
                    try { JSONObject(line).optString("c").takeIf { it.isNotEmpty() } }
                    catch (_: Exception) { null }
                }
                .asReversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear() = synchronized(lock) { try { file.delete() } catch (_: Exception) {} }
}
