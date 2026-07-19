package com.hereliesaz.mcpserved.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the service after a reboot, but only if it was armed beforehand.
 *
 * Arming is a deliberate act and should survive a restart; a device that came
 * back from a reboot silently listening for a session the user had switched off
 * would be the exact failure this whole design is arranged against.
 *
 * On an unrooted device the Shizuku binding does not survive the reboot either
 * way, so the service comes back with a narrower capability set until the user
 * re-pairs. That is reported honestly through the capability query rather than
 * discovered through a tool call that fails.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("service", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ARMED, false)) return

        context.startForegroundService(Intent(context, ControlService::class.java))
    }

    companion object {
        const val KEY_ARMED = "armed"
    }
}
