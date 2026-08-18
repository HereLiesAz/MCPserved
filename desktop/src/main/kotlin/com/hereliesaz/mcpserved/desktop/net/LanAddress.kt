package com.hereliesaz.mcpserved.desktop.net

import java.net.InetAddress

/**
 * The real LAN-facing address, not loopback — the interface another machine
 * on the network can actually dial. Shared by discovery, advertising, and
 * pairing, which all need the same answer to "which address am I reachable
 * at."
 */
object LanAddress {
    fun resolve(): InetAddress? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it is java.net.Inet4Address }
            ?: InetAddress.getLocalHost()
    }.getOrNull()
}
