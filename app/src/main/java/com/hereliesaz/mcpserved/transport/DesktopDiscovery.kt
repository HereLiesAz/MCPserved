package com.hereliesaz.mcpserved.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Browses the LAN for MCPserved desktops, so the phone can see them the same way
 * the desktop sees the phone.
 *
 * Discovery is mutual. The phone advertises its control service (see
 * [LanAdvertiser]) so a desktop can find and dial it; this is the other half —
 * the desktop advertises itself, and this lists every one on the network. It is
 * informational on the phone, which is the server rather than the client, but it
 * turns "is my desktop even seeing me?" from a guess into something visible.
 */
class DesktopDiscovery(context: Context) {

    companion object {
        private const val TAG = "DesktopDiscovery"
        const val SERVICE_TYPE = "_mcpserved-desktop._tcp."
    }

    /** A desktop found on the network. */
    data class Desktop(val name: String, val host: String, val port: Int)

    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val found = ConcurrentHashMap<String, Desktop>()

    private val _desktops = MutableStateFlow<List<Desktop>>(emptyList())
    val desktops: StateFlow<List<Desktop>> = _desktops

    private var listener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        // Local non-null handle: a nullable member does not smart-cast inside the
        // runCatching lambda below.
        val manager = nsd ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("mcpserved-desktop")) resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) publish()
            }
        }
        listener = l
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discoverServices threw: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        listener = null
        found.clear()
        publish()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        // resolveService is deprecated on API 34+ in favour of the callback API,
        // but is the only route on this app's minSdk and works fine. A failed
        // resolve simply drops that one entry rather than crashing.
        // Older platforms allow only one resolve at a time and throw
        // IllegalArgumentException ("listener already in use") on overlap; swallow
        // it — the next discovery tick re-finds the service and resolves again.
        runCatching {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    found[resolved.serviceName] = Desktop(resolved.serviceName, host, resolved.port)
                    publish()
                }
            })
        }
    }

    private fun publish() {
        _desktops.value = found.values.sortedBy { it.name }
    }
}
