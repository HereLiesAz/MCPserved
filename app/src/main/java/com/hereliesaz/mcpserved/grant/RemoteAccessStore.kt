package com.hereliesaz.mcpserved.grant

import android.content.Context

/**
 * Settings for the four opt-in remote-access paths, all off by default.
 *
 * None of the four is a trust boundary of its own — see [Enforcer] for the
 * boundary that actually matters — but each widens who can *reach* the device,
 * which is worth a durable, explicit, one-bit-per-path record rather than
 * inferring intent from whatever happened to be configured last time the
 * service started.
 *
 * Plain (unencrypted) storage, like [ConsentStore]: these are toggles and a
 * relay's address, not secrets. The one secret this feature needs — the relay
 * room token — lives in [com.hereliesaz.mcpserved.crypto.RelayToken] instead,
 * behind the same `EncryptedSharedPreferences` [com.hereliesaz.mcpserved.crypto.McpToken]
 * uses.
 */
class RemoteAccessStore(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val prefs by lazy { appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * Whether [com.hereliesaz.mcpserved.transport.McpServer] binds every interface
     * instead of loopback only.
     *
     * Meant for a device already reachable only through a private, encrypted
     * mesh (Tailscale, WireGuard) that the operator installs and joins
     * separately — this device makes no attempt to detect or join one itself.
     * Widening the bind widens *who can knock*, not who can act: the bearer
     * token in [com.hereliesaz.mcpserved.crypto.McpToken] is still the only
     * thing that gets an answer. Turning this on outside a private mesh (a
     * hostile or shared LAN) hands anyone on that network a login-free shot at
     * guessing the token.
     */
    var wildcardMcpBind: Boolean
        get() = prefs.getBoolean(KEY_WILDCARD_BIND, false)
        set(value) = prefs.edit().putBoolean(KEY_WILDCARD_BIND, value).apply()

    /** The host [com.hereliesaz.mcpserved.transport.McpServer] should bind. */
    val mcpBindHost: String get() = if (wildcardMcpBind) BIND_ALL else BIND_LOOPBACK

    /**
     * Whether the device dials out to a relay so a host with no local network
     * path can still reach it.
     *
     * Unlike [wildcardMcpBind], the relay carries only the already end-to-end
     * encrypted sealed-frame protocol ([com.hereliesaz.mcpserved.transport.LocalServer]'s
     * protocol) — the relay operator, whoever runs it, forwards ciphertext it
     * cannot open. See `docs/guide/remote-access.md`.
     */
    var relayEnabled: Boolean
        get() = prefs.getBoolean(KEY_RELAY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RELAY_ENABLED, value).apply()

    /** `wss://` URL of the relay to dial. Blank until the operator sets one. */
    var relayUrl: String
        get() = prefs.getString(KEY_RELAY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RELAY_URL, value.trim()).apply()

    /**
     * Whether the device tries UPnP IGD port mapping so it can be reached
     * directly, with no relay and no third party.
     *
     * Like the relay, this reaches [com.hereliesaz.mcpserved.transport.LocalServer]'s
     * already end-to-end encrypted sealed-frame port — never
     * [com.hereliesaz.mcpserved.transport.McpServer]'s — since once a mapping
     * is live the port is reachable by anyone who finds it. Only works on a
     * Wi-Fi network whose router supports and allows UPnP; never on cellular.
     * See [com.hereliesaz.mcpserved.transport.UpnpPortMapper].
     */
    var upnpEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPNP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_UPNP_ENABLED, value).apply()

    /**
     * Whether the device also listens on its own global IPv6 address, if it
     * has one.
     *
     * The most direct of the four paths: IPv6 has no NAT to begin with, so
     * there is no router to ask (unlike UPnP) and no relay to run (unlike the
     * relay path) — just the phone's own address, reachable as long as the
     * network's firewall allows unsolicited inbound. See
     * [com.hereliesaz.mcpserved.transport.LocalServer.setIpv6Enabled].
     */
    var ipv6Enabled: Boolean
        get() = prefs.getBoolean(KEY_IPV6_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IPV6_ENABLED, value).apply()

    private companion object {
        const val PREFS = "remote_access"
        const val KEY_WILDCARD_BIND = "mcp_wildcard_bind"
        const val KEY_RELAY_ENABLED = "relay_enabled"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_UPNP_ENABLED = "upnp_enabled"
        const val KEY_IPV6_ENABLED = "ipv6_enabled"
        const val BIND_LOOPBACK = "127.0.0.1"
        const val BIND_ALL = "0.0.0.0"
    }
}
