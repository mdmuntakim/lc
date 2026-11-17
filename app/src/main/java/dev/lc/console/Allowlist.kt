package dev.lc.console

/**
 * Read-only diagnostic allowlist enforced unless advanced mode is on.
 *
 * Commands are always executed as an argument vector (no shell is ever spawned
 * by LC itself). `ping` must carry `-c` and `top` must carry `-n` so a stray
 * click cannot start an endless stream; in advanced mode the owner can run
 * anything (still as the app UID, never root) — including an explicit
 * `sh -c '...'` if a shell is really wanted.
 */
object Allowlist {

    /** First-token allowlist: the commands the console accepts by default. */
    val ALLOWED = setOf(
        "ls", "df", "du", "free", "uptime", "id", "uname", "getprop",
        "ps", "top", "logcat", "ip", "date", "ping"
    )

    /** Never runnable, in any mode. Root execution is out of scope for LC. */
    private val FORBIDDEN = setOf("su", "sudo", "setuid", "doas", "mount", "remount")

    class Blocked(val reason: String)

    /**
     * Splits [input] into an argument vector, honouring single and double
     * quotes. Returns null when quoting is malformed.
     */
    fun tokenize(input: String): List<String>? {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        var started = false
        while (i < input.length) {
            val c = input[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                    if (started) {
                        out.add(cur.toString())
                        cur.setLength(0)
                        started = false
                    }
                }
                c == '\'' -> {
                    started = true
                    val end = input.indexOf('\'', i + 1)
                    if (end < 0) return null
                    cur.append(input, i + 1, end)
                    i = end
                }
                c == '"' -> {
                    started = true
                    i++
                    var closed = false
                    while (i < input.length) {
                        val d = input[i]
                        if (d == '"' && (i == 0 || input[i - 1] != '\\')) {
                            closed = true
                            break
                        }
                        if (d == '\\' && i + 1 < input.length &&
                            (input[i + 1] == '"' || input[i + 1] == '\\')
                        ) {
                            cur.append(input[i + 1])
                            i += 2
                            continue
                        }
                        cur.append(d)
                        i++
                    }
                    if (!closed) return null
                }
                c == '\\' && i + 1 < input.length -> {
                    started = true
                    cur.append(input[i + 1])
                    i++
                }
                c == '\u0000' -> return null
                else -> {
                    started = true
                    cur.append(c)
                }
            }
            i++
        }
        if (started) out.add(cur.toString())
        return out
    }

    /**
     * The no-root rule, applied in every mode (allowlist check already covers
     * it, but advanced mode needs it standalone).
     */
    fun checkForbidden(argv: List<String>): String? {
        if (argv.isEmpty()) return "empty command"
        val head = argv[0]
        if (head in FORBIDDEN) return "'$head' is never allowed (LC never uses root)"
        if (argv.drop(1).any { it in FORBIDDEN || it.endsWith("/su") }) {
            return "'su' may not appear in arguments"
        }
        return null
    }

    /** Throws nothing: returns null when the command may run, a reason otherwise. */
    fun check(argv: List<String>): String? {
        if (argv.isEmpty()) return "empty command"
        if (argv.any { it.isEmpty() }) return "empty argument"
        val head = argv[0]
        if (head in FORBIDDEN) return "'$head' is never allowed (LC never uses root)"
        if (argv.drop(1).any { it in FORBIDDEN || it.endsWith("/su") }) {
            return "'su' may not appear in arguments"
        }
        if (head !in ALLOWED) return "'$head' is not in the read-only allowlist"
        if (head == "ping" && argv.none { it == "-c" }) return "ping requires -c"
        if (head == "top" && argv.none { it == "-n" }) return "top requires -n"
        return null
    }
}
