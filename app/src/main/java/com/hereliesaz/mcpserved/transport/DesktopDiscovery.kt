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
 *
 * Resolution is serialized. Before API 34 `NsdManager` allows only one
 * `resolveService` in flight and rejects a second with "listener already in use";
 * resolving concurrently would silently drop every desktop after the first, since
 * `onServiceFound` fires only once per service and there is no retry. So found
 * services queue and resolve one at a time.
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

    private val lock = Any()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val manager = nsd ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("mcpserved-desktop")) enqueue(info)
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
        synchronized(lock) {
            pending.clear()
            resolving = false
        }
        found.clear()
        publish()
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(lock) { pending.addLast(info) }
        pump()
    }

    /** Resolve one queued service; the next starts only when this one finishes. */
    private fun pump() {
        val next: NsdServiceInfo = synchronized(lock) {
            if (resolving) return
            val n = pending.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        val manager = nsd
        if (manager == null) {
            synchronized(lock) { resolving = false }
            return
        }

        val advance = {
            synchronized(lock) { resolving = false }
            pump()
        }

        @Suppress("DEPRECATION")
        val ok = runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = advance()

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolved.host?.hostAddress?.let { host ->
                        found[resolved.serviceName] = Desktop(resolved.serviceName, host, resolved.port)
                        publish()
                    }
                    advance()
                }
            })
        }.isSuccess
        // If the resolve call itself threw, free the slot so the queue keeps moving.
        if (!ok) advance()
    }

    private fun publish() {
        _desktops.value = found.values.sortedBy { it.name }
    }
}
