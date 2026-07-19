package com.hereliesaz.mcpserved.service

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the wake signal that precedes a session.
 *
 * A persistent socket is best-effort. Doze, OEM battery managers, and cell
 * handoffs all kill it, and none of them notify the app. Firebase is the only
 * delivery path the platform guarantees will reach a sleeping device, so it
 * carries the signal to redial — never any content.
 *
 * That distinction is the whole design. Google sits in the wake path and learns
 * that a device was asked to connect; it never sees a request, a response, or a
 * key, because none of those travel this way.
 *
 * Cold path costs two to five seconds. That is the price of the phone in your
 * pocket answering at all, which was the entire reason for choosing a relay over
 * a local socket.
 */
class WakeService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != TYPE_WAKE) return

        // Start the service if it is not resident, then force a redial. A socket
        // the platform killed still reads as open from this side, so the client
        // closes and reopens rather than testing it.
        val intent = Intent(this, ControlService::class.java).setAction(ACTION_WAKE)
        startForegroundService(intent)
        ControlService.instance?.relay?.wake()
    }

    /**
     * Registers a rotated token with the relay.
     *
     * The relay stores the token against the device id so the MCP server can ask
     * for a wake without ever handling it. Registration is fire-and-forget: a
     * failed upload costs one slow session start, not a broken one, because the
     * client redials on its own schedule regardless.
     */
    override fun onNewToken(token: String) {
        ControlService.instance?.registerWakeToken(token)
    }

    companion object {
        const val TYPE_WAKE = "wake"
        const val ACTION_WAKE = "com.hereliesaz.mcpserved.WAKE"
    }
}
