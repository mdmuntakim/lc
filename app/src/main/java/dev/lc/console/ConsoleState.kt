package dev.lc.console

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for what the console service is doing, observed by
 * the activity so the UI always shows the accurate state.
 */
object ConsoleState {

    enum class Phase { STOPPED, STARTING, RUNNING, ERROR }

    @Volatile
    var phase: Phase = Phase.STOPPED
        private set

    @Volatile
    var message: String = "Console stopped"
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(newPhase: Phase, newMessage: String) {
        phase = newPhase
        message = newMessage
        main.post { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
        l()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
