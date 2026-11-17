package dev.lc.console

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opt-in boot start. The manifest keeps this receiver disabled; the app turns
 * the component on only while the "Start on boot" setting is enabled, so the
 * receiver is registered exactly when the user asked for it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.disclosed || !prefs.startOnBoot) return
        ConsoleService.start(context)
    }
}
