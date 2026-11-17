package dev.lc.console

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Holds the pairing token that guards the console.
 *
 * The token is a random 256-bit base64url string. It lives in the app-private
 * preferences so the owner can display it again in the UI; the file is inside
 * the app sandbox and is removed on uninstall.
 *
 * Comparison is constant time. [TokenBox] is the context-free half used by the
 * HTTP layer (and by the JVM tests); the Android side mirrors it into prefs.
 */
object Tokens {

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Constant-time equality of two strings; length is not leaked either. */
    fun equal(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

/** Context-free token holder used by the HTTP API. Thread-safe. */
class TokenBox(initial: String?) {

    @Volatile
    var token: String? = initial

    fun set(newToken: String?) {
        token = newToken
    }

    fun verify(presented: String?): Boolean =
        Tokens.equal(token, presented)

    fun rotate(): String {
        val t = Tokens.generate()
        token = t
        return t
    }
}

/** Android-side persistence for the token kept in sync with a [TokenBox]. */
object TokenStore {

    private const val KEY = "pairingToken"

    fun load(context: Context): String? =
        context.getSharedPreferences("lc", Context.MODE_PRIVATE).getString(KEY, null)

    fun current(context: Context, box: TokenBox): String {
        val stored = load(context)
        if (stored != null) {
            box.token = stored
            return stored
        }
        val fresh = box.rotate()
        save(context, fresh)
        return fresh
    }

    fun rotate(context: Context, box: TokenBox): String {
        val fresh = box.rotate()
        save(context, fresh)
        return fresh
    }

    private fun save(context: Context, token: String) {
        context.getSharedPreferences("lc", Context.MODE_PRIVATE)
            .edit().putString(KEY, token).apply()
    }
}
