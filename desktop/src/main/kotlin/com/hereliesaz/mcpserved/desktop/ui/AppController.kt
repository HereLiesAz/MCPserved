package com.hereliesaz.mcpserved.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.mcpserved.desktop.adb.Adb
import com.hereliesaz.mcpserved.desktop.config.ConfigStore
import com.hereliesaz.mcpserved.desktop.discovery.DeviceDiscovery
import com.hereliesaz.mcpserved.desktop.discovery.DiscoveredDevice
import com.hereliesaz.mcpserved.desktop.hosts.Hosts
import com.hereliesaz.mcpserved.desktop.mcp.McpServer
import com.hereliesaz.mcpserved.desktop.net.AppLink
import com.hereliesaz.mcpserved.desktop.net.Target
import com.hereliesaz.mcpserved.desktop.net.boolOr
import com.hereliesaz.mcpserved.desktop.net.isOk
import com.hereliesaz.mcpserved.desktop.pair.PairingFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class Tab(val label: String) {
    Devices("Devices"),
    Pair("Pair"),
    Hosts("AI Hosts"),
    Log("Log"),
}

/**
 * Holds the desktop app's observable state and the actions the screens invoke.
 *
 * Every action that touches the network or the device runs off the UI thread on
 * [Dispatchers.IO]; results are marshalled back to the Swing main thread before
 * touching Compose state, which keeps discovery callbacks (fired from JmDNS
 * threads) from mutating state under recomposition.
 */
class AppController(private val scope: CoroutineScope) {

    var selectedTab by mutableStateOf(Tab.Devices)

    private val discovery = DeviceDiscovery()
    val devices = mutableStateListOf<DiscoveredDevice>()
    var discovering by mutableStateOf(false)
        private set

    var paired by mutableStateOf(ConfigStore.isPaired)
        private set
    var pairedDeviceId by mutableStateOf(ConfigStore.tryLoad()?.deviceId)
        private set

    var adbReady by mutableStateOf<Boolean?>(null)
        private set
    var backendLabel by mutableStateOf<String?>(null)
        private set
    var capabilitiesText by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    var pairInput by mutableStateOf("")
    var pairReply by mutableStateOf<String?>(null)
        private set
    var pairError by mutableStateOf<String?>(null)
        private set

    val hostOutcomes = mutableStateListOf<String>()
    val logLines = mutableStateListOf<String>()

    init {
        toggleDiscovery(true)
        scope.launch { refreshAdb() }
    }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun log(message: String) {
        scope.launch {
            onMain {
                logLines.add(0, message)
                while (logLines.size > 400) logLines.removeAt(logLines.size - 1)
            }
        }
    }

    fun toggleDiscovery(on: Boolean = !discovering) {
        if (on == discovering) return
        if (on) {
            discovery.start { scope.launch { syncDevices() } }
            discovering = true
            log("discovery started — browsing ${DeviceDiscovery.SERVICE_TYPE}")
        } else {
            discovery.stop()
            discovering = false
            devices.clear()
            log("discovery stopped")
        }
    }

    private suspend fun syncDevices() {
        val snapshot = discovery.snapshot()
        onMain {
            devices.clear()
            devices.addAll(snapshot)
        }
    }

    fun refreshAdb() {
        scope.launch {
            val ready = withContext(Dispatchers.IO) { Adb.ready() }
            onMain { adbReady = ready }
        }
    }

    /** Probe the best available backend and surface its capability report. */
    fun testConnection() {
        scope.launch {
            onMain { busy = true; capabilitiesText = "connecting…" }
            val (label, text) = withContext(Dispatchers.IO) {
                try {
                    val link = McpServer.chooseLink()
                    try {
                        val caps = link.send(buildJsonObject { put("op", JsonPrimitive("capabilities")) }, 8_000)
                        if (!caps.isOk()) {
                            link.label to "error: ${caps["error"]?.let { (it as? JsonPrimitive)?.content } ?: "unreachable"}"
                        } else {
                            val capList = (caps["caps"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content } ?: ""
                            link.label to buildString {
                                appendLine("accessibility: ${if (caps.boolOr("a11y", false)) "connected" else "NOT CONNECTED"}")
                                appendLine("root: ${caps.boolOr("root", false)}")
                                appendLine("shizuku: ${caps.boolOr("shizuku", false)}")
                                append("capabilities: $capList")
                            }
                        }
                    } finally {
                        link.close()
                    }
                } catch (e: Exception) {
                    "none" to "error: ${e.message}"
                }
            }
            onMain {
                backendLabel = label
                capabilitiesText = text
                busy = false
            }
            log("tested backend $label")
        }
    }

    /** Probe one discovered device directly over its advertised LAN address. */
    fun testDevice(device: DiscoveredDevice) {
        val config = ConfigStore.tryLoad()
        if (config == null) {
            log("not paired — pair with the device before connecting")
            selectedTab = Tab.Pair
            return
        }
        scope.launch {
            onMain { busy = true }
            val text = withContext(Dispatchers.IO) {
                val link = AppLink(config, Target.lan(device.host, device.port))
                try {
                    val caps = link.send(buildJsonObject { put("op", JsonPrimitive("capabilities")) }, 6_000)
                    if (caps.isOk()) "reachable at ${device.host}:${device.port}" else "unreachable: ${caps["error"]}"
                } catch (e: Exception) {
                    "error: ${e.message}"
                } finally {
                    link.close()
                }
            }
            onMain { busy = false }
            log("device ${device.name}: $text")
        }
    }

    fun pair() {
        val input = pairInput.trim()
        if (input.isEmpty()) return
        scope.launch {
            onMain { busy = true; pairError = null }
            val result = withContext(Dispatchers.IO) { runCatching { PairingFlow.pairFromPayload(input) } }
            onMain {
                busy = false
                result.onSuccess {
                    pairReply = it.reply
                    paired = true
                    pairedDeviceId = it.deviceId
                    log("paired with ${it.deviceId}")
                }.onFailure {
                    pairError = it.message
                    log("pairing failed: ${it.message}")
                }
            }
        }
    }

    fun unpair() {
        ConfigStore.clear()
        paired = false
        pairedDeviceId = null
        pairReply = null
        log("unpaired — device keys discarded")
    }

    fun installHost(target: Hosts.Target) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Hosts.install(target) }
            onMain { record(describe(outcome)) }
        }
    }

    fun installClaudeCode() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Hosts.installClaudeCode() }
            onMain { record(describe(outcome)) }
        }
    }

    fun installAll() {
        installClaudeCode()
        Hosts.targets.forEach { installHost(it) }
    }

    private fun record(line: String) {
        hostOutcomes.add(0, line)
        log(line)
    }

    private fun describe(o: Hosts.Outcome): String = when (o) {
        is Hosts.Outcome.Written -> "${o.label}: ${if (o.updated) "updated" else "added"} → ${o.path}"
        is Hosts.Outcome.Blocked -> "${o.label}: config is not plain JSON — open it and paste the snippet manually"
        is Hosts.Outcome.Unavailable -> "${o.label}: not available on this OS"
        is Hosts.Outcome.External -> "${o.label}: ${o.message}"
    }

    fun dispose() {
        discovery.stop()
    }
}
