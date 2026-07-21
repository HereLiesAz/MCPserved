package com.hereliesaz.mcpserved.desktop.mcp

import com.hereliesaz.mcpserved.desktop.config.Config
import com.hereliesaz.mcpserved.desktop.config.ConfigStore
import com.hereliesaz.mcpserved.desktop.config.DiscoveryCache
import com.hereliesaz.mcpserved.desktop.discovery.DeviceDiscovery
import com.hereliesaz.mcpserved.desktop.net.AdbLink
import com.hereliesaz.mcpserved.desktop.net.AppLink
import com.hereliesaz.mcpserved.desktop.net.Link
import com.hereliesaz.mcpserved.desktop.net.ProtoJson
import com.hereliesaz.mcpserved.desktop.net.Target
import com.hereliesaz.mcpserved.desktop.net.boolOr
import com.hereliesaz.mcpserved.desktop.net.isOk
import com.hereliesaz.mcpserved.desktop.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Desktop MCP server for MCPserved — the headless, stdio side.
 *
 * Stdio transport, one device per process. Multiple devices would mean routing
 * every call by a target argument the model has to get right, and getting it
 * wrong means acting on the wrong phone — a failure with no recovery.
 *
 * Two backends sit behind one interface. The quick-connect default drives the
 * device straight over `adb`; when the on-device app is paired and reachable —
 * discovered on the LAN over mDNS, or bridged through `adb forward` — the server
 * upgrades to it for the richer accessibility surface. `MCPSERVED_MODE` pins the
 * choice to `adb` or `app`; the default, `auto`, prefers the app and falls back.
 */
object McpServer {

    private const val LATEST_PROTOCOL = "2025-06-18"
    private const val SERVER_VERSION = "0.4.0"

    private fun log(msg: String) = System.err.println(msg)

    /**
     * Picks a backend.
     *
     * With a pairing on file (and mode not pinned to adb) it first tries to find
     * the device on the LAN over mDNS and dial it directly, then falls back to an
     * `adb forward` tunnel onto its loopback port. A pinned `app` mode never
     * silently becomes adb — adb is device-wide shell authority, and falling back
     * to it would quietly widen what the operator asked to restrict.
     */
    fun chooseLink(): Link {
        val mode = (System.getenv("MCPSERVED_MODE") ?: "auto").lowercase()
        val config = if (mode == "adb") null else ConfigStore.tryLoad()

        if (mode == "app" && config == null) {
            error("MCPSERVED_MODE=app, but no pairing was found. Pair the device first.")
        }

        if (config != null) {
            appLinkFor(config)?.let { return it }
            if (mode == "app") {
                error(
                    "MCPSERVED_MODE=app, but the on-device app did not answer. Check that it is " +
                        "installed, paired, and armed, and that the device is reachable over the LAN " +
                        "or adb.",
                )
            }
        }

        return AdbLink()
    }

    /**
     * Try, in order: the address the background service already found (instant,
     * no browse), a live mDNS browse, then the adb-forward loopback tunnel.
     */
    private fun appLinkFor(config: Config): Link? {
        // Warm path: the always-on service keeps a fresh address on disk, so a
        // freshly-spawned stdio server connects without waiting to discover.
        DiscoveryCache.fresh(config.deviceId, System.currentTimeMillis())?.let { cached ->
            val app = AppLink(config, Target.lan(cached.host, cached.port))
            if (probe(app)) {
                log("connected to device via cached address: ${cached.host}:${cached.port}")
                return app
            }
            app.close()
        }

        discoverTarget(config.deviceId)?.let { target ->
            val app = AppLink(config, target)
            if (probe(app)) {
                log("connected to device over LAN: ${target.describe}")
                return app
            }
            app.close()
        }

        val loop = AppLink(config, Target.loopback(config))
        if (probe(loop)) {
            log("connected to device over adb: ${Target.loopback(config).describe}")
            return loop
        }
        loop.close()
        return null
    }

    private fun probe(link: Link): Boolean =
        runCatching { link.send(buildJsonObject { put("op", JsonPrimitive("capabilities")) }, 5_000).isOk() }
            .getOrDefault(false)

    /** Briefly browse mDNS for the paired device's advertised address. */
    private fun discoverTarget(deviceId: String, waitMs: Long = 3_000): Target? {
        val discovery = DeviceDiscovery()
        return try {
            discovery.start {}
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                discovery.snapshot().firstOrNull { it.deviceId == deviceId }?.let {
                    return Target.lan(it.host, it.port)
                }
                Thread.sleep(150)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            discovery.stop()
        }
    }

    /** Runs the JSON-RPC stdio loop until stdin closes. */
    fun run() {
        val link = chooseLink()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { link.close() } })

        var tools: List<ToolDef>? = null

        fun resolveTools(): List<ToolDef> {
            tools?.let { return it }
            val caps = link.send(buildJsonObject { put("op", JsonPrimitive("capabilities")) }, 15_000)
            if (!caps.isOk()) {
                // Device unreachable: advertise the unprivileged surface rather than
                // nothing, so the model is told (by a tool that then fails) what went
                // wrong instead of being handed an empty manifest.
                return buildTools(Capabilities(emptyList(), root = false, shizuku = false, a11y = false))
            }
            val capList = (caps["caps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            val resolved = buildTools(
                Capabilities(
                    caps = capList,
                    root = caps.boolOr("root", false),
                    shizuku = caps.boolOr("shizuku", false),
                    a11y = caps.boolOr("a11y", false),
                ),
            )
            tools = resolved
            return resolved
        }

        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        val out = System.out
        log("mcpserved stdio server ready — backend: ${link.label}")

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            val message = try {
                ProtoJson.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: Exception) {
                continue
            }

            val id = message["id"]
            val method = message.str("method") ?: continue
            val params = message["params"] as? JsonObject

            // Notifications carry no id and expect no response.
            val isNotification = id == null || id is JsonNull

            val response: JsonObject? = when (method) {
                "initialize" -> result(id, initializeResult(params))
                "notifications/initialized", "notifications/cancelled" -> null
                "ping" -> result(id, buildJsonObject { })
                "tools/list" -> result(id, buildJsonObject {
                    put("tools", JsonArray(resolveTools().map { toolManifest(it) }))
                })
                "tools/call" -> result(id, callTool(params, ::resolveTools, link))
                else -> if (isNotification) null else errorResult(id, -32601, "method not found: $method")
            }

            if (response != null && !isNotification) {
                out.write((ProtoJson.encodeToString(JsonObject.serializer(), response) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    private fun initializeResult(params: JsonObject?): JsonObject {
        val requested = params?.str("protocolVersion") ?: LATEST_PROTOCOL
        return buildJsonObject {
            put("protocolVersion", JsonPrimitive(requested))
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
            put("serverInfo", buildJsonObject {
                put("name", JsonPrimitive("mcpserved"))
                put("version", JsonPrimitive(SERVER_VERSION))
            })
        }
    }

    private fun toolManifest(t: ToolDef): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(t.name))
        put("description", JsonPrimitive(t.description))
        put("inputSchema", t.inputSchema)
    }

    private fun callTool(params: JsonObject?, resolveTools: () -> List<ToolDef>, link: Link): JsonObject {
        val name = params?.str("name")
        val args = (params?.get("arguments") as? JsonObject) ?: buildJsonObject { }
        val tool = resolveTools().firstOrNull { it.name == name }
            ?: return buildJsonObject {
                put("content", buildJsonArray { add(textContent("unknown tool: $name")) })
                put("isError", JsonPrimitive(true))
            }

        return try {
            val text = tool.handler(args, link)
            // Device-level refusals arrive as ordinary text — outcomes the model
            // should reason about, not transport failures the host should hide.
            buildJsonObject { put("content", buildJsonArray { add(textContent(text)) }) }
        } catch (e: Exception) {
            buildJsonObject {
                put("content", buildJsonArray { add(textContent("transport failure: ${e.message ?: e}")) })
                put("isError", JsonPrimitive(true))
            }
        }
    }

    private fun textContent(text: String): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }

    private fun result(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id ?: JsonNull)
        put("result", result)
    }

    private fun errorResult(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        })
    }
}
