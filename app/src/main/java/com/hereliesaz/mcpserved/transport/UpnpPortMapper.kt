package com.hereliesaz.mcpserved.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitlet.weupnp.GatewayDevice
import org.bitlet.weupnp.GatewayDiscover

/**
 * Best-effort UPnP IGD port mapping, so the phone can become directly
 * reachable from the public internet with no relay and no third party —
 * standard practice on Android (torrent clients, DLNA/media servers, and
 * game networking libraries all do exactly this) for the common case of a
 * home Wi-Fi network whose router supports it.
 *
 * This maps [LocalServer]'s port — the already end-to-end encrypted
 * sealed-frame protocol — and is never offered for [McpServer]'s plaintext-
 * behind-a-bearer-token port, for the same reason the relay never is (see
 * `docs/guide/security.md`). Once a mapping is live, that port is reachable
 * by literally anyone who finds it (port scanning the open internet is
 * routine), so only a protocol that is already encrypted end-to-end, with
 * an authorization boundary that doesn't depend on the network being
 * trusted, is safe to expose this way.
 *
 * Not a VPN, and nothing here needs to be: the payload is already sealed, so
 * there is no confidentiality gap for a VPN tunnel to close. What UPnP adds
 * is reachability, nothing else — and what it does *not* solve is a stable
 * address: the external IP can change (home DHCP churn) and the external
 * port can move on lease renewal, so [Mapping] is provisional by nature, not
 * a fact to cache past the next [mapPort] call.
 */
class UpnpPortMapper(private val internalPort: Int = LocalServer.DEFAULT_PORT) {

    data class Mapping(val externalAddress: String, val externalPort: Int)

    @Volatile
    private var gateway: GatewayDevice? = null

    /**
     * Discovers a UPnP IGD-capable router on the LAN and requests a mapping
     * from an external port to [internalPort] on this device.
     *
     * Returns null on any failure — no router found, no UPnP support on this
     * network, the router refused the mapping — since every one of those is
     * an ordinary outcome (cellular has no router to ask at all; many home
     * routers ship with UPnP disabled), not an exceptional one worth a
     * thrown exception the caller has to specifically handle.
     */
    suspend fun mapPort(): Mapping? = withContext(Dispatchers.IO) {
        runCatching {
            val discovery = GatewayDiscover()
            discovery.discover()
            val gw = discovery.validGateway ?: return@withContext null
            gateway = gw

            val localAddress = gw.localAddress ?: return@withContext null
            val externalAddress = gw.externalIPAddress
            if (externalAddress.isNullOrBlank()) return@withContext null

            val mapped = gw.addPortMapping(
                internalPort,
                internalPort,
                localAddress.hostAddress,
                "TCP",
                "mcpserved",
            )
            if (!mapped) return@withContext null

            Mapping(externalAddress, internalPort)
        }.onFailure { Log.w(TAG, "UPnP port mapping failed", it) }.getOrNull()
    }

    /** Removes the mapping. Safe to call even when [mapPort] never succeeded. */
    suspend fun unmapPort() {
        withContext(Dispatchers.IO) {
            runCatching { gateway?.deletePortMapping(internalPort, "TCP") }
        }
        gateway = null
    }

    private companion object {
        const val TAG = "UpnpPortMapper"
    }
}
