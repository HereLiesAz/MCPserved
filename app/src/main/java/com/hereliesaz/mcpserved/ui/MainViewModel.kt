package com.hereliesaz.mcpserved.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.mcpserved.crypto.McpToken
import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.crypto.RelayToken
import com.hereliesaz.mcpserved.grant.ConsentStore
import com.hereliesaz.mcpserved.grant.Grant
import com.hereliesaz.mcpserved.grant.GrantStore
import com.hereliesaz.mcpserved.grant.RemoteAccessStore
import com.hereliesaz.mcpserved.service.ControlService
import com.hereliesaz.mcpserved.service.McpAccessibilityService
import com.hereliesaz.mcpserved.transport.DesktopDiscovery
import com.hereliesaz.mcpserved.transport.McpServer
import com.hereliesaz.mcpserved.transport.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY_PREFERRED_HOST = "preferred_host"

/**
 * State for the whole application, which is small enough not to want more.
 *
 * Deliberately thin: everything durable lives in [GrantStore] and [Pairing], and
 * everything live lives in [ControlService]. This exists to marshal between those
 * and Compose, not to own anything.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val pairing = Pairing(app)
    private val store = GrantStore(app)
    private val consent = ConsentStore(app)

    /**
     * Whether the prominent disclosure has been accepted.
     *
     * Gates the entire UI. `null` means "not yet loaded": the value is read off
     * the main thread, and while it is pending the UI shows neither the
     * disclosure nor the main surface, so there is no flash of gated content and
     * no disk I/O on the main thread. It is recorded once and never cleared here:
     * revoking consent is uninstalling the app, which is the honest scope for a
     * decision this broad.
     */
    private val _hasConsented = MutableStateFlow<Boolean?>(null)
    val hasConsented: StateFlow<Boolean?> = _hasConsented

    /** Records acceptance of the disclosure and lets the rest of the app open. */
    fun grantConsent() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { consent.accept() }
            _hasConsented.value = true
        }
    }

    /** One installed application, with whatever grant it currently holds. */
    data class AppRow(
        val pkg: String,
        val label: String,
        val scopes: Set<Scope>,
        val isSystem: Boolean
    )

    private val _apps = MutableStateFlow<List<AppRow>>(emptyList())
    val apps: StateFlow<List<AppRow>> = _apps

    private val _pairPayload = MutableStateFlow(pairing.currentPayload().encode())
    val pairPayload: StateFlow<String> = _pairPayload

    private val _isPaired = MutableStateFlow(pairing.isPaired)
    val isPaired: StateFlow<Boolean> = _isPaired

    private val mcpToken = McpToken(app)

    /**
     * The device's own MCP-over-HTTP endpoint.
     *
     * Bound to loopback on the device; a host reaches it through an
     * `adb forward tcp:PORT tcp:PORT` tunnel and connects to this URL. This is the
     * direct path — the device is the MCP server, with no desktop process between
     * it and the model's host.
     */
    val mcpEndpoint: String = "http://127.0.0.1:${McpServer.DEFAULT_HTTP_PORT}/mcp"

    private val _mcpBearer = MutableStateFlow(mcpToken.value())
    val mcpBearer: StateFlow<String> = _mcpBearer

    /**
     * Desktops seen on the LAN (mutual discovery).
     *
     * The phone advertises its control service so a desktop can find it; this is
     * the other direction — every MCPserved desktop advertising itself on the
     * network, shown so the connection feels two-way rather than one-way.
     */
    private val desktopDiscovery = DesktopDiscovery(app).also { it.start() }
    val discoveredDesktops: StateFlow<List<DesktopDiscovery.Desktop>> = desktopDiscovery.desktops

    /** Copy-to-paste configs for each supported AI host, for the "choose a different one" list. */
    val quickConnectHosts = com.hereliesaz.mcpserved.transport.HostConfigs.hosts

    fun hostConfig(host: com.hereliesaz.mcpserved.transport.HostConfigs.Host): String =
        host.config(mcpEndpoint, _mcpBearer.value)

    private val uiPrefs by lazy {
        getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    }

    /**
     * The one host the Direct tab's primary button targets, remembered across
     * launches so a repeat visit is a single tap with no list to scan.
     * Defaults to the first entry in [quickConnectHosts]; changing the choice
     * from the "choose a different host" list updates this too.
     */
    private val _preferredHost = MutableStateFlow(
        quickConnectHosts.firstOrNull { it.id == uiPrefs.getString(KEY_PREFERRED_HOST, null) }
            ?: quickConnectHosts.first()
    )
    val preferredHost: StateFlow<com.hereliesaz.mcpserved.transport.HostConfigs.Host> = _preferredHost

    fun setPreferredHost(host: com.hereliesaz.mcpserved.transport.HostConfigs.Host) {
        uiPrefs.edit().putString(KEY_PREFERRED_HOST, host.id).apply()
        _preferredHost.value = host
    }

    /** A ready-to-paste MCP host config for the direct endpoint. */
    fun mcpConfigJson(): String = """
        {
          "mcpServers": {
            "mcpserved": {
              "url": "$mcpEndpoint",
              "headers": { "Authorization": "Bearer ${_mcpBearer.value}" }
            }
          }
        }
    """.trimIndent()

    /** Mints a new bearer token, invalidating any host still using the old one. */
    fun rotateMcpToken() {
        _mcpBearer.value = mcpToken.rotate()
    }

    // ---- Remote access: opt-in, off by default ---------------------------

    private val remoteAccess = RemoteAccessStore(app)
    private val relayToken = RelayToken(app)

    private val _hasAcceptedRemoteAccessDisclosure = MutableStateFlow(consent.isRemoteAccessAccepted)
    val hasAcceptedRemoteAccessDisclosure: StateFlow<Boolean> = _hasAcceptedRemoteAccessDisclosure

    /** Records acceptance of the one-time remote-access disclosure. */
    fun acceptRemoteAccessDisclosure() {
        consent.acceptRemoteAccess()
        _hasAcceptedRemoteAccessDisclosure.value = true
    }

    private val _wildcardMcpBind = MutableStateFlow(remoteAccess.wildcardMcpBind)
    val wildcardMcpBind: StateFlow<Boolean> = _wildcardMcpBind

    private val _relayEnabled = MutableStateFlow(remoteAccess.relayEnabled)
    val relayEnabled: StateFlow<Boolean> = _relayEnabled

    private val _relayUrl = MutableStateFlow(remoteAccess.relayUrl)
    val relayUrl: StateFlow<String> = _relayUrl

    private val _relayRoomToken = MutableStateFlow(relayToken.value())
    val relayRoomToken: StateFlow<String> = _relayRoomToken

    private val _upnpEnabled = MutableStateFlow(remoteAccess.upnpEnabled)
    val upnpEnabled: StateFlow<Boolean> = _upnpEnabled

    /** The current UPnP mapping, if any — see [ControlService.upnpMapping]. */
    val upnpMapping: StateFlow<com.hereliesaz.mcpserved.transport.UpnpPortMapper.Mapping?> =
        ControlService.upnpMapping

    private val _ipv6Enabled = MutableStateFlow(remoteAccess.ipv6Enabled)
    val ipv6Enabled: StateFlow<Boolean> = _ipv6Enabled

    /** Addresses this device could be reached at if [wildcardMcpBind] is set. */
    val localAddresses: List<String>
        get() = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())

    /**
     * This device's own global-scope IPv6 address(es), if any — informational
     * only, mirroring [localAddresses]. Whether [ipv6Enabled] actually leaves
     * the corresponding port reachable is a property of the network, not of
     * this list; see [com.hereliesaz.mcpserved.transport.LocalServer].
     */
    val ipv6Addresses: List<String>
        get() = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet6Address>()
                .filter {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress &&
                        (it.address[0].toInt() and 0xFE) != 0xFC
                }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())

    /**
     * Sets whether [com.hereliesaz.mcpserved.transport.McpServer] binds every
     * interface instead of loopback only. Takes effect the next time the
     * service arms — it is read once, in [ControlService.onCreate].
     */
    fun setWildcardMcpBind(enabled: Boolean) {
        remoteAccess.wildcardMcpBind = enabled
        _wildcardMcpBind.value = enabled
    }

    /**
     * Sets whether the device dials out to the configured relay. Takes effect
     * the next time the service arms, same as [setWildcardMcpBind].
     */
    fun setRelayEnabled(enabled: Boolean) {
        remoteAccess.relayEnabled = enabled
        _relayEnabled.value = enabled
    }

    /**
     * Sets whether the device tries UPnP port mapping. Takes effect the next
     * time the service arms, same as [setWildcardMcpBind].
     */
    fun setUpnpEnabled(enabled: Boolean) {
        remoteAccess.upnpEnabled = enabled
        _upnpEnabled.value = enabled
    }

    /**
     * Sets whether [com.hereliesaz.mcpserved.transport.LocalServer] also
     * listens on this device's global IPv6 address. Takes effect the next
     * time the service arms, same as [setUpnpEnabled].
     */
    fun setIpv6Enabled(enabled: Boolean) {
        remoteAccess.ipv6Enabled = enabled
        _ipv6Enabled.value = enabled
    }

    fun setRelayUrl(url: String) {
        remoteAccess.relayUrl = url
        _relayUrl.value = remoteAccess.relayUrl
    }

    /** Mints a new room token. Any relay room paired under the old one goes cold. */
    fun rotateRelayRoomToken() {
        _relayRoomToken.value = relayToken.rotate()
    }

    /** The connect string an operator pastes into `mcpserved connect --relay`. */
    fun relayConnectString(): String =
        "MCPSERVED_MODE=relay MCPSERVED_RELAY_URL=${_relayUrl.value} " +
            "MCPSERVED_RELAY_ROOM=${_relayRoomToken.value} mcpserved install claude-code"

    /** True when the accessibility service is bound. Nothing works without it. */
    val a11yConnected: Boolean get() = McpAccessibilityService.instance != null

    val serviceRunning: Boolean get() = ControlService.instance != null

    /**
     * Bumped by [refreshStatus] so [StatusScreen] recomposes and re-reads
     * [a11yConnected]/[serviceRunning] — both are plain getters over static
     * instances, not their own observable state, so nothing here would
     * otherwise force a redraw when the user comes back from system settings.
     */
    private val _statusTick = MutableStateFlow(0)
    val statusTick: StateFlow<Int> = _statusTick

    /**
     * Re-reads readiness and, the moment accessibility just became available,
     * arms immediately — no separate tap. Enabling accessibility in system
     * settings is the one step Android will not let an app do for itself; once
     * the user has done that one unavoidable thing, everything on this app's
     * side of the boundary should happen without asking for another tap.
     *
     * Called from [StatusScreen] on every `ON_RESUME` — the moment the user
     * returns from the settings screen this app sent them to.
     */
    fun refreshStatus() {
        _statusTick.value++
        if (a11yConnected && !serviceRunning) startService()
    }

    init {
        refreshApps()
        viewModelScope.launch {
            _hasConsented.value = withContext(Dispatchers.IO) { consent.isAccepted }
        }
    }

    /**
     * Loads installed applications, launchable ones first.
     *
     * System packages are included but marked. Excluding them would hide the
     * settings and dialer apps, which are occasionally the legitimate target and
     * are always the ones worth thinking twice about — better visible and
     * labelled than quietly missing.
     */
    fun refreshApps() = viewModelScope.launch {
        val pm = getApplication<Application>().packageManager
        val granted = store.current().associateBy { it.pkg }

        // getInstalledApplications is a blocking binder call that can be slow and,
        // on devices with many apps, throw TransactionTooLargeException. viewModelScope
        // runs on Dispatchers.Main, so the whole enumeration is moved to IO.
        _apps.value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    AppRow(
                        pkg = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        scopes = granted[info.packageName]?.scopes ?: emptySet(),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedWith(compareBy({ it.scopes.isEmpty() }, { it.label.lowercase() }))
        }
    }

    /**
     * Sets the scope set for a package.
     *
     * An empty set revokes rather than storing a grant that permits nothing.
     * A grant row conferring no authority would appear in the list and read as
     * permission where there is none.
     */
    fun setScopes(pkg: String, scopes: Set<Scope>, ttlSec: Int?) = viewModelScope.launch {
        if (scopes.isEmpty()) {
            store.revoke(pkg)
        } else {
            store.put(
                Grant(
                    pkg = pkg,
                    scopes = scopes,
                    expiresAtEpochMs = ttlSec?.let { System.currentTimeMillis() + it * 1000L }
                )
            )
        }
        refreshApps()
    }

    fun revokeAll() = viewModelScope.launch {
        store.revokeAll()
        refreshApps()
    }

    /** Completes pairing from a scanned reply payload. */
    fun completePairing(scanned: String): Boolean {
        val payload = Pairing.QrPayload.decode(scanned) ?: return false
        val ok = pairing.completePairing(payload.devicePublicKey)
        _isPaired.value = pairing.isPaired
        return ok
    }

    /**
     * Discards the identity and mints a new one.
     *
     * The only complete revocation. Emptying the grant table stops the peer from
     * doing anything; rotating the key stops it from arriving at all.
     */
    fun rotateIdentity() {
        _pairPayload.value = pairing.rotateIdentity().encode()
        _isPaired.value = false
    }

    fun startService() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, ControlService::class.java))
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        ctx.startService(
            Intent(ctx, ControlService::class.java).setAction(ControlService.ACTION_DISARM)
        )
    }

    fun openAccessibilitySettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationSettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onCleared() {
        desktopDiscovery.stop()
        super.onCleared()
    }
}
