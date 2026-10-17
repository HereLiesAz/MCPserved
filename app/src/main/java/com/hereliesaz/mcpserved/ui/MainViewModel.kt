package com.hereliesaz.mcpserved.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
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
import com.hereliesaz.mcpserved.macro.Macro
import com.hereliesaz.mcpserved.macro.MacroStore
import com.hereliesaz.mcpserved.macro.RawGestureRecorder
import com.hereliesaz.mcpserved.service.ControlService
import com.hereliesaz.mcpserved.service.McpAccessibilityService
import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.DesktopDiscovery
import com.hereliesaz.mcpserved.transport.McpServer
import com.hereliesaz.mcpserved.transport.PairingPush
import com.hereliesaz.mcpserved.transport.Request
import com.hereliesaz.mcpserved.transport.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val KEY_PREFERRED_HOST = "preferred_host"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_REQUEST_CODE = 7301

/** How long [MainViewModel.startService]'s "Connecting…" feedback polls for, and how often. */
private const val CONNECTING_POLL_INTERVAL_MS = 150L
private const val CONNECTING_POLL_ATTEMPTS = 20

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
    private val macroStore = MacroStore(app)

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
        val isSystem: Boolean,
        val category: AppCategory,
        val permissionTags: Set<PermissionTag>
    )

    private val _apps = MutableStateFlow<List<AppRow>>(emptyList())
    val apps: StateFlow<List<AppRow>> = _apps

    private val _pairPayload = MutableStateFlow(pairing.currentPayload().encode())
    val pairPayload: StateFlow<String> = _pairPayload

    private val _isPaired = MutableStateFlow(pairing.isPaired)
    val isPaired: StateFlow<Boolean> = _isPaired

    /** Live status of the automatic LAN push that finishes a desktop QR scan. */
    sealed class PairPushStatus {
        data object Idle : PairPushStatus()
        data object Pushing : PairPushStatus()
        data class Failed(val message: String) : PairPushStatus()
    }

    private val _pairPushStatus = MutableStateFlow<PairPushStatus>(PairPushStatus.Idle)
    val pairPushStatus: StateFlow<PairPushStatus> = _pairPushStatus

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

    /** A ready-to-paste MCP host config for the direct (loopback) endpoint. */
    fun mcpConfigJson(): String = mcpConfigJson(mcpEndpoint)

    /** A ready-to-paste MCP host config for any endpoint (e.g. a public tunnel URL). */
    fun mcpConfigJson(endpoint: String): String = """
        {
          "mcpServers": {
            "mcpserved": {
              "url": "$endpoint",
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

    // ---- Deploying a relay to the operator's own Cloudflare account -------

    private val cloudflareToken = com.hereliesaz.mcpserved.crypto.CloudflareApiToken(app)
    private val cloudflareDeployer = com.hereliesaz.mcpserved.transport.CloudflareRelayDeployer(app)
    private val namedTunnelDeployer = com.hereliesaz.mcpserved.transport.CloudflareNamedTunnel(app)

    sealed class DeployState {
        object Idle : DeployState()
        object Deploying : DeployState()
        data class Done(val url: String, val note: String? = null) : DeployState()
        data class Error(val message: String) : DeployState()
    }

    // ---- A stable address via a named Cloudflare tunnel (no desktop) ------

    /**
     * The hostname this device's named tunnel serves — a no-desktop remote MCP
     * connector. Operator-supplied: a hostname in a Cloudflare zone on the
     * operator's *own* account, so the traffic is theirs end to end and the app
     * ships with no shared domain. Blank until entered.
     */
    fun currentNamedTunnelHostname(): String = cloudflareToken.namedTunnelHostname

    /** Persists the operator's chosen hostname as they type it. */
    fun setNamedTunnelHostname(host: String) {
        cloudflareToken.namedTunnelHostname = host
    }

    /** Progress of provisioning the named tunnel (the one-time API setup). */
    private val _stableAddressState = MutableStateFlow<DeployState>(DeployState.Idle)
    val stableAddressState: StateFlow<DeployState> = _stableAddressState

    /** Whether a named tunnel is already provisioned (a run token is stored). */
    val stableAddressProvisioned: Boolean get() = cloudflareToken.namedTunnelRunToken.isNotBlank() &&
        cloudflareToken.provisionedHostname == cloudflareToken.namedTunnelHostname.trim()

    /** Running state of the named tunnel itself (re-exported from the service). */
    val namedTunnelState: StateFlow<com.hereliesaz.mcpserved.service.ControlService.Companion.TunnelState> =
        com.hereliesaz.mcpserved.service.ControlService.namedTunnelState

    /**
     * One tap for a stable, no-desktop address. Arms the service (so McpServer is
     * bound), then: if a tunnel is already provisioned, just runs it; otherwise
     * provisions one via the Cloudflare API (create tunnel + ingress + DNS) for
     * the operator's own [currentNamedTunnelHostname], persists the run token,
     * and runs it. The resulting `https://<hostname>/mcp` + bearer is a remote
     * MCP connector a cloud host registers directly.
     */
    fun setUpStableAddress() {
        viewModelScope.launch {
            val host = cloudflareToken.namedTunnelHostname.trim()
            if (host.isBlank()) {
                _stableAddressState.value = DeployState.Error(
                    "Enter your own hostname first — a subdomain in a Cloudflare zone on your account (e.g. mcp.yourdomain.com)."
                )
                return@launch
            }
            if (!serviceRunning) {
                startService()
                for (attempt in 1..CONNECTING_POLL_ATTEMPTS) {
                    if (serviceRunning) break
                    delay(CONNECTING_POLL_INTERVAL_MS)
                    _statusTick.value++
                }
            }
            if (!serviceRunning) {
                _stableAddressState.value = DeployState.Error("Couldn't start the control service.")
                return@launch
            }
            val storedToken = cloudflareToken.namedTunnelRunToken
            if (storedToken.isNotBlank() && cloudflareToken.provisionedHostname == host) {
                _stableAddressState.value = DeployState.Idle
                com.hereliesaz.mcpserved.service.ControlService.requestStartNamedTunnel(storedToken, host)
                return@launch
            }
            val apiToken = cloudflareToken.value
            if (apiToken.isBlank()) {
                _stableAddressState.value = DeployState.Error("Paste a Cloudflare API token first (under Deploy your own relay).")
                return@launch
            }
            _stableAddressState.value = DeployState.Deploying
            when (val r = namedTunnelDeployer.provision(apiToken, host)) {
                is com.hereliesaz.mcpserved.transport.CloudflareNamedTunnel.Result.Success -> {
                    cloudflareToken.namedTunnelRunToken = r.runToken
                    cloudflareToken.provisionedHostname = r.hostname
                    cloudflareToken.namedTunnelHostname = r.hostname
                    val note = buildString { r.notes.forEach { if (isNotEmpty()) append(' '); append(it).append('.') } }
                        .trim().ifBlank { null }
                    _stableAddressState.value = DeployState.Done("https://${r.hostname}", note)
                    if (serviceRunning) {
                        com.hereliesaz.mcpserved.service.ControlService.requestStartNamedTunnel(r.runToken, r.hostname)
                    }
                }
                is com.hereliesaz.mcpserved.transport.CloudflareNamedTunnel.Result.Failure -> {
                    _stableAddressState.value = DeployState.Error(r.message)
                }
            }
        }
    }

    /** Stops the named tunnel. The stable address stops answering until started again. */
    fun stopStableAddress() {
        com.hereliesaz.mcpserved.service.ControlService.requestStopNamedTunnel()
    }

    private val _cloudflareDeployState = MutableStateFlow<DeployState>(DeployState.Idle)
    val cloudflareDeployState: StateFlow<DeployState> = _cloudflareDeployState

    /** Not a StateFlow: the token is write-mostly, read only when a deploy actually runs. */
    fun setCloudflareApiToken(token: String) {
        cloudflareToken.value = token
    }

    /**
     * The persisted token, for the screen to seed its text field with on entry —
     * otherwise every save is invisible: it lands correctly in
     * `EncryptedSharedPreferences` on every keystroke, but a field that always
     * starts blank looks exactly like it never saved.
     */
    fun currentCloudflareApiToken(): String = cloudflareToken.value

    /** Local erase only; revoke the Cloudflare API token in its dashboard separately. */
    fun clearCloudflareCredentials() {
        stopStableAddress()
        cloudflareToken.clear()
        _stableAddressState.value = DeployState.Idle
        _cloudflareDeployState.value = DeployState.Idle
    }

    /**
     * Deploys `relay/cloudflare/worker.js` (bundled as an asset) to the
     * operator's own Cloudflare account and, on success, fills in [relayUrl]
     * with the resulting address — no separate "now paste the URL" step.
     * See [com.hereliesaz.mcpserved.transport.CloudflareRelayDeployer]'s doc
     * for why this is unverified against a live account.
     */
    fun deployCloudflareRelay() {
        val token = cloudflareToken.value
        if (token.isBlank()) {
            _cloudflareDeployState.value = DeployState.Error("Paste a Cloudflare API token first.")
            return
        }
        _cloudflareDeployState.value = DeployState.Deploying
        viewModelScope.launch {
            when (val result = cloudflareDeployer.deploy(token)) {
                is com.hereliesaz.mcpserved.transport.CloudflareRelayDeployer.Result.Success -> {
                    setRelayUrl(result.url)
                    _cloudflareDeployState.value = DeployState.Done(result.url)
                }
                is com.hereliesaz.mcpserved.transport.CloudflareRelayDeployer.Result.Failure -> {
                    _cloudflareDeployState.value = DeployState.Error(result.message)
                }
            }
        }
    }

    // ---- A session-scoped tunnel, no account, alive only while this session is ---

    /** Re-exports [ControlService.tunnelState] — see that class for why it owns the process. */
    val tunnelState: StateFlow<com.hereliesaz.mcpserved.service.ControlService.Companion.TunnelState> =
        com.hereliesaz.mcpserved.service.ControlService.tunnelState

    /**
     * Starts a Cloudflare Quick Tunnel to [com.hereliesaz.mcpserved.transport.LocalWsServer] —
     * anonymous, no Cloudflare account, alive only as long as this app process
     * keeps it running. On success the resulting URL fills [relayUrl] the same
     * way [deployCloudflareRelay] does, so the existing "send connect
     * instructions" action picks it up unchanged. Requires the service to be
     * armed already — there is nothing at the other end of the tunnel otherwise.
     */
    fun startTunnel() {
        com.hereliesaz.mcpserved.service.ControlService.requestStartTunnel()
    }

    fun stopTunnel() {
        com.hereliesaz.mcpserved.service.ControlService.requestStopTunnel()
    }

    /**
     * One tap: arm the service if it isn't already (so the MCP server is bound),
     * wait for it to come up, then open the tunnel. The tunnel has nothing to
     * front until the server is listening, so arming must finish first — this
     * sequences the two the ViewModel way, reusing [startService]'s poll.
     */
    fun setUpRemoteAccess() {
        viewModelScope.launch {
            if (!serviceRunning) {
                startService()
                // startService polls in its own coroutine; wait out the same
                // window here so the tunnel starts only once the server is up.
                for (attempt in 1..CONNECTING_POLL_ATTEMPTS) {
                    if (serviceRunning) break
                    delay(CONNECTING_POLL_INTERVAL_MS)
                    _statusTick.value++
                }
            }
            if (serviceRunning) startTunnel()
        }
    }

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

    /** The public MCP endpoint for a tunnel/base URL — [McpServer] serves at `/mcp`. */
    fun publicMcpEndpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/mcp"

    /**
     * Connect instructions for the one-tap Quick Tunnel.
     *
     * The tunnel now fronts the Streamable-HTTP [McpServer] (not the sealed-frame
     * WebSocket), so the public URL is a bearer-authed remote MCP endpoint a host
     * wires to directly — no `mcpserved` client, no pairing. Both forms, since a
     * host either takes a URL + Authorization header (claude.ai, Claude Code) or
     * reads a config file.
     */
    fun tunnelConnectString(url: String): String {
        val endpoint = publicMcpEndpoint(url)
        val bearer = _mcpBearer.value
        return """
            MCPserved — remote MCP server (Streamable HTTP, no desktop needed).

            Add as a connector (e.g. claude.ai → Settings → Connectors):
              URL:    $endpoint
              Header: Authorization: Bearer $bearer

            Claude Code, one line:
              claude mcp add --transport http mcpserved $endpoint --header "Authorization: Bearer $bearer"

            Config-file hosts:
            ${mcpConfigJson(endpoint)}
        """.trimIndent()
    }

    /**
     * The connect string for a persistent, operator-deployed relay.
     *
     * Takes [url] explicitly rather than reading [relayUrl] itself, since a
     * caller may be sending the just-deployed URL before it's finished being
     * persisted to [relayUrl] — never something this function should read
     * off state that might be one recomposition behind.
     */
    fun relayConnectString(url: String): String =
        "MCPSERVED_MODE=relay MCPSERVED_RELAY_URL=$url " +
            "MCPSERVED_RELAY_ROOM=${_relayRoomToken.value} mcpserved install claude-code"

    /** True when the accessibility service is bound. Nothing works without it. */
    val a11yConnected: Boolean get() = McpAccessibilityService.instance != null

    val serviceRunning: Boolean get() = ControlService.instance != null

    /**
     * True once the operator has granted the MediaProjection screenshot
     * dialog. Only meaningful when neither accessibility nor root can
     * capture the screen — see [MediaProjectionBackend].
     */
    val screenCaptureReady: Boolean get() = ControlService.instance?.screenCaptureReady ?: false

    /** True when screenshots need the one-time MediaProjection grant — no root to bypass it with. */
    val needsScreenCaptureGrant: Boolean
        get() {
            val svc = ControlService.instance ?: return false
            return !svc.screenCaptureReady && Cap.CAPTURE_SILENT !in svc.resolver.caps
        }

    /** The intent [MainActivity] should launch to request the consent dialog, if the service is up. */
    fun screenCaptureIntent(): Intent? = ControlService.instance?.mediaProjectionBackend?.captureIntent()

    fun revokeScreenCapture() {
        ControlService.instance?.revokeScreenCapture()
        _statusTick.value++
    }

    /**
     * Bumped by [refreshStatus] so [StatusScreen] recomposes and re-reads
     * [a11yConnected]/[serviceRunning] — both are plain getters over static
     * instances, not their own observable state, so nothing here would
     * otherwise force a redraw when the user comes back from system settings.
     */
    private val _statusTick = MutableStateFlow(0)
    val statusTick: StateFlow<Int> = _statusTick

    /**
     * Last reading of [a11yConnected], so [refreshStatus] can tell "just
     * became connected" from "was already connected" — null until the first
     * call, so that call's reading sets the baseline rather than a hardcoded
     * default.
     */
    private var lastA11yConnected: Boolean? = null

    /**
     * Re-reads readiness and, the moment accessibility just became available,
     * arms immediately — no separate tap. Enabling accessibility in system
     * settings is the one step Android will not let an app do for itself; once
     * the user has done that one unavoidable thing, everything on this app's
     * side of the boundary should happen without asking for another tap.
     *
     * Gated on the false→true edge of [a11yConnected], not merely its current
     * value: this runs on every `ON_RESUME`, including the one right after
     * the notification's Disarm action (or the in-app one) stops the service
     * — accessibility is still connected then, only [serviceRunning] changed.
     * Auto-arming on the level rather than the edge would silently undo that
     * disarm the moment the app next resumed.
     *
     * Called from [StatusScreen] on every `ON_RESUME` — the moment the user
     * returns from the settings screen this app sent them to.
     */
    fun refreshStatus() {
        _statusTick.value++
        val justConnected = a11yConnected && lastA11yConnected == false
        lastA11yConnected = a11yConnected
        if (justConnected && !serviceRunning) startService()
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
        // runs on Dispatchers.Main, so the whole enumeration is moved to IO. Permissions
        // are fetched per-package rather than via a single bulk GET_PERMISSIONS call —
        // more binder round-trips, but each is small, instead of one giant transaction
        // sized by every installed package's permission list.
        _apps.value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    AppRow(
                        pkg = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        scopes = granted[info.packageName]?.scopes ?: emptySet(),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        category = AppCategory.of(info),
                        permissionTags = PermissionTag.tagsFor(currentlyGrantedPermissions(pm, info.packageName))
                    )
                }
                .sortedWith(compareBy({ it.scopes.isEmpty() }, { it.label.lowercase() }))
        }
    }

    /**
     * Permissions [pkg] currently holds, not merely ones its manifest
     * declares wanting — `requestedPermissions` lists both granted and
     * refused requests, so only the flagged-granted ones count. Failures
     * (a package uninstalled mid-enumeration, most likely) fall back to
     * "holds nothing" rather than crashing the whole app list.
     */
    private fun currentlyGrantedPermissions(pm: PackageManager, pkg: String): Set<String> =
        runCatching {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: return@runCatching emptySet()
            val flags = info.requestedPermissionsFlags ?: return@runCatching emptySet()
            requested.indices
                .filter { flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                .mapTo(mutableSetOf()) { requested[it] }
        }.getOrDefault(emptySet())

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

    // ---- macros: user-recorded, AI-runnable action sequences ---------------
    // (macroStore itself is declared near the top of the class — see the comment there.)

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros

    /** What the current package must be, and how far a running recording has gotten. */
    sealed class RecordingState {
        data object Idle : RecordingState()
        data class Recording(val pkg: String, val steps: Int) : RecordingState()
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private var recordingStepsJob: Job? = null

    /**
     * The active root-based recorder, when [startRecording] chose that path.
     * Null whenever recording is idle, or when the active recording is
     * accessibility-based instead — the two never run at once.
     */
    private var rawRecorder: RawGestureRecorder? = null
    private var rawRecordingJob: Job? = null

    private val _macroRunResult = MutableStateFlow<String?>(null)
    val macroRunResult: StateFlow<String?> = _macroRunResult

    fun refreshMacros() = viewModelScope.launch {
        _macros.value = withContext(Dispatchers.IO) { macroStore.current() }
    }

    /**
     * Starts recording against [pkg] — the package currently in the
     * foreground, since a macro's node ids and coordinates only ever mean
     * anything for the one app they were captured against (see
     * [com.hereliesaz.mcpserved.service.Dispatcher.macroRun]).
     *
     * Root's [RawGestureRecorder] is strictly more capable than
     * accessibility's [com.hereliesaz.mcpserved.macro.MacroRecorder] — it
     * can capture swipes, which accessibility's event stream cannot report
     * at all — so it wins whenever root is available, on either flavor.
     * Accessibility is the fallback, and the only option at all on a
     * `playstore`-flavor device without root.
     *
     * @return false when neither path is available, or a recording is
     *   already running
     */
    fun startRecording(pkg: String): Boolean {
        val svc = ControlService.instance
        return if (svc != null && svc.resolver.hasRoot) startRawRecording(pkg, svc)
        else startAccessibilityRecording(pkg)
    }

    private fun startRawRecording(pkg: String, svc: ControlService): Boolean {
        if (rawRecorder != null) return false
        val dm = getApplication<Application>().resources.displayMetrics
        val recorder = RawGestureRecorder(svc.resolver::shell, dm.widthPixels, dm.heightPixels)
        rawRecorder = recorder
        _recordingState.value = RecordingState.Recording(pkg, 0)
        recordingStepsJob = recorder.steps
            .onEach { steps -> _recordingState.value = RecordingState.Recording(pkg, steps.size) }
            .launchIn(viewModelScope)
        rawRecordingJob = viewModelScope.launch(Dispatchers.IO) { recorder.start() }
        return true
    }

    private fun startAccessibilityRecording(pkg: String): Boolean {
        val svc = McpAccessibilityService.instance ?: return false
        if (!svc.startRecording(pkg)) return false
        _recordingState.value = RecordingState.Recording(pkg, 0)
        recordingStepsJob = svc.recordingSteps
            ?.onEach { steps -> _recordingState.value = RecordingState.Recording(pkg, steps.size) }
            ?.launchIn(viewModelScope)
        return true
    }

    /** Discards whatever has been captured so far without saving it. */
    fun cancelRecording() {
        rawRecorder?.stop()
        rawRecorder = null
        rawRecordingJob?.cancel()
        rawRecordingJob = null
        McpAccessibilityService.instance?.stopRecording()
        recordingStepsJob?.cancel()
        recordingStepsJob = null
        _recordingState.value = RecordingState.Idle
    }

    /**
     * Stops recording and saves it as [name]. A blank name or an empty
     * capture (nothing the active recorder could translate into a step)
     * discards the recording instead of saving a macro that would do
     * nothing.
     *
     * @return false when there was nothing to save
     */
    fun stopRecording(name: String): Boolean {
        val pkg = (_recordingState.value as? RecordingState.Recording)?.pkg
        val steps = rawRecorder?.stop() ?: McpAccessibilityService.instance?.stopRecording()
        rawRecorder = null
        rawRecordingJob?.cancel()
        rawRecordingJob = null
        recordingStepsJob?.cancel()
        recordingStepsJob = null
        _recordingState.value = RecordingState.Idle

        if (pkg == null || steps.isNullOrEmpty() || name.isBlank()) return false
        viewModelScope.launch {
            macroStore.put(
                Macro(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    pkg = pkg,
                    steps = steps,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
            refreshMacros()
        }
        return true
    }

    fun deleteMacro(name: String) = viewModelScope.launch {
        macroStore.delete(name)
        refreshMacros()
    }

    /**
     * Runs a saved macro locally, through the same session-gated
     * [com.hereliesaz.mcpserved.service.Dispatcher] instance an AI-driven
     * action uses — a short session opens for the call and closes right
     * after, mirroring what a remote caller would do around one action.
     * This is also exactly how an AI host runs a macro remotely: it just
     * skips the local button and calls `macro_run` directly, since the
     * dispatcher makes no distinction between the two callers.
     *
     * Brings the macro's app forward first when it isn't already there —
     * [com.hereliesaz.mcpserved.service.Dispatcher.macroRun] refuses to run
     * against the wrong foreground app — and waits out the activity
     * transition rather than racing it.
     */
    fun runMacro(name: String) = viewModelScope.launch {
        _macroRunResult.value = null
        val service = ControlService.instance
        if (service == null) {
            _macroRunResult.value = "the control service isn't running — arm it first"
            return@launch
        }
        val macro = macroStore.find(name)
        if (macro == null) {
            _macroRunResult.value = "no macro named '$name'"
            return@launch
        }
        if (McpAccessibilityService.instance?.foreground?.pkg != macro.pkg) {
            val ctx = getApplication<Application>()
            val intent = ctx.packageManager.getLaunchIntentForPackage(macro.pkg)
            if (intent == null) {
                _macroRunResult.value = "can't launch ${macro.pkg} — is it still installed?"
                return@launch
            }
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(700)
        }
        val wasActive = service.session.isActive
        if (!wasActive) service.beginSession(60)
        val resp = service.dispatcher.handle(Request.MacroRun(name))
        if (!wasActive) service.endSession()
        _macroRunResult.value = if (resp.ok) "ran '$name'" else "error: ${resp.error}"
    }

    fun clearMacroRunResult() {
        _macroRunResult.value = null
    }

    /**
     * Completes pairing from a scanned code.
     *
     * Two shapes: a desktop's live pairing QR (`mcpserved-pair:1:…`), which
     * carries its LAN address and a one-time token — scanning it is the whole
     * exchange, since this device immediately pushes its own public key back
     * over the network with no further step from the operator (see
     * [PairingPush]). Or the older reciprocal reply (`mcpserved:2:…`) the
     * `mcpserved pair` terminal command prints for the no-shared-LAN, adb-only
     * case, where the operator scans it back by hand because there is nothing
     * to push to.
     *
     * @return false only when [scanned] matches neither shape; a push that
     *   later fails is reported through [pairPushStatus] instead, since the
     *   scan itself was a valid pairing code.
     */
    fun completePairing(scanned: String): Boolean {
        Pairing.DesktopPairingRequest.decode(scanned)?.let { request ->
            pushToDesktop(request)
            return true
        }

        val payload = Pairing.QrPayload.decode(scanned) ?: return false
        val ok = pairing.completePairing(payload.devicePublicKey)
        _isPaired.value = pairing.isPaired
        return ok
    }

    private fun pushToDesktop(request: Pairing.DesktopPairingRequest) {
        _pairPushStatus.value = PairPushStatus.Pushing
        val myPublicKey = pairing.currentPayload().devicePublicKey
        val deviceId = pairing.deviceId
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PairingPush.push(request.host, request.port, request.token, deviceId, myPublicKey)
                }
            }
            result.onSuccess {
                pairing.completePairing(request.desktopPublicKey)
                _isPaired.value = pairing.isPaired
                _pairPushStatus.value = PairPushStatus.Idle
            }.onFailure { e ->
                _pairPushStatus.value = PairPushStatus.Failed(e.message ?: "couldn't reach the desktop")
            }
        }
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

    /**
     * True from the moment [startService] is tapped until [serviceRunning]
     * actually flips or [CONNECTING_POLL_ATTEMPTS] is exhausted.
     *
     * [startForegroundService] returns before the service has bound —
     * [serviceRunning] is a plain getter over [ControlService.instance], so
     * nothing recomposes [StatusScreen] to reflect that until the next
     * [_statusTick] bump. Without this, the "Connect" button's tap has no
     * visible effect at all until the next `ON_RESUME`.
     */
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    fun startService() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, ControlService::class.java))
        _connecting.value = true
        viewModelScope.launch {
            for (attempt in 1..CONNECTING_POLL_ATTEMPTS) {
                delay(CONNECTING_POLL_INTERVAL_MS)
                _statusTick.value++
                if (serviceRunning) break
            }
            _connecting.value = false
        }
    }

    fun stopService() {
        _connecting.value = false
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

    // ---- Shizuku: the playstore flavor's readiness signal, in place of accessibility ---

    /** Whether the Shizuku app is installed at all — the first rung of onboarding. */
    val shizukuInstalled: Boolean
        get() = runCatching {
            getApplication<Application>().packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    /**
     * Whether this app currently holds Shizuku's permission. Mirrors
     * [com.hereliesaz.mcpserved.backend.ShizukuBackend]'s own availability
     * check — `pingBinder()` false means Shizuku isn't installed, isn't
     * paired, or died on the last reboot; permission can only be granted
     * once the binder is actually alive.
     */
    val shizukuReady: Boolean
        get() = runCatching {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * One button that tracks exactly where the user is, same shape as
     * [openAccessibilitySettings] on the github flavor: not installed →
     * the Play listing; installed but not paired → wireless-debugging
     * settings, where Android 11+'s pair-with-a-code flow needs no computer;
     * paired but not yet permitted → Shizuku's own permission dialog.
     */
    fun connectShizuku() {
        val ctx = getApplication<Application>()
        when {
            !shizukuInstalled -> runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            !runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false) -> runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            else -> runCatching { rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
        }
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

    // Declared last, deliberately: Kotlin initializes properties and init{}
    // blocks in source order, and viewModelScope.launch's Main.immediate
    // dispatcher runs far enough synchronously (up to the first suspension
    // point) to reach a property declared below init{} while it is still
    // null. An init{} block placed anywhere earlier in the class is a crash
    // waiting on whichever property gets added below it next — this one
    // block, run only once every property above it in this file has already
    // been constructed, is proof against that class of bug rather than a fix
    // for one particular field (see the macroStore/_macros crashes this
    // exact bug produced historically).
    init {
        refreshApps()
        refreshMacros()
        viewModelScope.launch {
            _hasConsented.value = withContext(Dispatchers.IO) { consent.isAccepted }
        }
    }
}
