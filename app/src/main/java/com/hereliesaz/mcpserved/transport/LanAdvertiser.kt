package com.hereliesaz.mcpserved.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Advertises the control server on the local network over mDNS / DNS-SD.
 *
 * Once the service is armed and paired, the phone broadcasts a `_mcpserved._tcp`
 * record carrying its device id and control port. The desktop app browses for it
 * and dials the advertised address directly, so the two find each other over
 * Wi-Fi with no cable, no `adb`, and no hand-typed `ip:port`.
 *
 * Discovery reveals only an address. It is not an authorization boundary and is
 * not meant to be one: [LocalServer] answers a connection only when it can open
 * frames sealed under the pairing secret, so being visible on the network is a
 * long way from being reachable by anything that is not the paired desktop. The
 * advertisement is withdrawn the moment the service is disarmed or unpaired.
 */
class LanAdvertiser(
    private val context: Context,
    private val deviceId: String,
    private val port: Int = LocalServer.DEFAULT_PORT,
) {

    private val nsd: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registered = false

    /** Registers the service. Idempotent; a second call while registered is a no-op. */
    @Synchronized
    fun start() {
        if (registered || nsd == null) return

        val info = NsdServiceInfo().apply {
            // A human-readable name so a person picking from the desktop's device
            // list sees "MCPserved <model>", not a raw UUID. The daemon may append
            // a suffix on collision; the TXT id below is the stable identity.
            serviceName = "MCPserved ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = this@LanAdvertiser.port
            setAttribute(TXT_DEVICE_ID, deviceId)
            setAttribute(TXT_PROTO, PROTO_VERSION.toString())
        }

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising ${info.serviceName} on $SERVICE_TYPE:$port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Roll back the optimistic flag so a later start() can retry.
                synchronized(this@LanAdvertiser) { if (listener === this) { registered = false; listener = null } }
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        // Set registered before the call so a second start() that races the
        // async onServiceRegistered callback is a no-op rather than a double
        // register (which the daemon would reject).
        listener = l
        registered = true
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure {
                registered = false
                listener = null
                Log.w(TAG, "registerService threw: ${it.message}")
            }
    }

    /** Withdraws the advertisement. Safe to call when not registered. */
    @Synchronized
    fun stop() {
        val l = listener ?: return
        runCatching { nsd?.unregisterService(l) }
        listener = null
        registered = false
    }

    private companion object {
        const val TAG = "LanAdvertiser"
        const val SERVICE_TYPE = "_mcpserved._tcp"
        const val TXT_DEVICE_ID = "id"
        const val TXT_PROTO = "proto"
        const val PROTO_VERSION = 2
    }
}
