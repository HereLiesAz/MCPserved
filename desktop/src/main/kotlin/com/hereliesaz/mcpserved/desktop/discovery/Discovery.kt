package com.hereliesaz.mcpserved.desktop.discovery

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** A device the phone advertised on the LAN over mDNS / DNS-SD. */
data class DiscoveredDevice(
    val deviceId: String,
    val host: String,
    val port: Int,
    val name: String,
)

/**
 * Browses the local network for MCPserved devices.
 *
 * The Android app registers a `_mcpserved._tcp` service through `NsdManager` the
 * moment its control server is armed, carrying its device id and control port in
 * the TXT record. This side listens for that broadcast and connects straight to
 * the advertised address — no `adb`, no cable, no manual ip:port. The pairing
 * secret still authenticates the connection, so being discoverable is not the
 * same as being reachable by anything unpaired.
 *
 * Discovery is a convenience for finding the address, never an authority: a
 * device that appears here is still refused unless it holds the paired key.
 */
class DeviceDiscovery {

    companion object {
        const val SERVICE_TYPE = "_mcpserved._tcp.local."
        const val TXT_DEVICE_ID = "id"
    }

    private var jmdns: JmDNS? = null
    private var listener: ServiceListener? = null
    private val devices = ConcurrentHashMap<String, DiscoveredDevice>()

    @Volatile
    private var onChange: (() -> Unit)? = null

    val isRunning: Boolean get() = jmdns != null

    /** Starts browsing. [onChange] fires whenever the device set changes. */
    @Synchronized
    fun start(onChange: () -> Unit) {
        if (jmdns != null) return
        this.onChange = onChange
        val md = JmDNS.create(bindAddress())
        val l = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Resolution is asynchronous; ask for the details explicitly.
                md.requestServiceInfo(event.type, event.name, 1_500)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                if (devices.remove(event.name) != null) this@DeviceDiscovery.onChange?.invoke()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                toDevice(event.name, info)?.let {
                    devices[event.name] = it
                    this@DeviceDiscovery.onChange?.invoke()
                }
            }
        }
        md.addServiceListener(SERVICE_TYPE, l)
        jmdns = md
        listener = l
    }

    @Synchronized
    fun stop() {
        listener?.let { jmdns?.removeServiceListener(SERVICE_TYPE, it) }
        runCatching { jmdns?.close() }
        jmdns = null
        listener = null
        devices.clear()
        onChange = null
    }

    fun snapshot(): List<DiscoveredDevice> = devices.values.sortedBy { it.name }

    private fun toDevice(name: String, info: ServiceInfo): DiscoveredDevice? {
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.hostAddresses.firstOrNull()
            ?: return null
        val port = info.port
        if (port <= 0) return null
        val deviceId = info.getPropertyString(TXT_DEVICE_ID) ?: name.removeSuffix(".$SERVICE_TYPE")
        val display = name.substringBefore(".$SERVICE_TYPE").ifBlank { deviceId }
        return DiscoveredDevice(deviceId = deviceId, host = host, port = port, name = display)
    }

    /**
     * Picks an address to bind the responder to.
     *
     * A site-local address keeps discovery on the real LAN interface rather than
     * loopback, where nothing would ever answer. Falls back to the default local
     * host when no site-local address is found.
     */
    private fun bindAddress(): InetAddress? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it is java.net.Inet4Address }
            ?: InetAddress.getLocalHost()
    }.getOrNull()
}
