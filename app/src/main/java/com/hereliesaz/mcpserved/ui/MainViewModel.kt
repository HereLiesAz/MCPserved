package com.hereliesaz.mcpserved.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.mcpserved.crypto.McpToken
import com.hereliesaz.mcpserved.crypto.Pairing
import com.hereliesaz.mcpserved.grant.ConsentStore
import com.hereliesaz.mcpserved.grant.Grant
import com.hereliesaz.mcpserved.grant.GrantStore
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

    /** Copy-to-paste configs for each supported AI host, for the quick-connect list. */
    val quickConnectHosts = com.hereliesaz.mcpserved.transport.HostConfigs.hosts

    fun hostConfig(host: com.hereliesaz.mcpserved.transport.HostConfigs.Host): String =
        host.config(mcpEndpoint, _mcpBearer.value)

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

    /** True when the accessibility service is bound. Nothing works without it. */
    val a11yConnected: Boolean get() = McpAccessibilityService.instance != null

    val serviceRunning: Boolean get() = ControlService.instance != null

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
