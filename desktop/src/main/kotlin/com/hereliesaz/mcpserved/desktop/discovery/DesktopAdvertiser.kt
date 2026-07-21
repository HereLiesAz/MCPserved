package com.hereliesaz.mcpserved.desktop.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises this desktop on the LAN so the phone can find it too.
 *
 * Discovery is mutual: the phone advertises its control service (so the desktop
 * can dial it), and the desktop advertises itself here (so the phone's app can
 * show which desktops are on the network and looking). This side is informational
 * — the desktop is the client, the phone the server — but seeing each other is
 * what makes the pair feel like one system rather than two things that have to be
 * wired together by hand.
 */
class DesktopAdvertiser {

    companion object {
        const val SERVICE_TYPE = "_mcpserved-desktop._tcp.local."
        // The desktop accepts no inbound control connection; the port is nominal,
        // present only because DNS-SD records carry one. The phone reads the name.
        private const val NOMINAL_PORT = 8790
    }

    private var jmdns: JmDNS? = null

    @Synchronized
    fun start() {
        if (jmdns != null) return
        runCatching {
            val md = JmDNS.create(bindAddress())
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "desktop"
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                "MCPserved on $host",
                NOMINAL_PORT,
                0,
                0,
                mapOf("role" to "desktop", "host" to host),
            )
            md.registerService(info)
            jmdns = md
        }
    }

    @Synchronized
    fun stop() {
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }
        jmdns = null
    }

    /** Bind on the real LAN interface, not loopback, so the record is reachable. */
    private fun bindAddress(): InetAddress? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it is java.net.Inet4Address }
            ?: InetAddress.getLocalHost()
    }.getOrNull()
}
