package com.hereliesaz.mcpserved.transport

import com.hereliesaz.mcpserved.service.Dispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Speaks MCP on the device itself, so a model's host connects straight to the
 * phone with no desktop process in the path.
 *
 * This is the same tool surface the desktop bridge (`mcp/src/tools.ts`) exposes,
 * ported to the device and wired to the local [Dispatcher] rather than to a
 * loopback socket. The two are deliberately kept identical: a model should see one
 * MCPserved whether it reached the device directly over HTTP or through the
 * desktop server's `adb` tunnel. Tool names are the wire [Request] discriminators
 * one-to-one, so an MCP `tools/call` is decoded into a [Request] with the very
 * serializers the desktop path uses.
 *
 * The rendering — the indented tree, the foreground-changed warning, the ack
 * strings — is duplicated here rather than shared, for the same reason the crypto
 * is: the Kotlin and TypeScript runtimes cannot import from each other, and a
 * model reads structure from these exact shapes. Any change to one must be mirrored
 * in the other.
 *
 * @param dispatcher the device-side request handler (session gating, grant
 *   bracketing, and mechanism all live behind it)
 * @param privileged whether a root or Shizuku backend exists, which decides
 *   whether the `shell` tool is listed at all
 */
class McpBridge(
    private val dispatcher: Dispatcher,
    private val privileged: () -> Boolean,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "op"
    }

    // ---- MCP lifecycle ------------------------------------------------------

    /** The `initialize` result: protocol version, offered capabilities, identity. */
    fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        put("serverInfo", buildJsonObject {
            put("name", "mcpserved")
            put("version", SERVER_VERSION)
        })
    }

    /** The `tools/list` result, shaped to the device's privilege level. */
    fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            alwaysTools.forEach { add(it) }
            if (privileged()) add(shellTool)
        }
    }

    /**
     * Handles a `tools/call`.
     *
     * Decodes the tool name and arguments into a [Request], dispatches it, and
     * renders the [Response] into MCP content. Device-level refusals (no session,
     * an absent grant) come back as ordinary content text, never as a protocol
     * error — they are outcomes the model must reason about, and an error thrown at
     * the host would discard them. Only a malformed call or a thrown dispatch is
     * flagged `isError`.
     */
    suspend fun toolsCall(params: JsonObject?): JsonObject {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return errorResult("missing tool name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        val reqJson = buildJsonObject {
            put("op", name)
            arguments.forEach { (k, v) -> put(k, v) }
        }
        val request = try {
            json.decodeFromJsonElement(Request.serializer(), reqJson)
        } catch (e: Exception) {
            return errorResult("invalid arguments for $name: ${e.message}")
        }

        val resp = try {
            dispatcher.handle(request)
        } catch (e: Exception) {
            return errorResult(e.message ?: "dispatch failed")
        }

        return buildJsonObject {
            put("content", renderContent(name, arguments, resp))
            put("isError", false)
        }
    }

    // ---- rendering (mirror of mcp/src/tools.ts) -----------------------------

    private fun renderContent(name: String, args: JsonObject, resp: Response): JsonArray {
        if (name == "screenshot") {
            val img = resp as? Response.Image ?: return textArray(errText(resp))
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "image")
                    put("data", img.b64)
                    put("mimeType", img.mime)
                })
                val suffix = changedSuffix(img.foregroundChanged)
                if (suffix.isNotEmpty()) add(textBlock("image ${img.w}x${img.h}$suffix"))
            }
        }
        return textArray(renderText(name, args, resp))
    }

    private fun renderText(name: String, args: JsonObject, resp: Response): String = when (name) {
        "capabilities" -> (resp as? Response.Capabilities)?.let {
            "accessibility: ${if (it.a11y) "connected" else "NOT CONNECTED"}\n" +
                "root: ${it.root}\nshizuku: ${it.shizuku}\n" +
                "capabilities: ${it.caps.joinToString(", ")}"
        } ?: errText(resp)

        "session_begin" -> (resp as? Response.Session)?.let {
            val secs = (it.expiresAtEpochMs - System.currentTimeMillis()) / 1000
            "session ${it.sessionId} open, expires in ${secs}s"
        } ?: errText(resp)

        "session_end" -> ack(resp, "session closed")

        "grants_list" -> (resp as? Response.Grants)?.let { g ->
            if (g.grants.isEmpty()) "no grants — the user has not authorized any package"
            else g.grants.joinToString("\n") { gr ->
                val exp = gr.expiresAtEpochMs
                    ?.let { " (expires in ${(it - System.currentTimeMillis()) / 1000}s)" }
                    ?: ""
                "${gr.pkg}: ${gr.scopes.joinToString(", ")}$exp"
            }
        } ?: errText(resp)

        "apps_list" -> (resp as? Response.Apps)?.let { a ->
            if (a.apps.isEmpty()) "no applications"
            else a.apps.joinToString("\n") {
                "${it.pkg} — ${it.label}${if (it.granted) " [granted]" else ""}"
            }
        } ?: errText(resp)

        "ui_tree" -> (resp as? Response.Tree)?.let { renderTree(it) } ?: errText(resp)

        "notifications" -> (resp as? Response.Notifications)?.let { n ->
            if (n.items.isEmpty()) "no notifications from authorized packages"
            else n.items.joinToString("\n") { "${it.pkg}: ${it.title ?: ""} — ${it.text ?: ""}" }
        } ?: errText(resp)

        "clipboard_get" -> (resp as? Response.Text)?.let { it.text + changedSuffix(it.foregroundChanged) }
            ?: errText(resp)
        "shell" -> (resp as? Response.Text)?.let { it.text + changedSuffix(it.foregroundChanged) }
            ?: errText(resp)

        "tap" -> ack(resp, "tapped")
        "long_press" -> ack(resp, "held")
        "swipe" -> ack(resp, "swiped")
        "scroll" -> ack(resp, "scrolled")
        "type" -> ack(resp, "typed")
        "key" -> ack(resp, "pressed ${(args["key"] as? JsonPrimitive)?.contentOrNull ?: ""}")
        "launch" -> ack(resp, "launched ${(args["pkg"] as? JsonPrimitive)?.contentOrNull ?: ""}")
        "clipboard_set" -> ack(resp, "clipboard set")

        else -> errText(resp)
    }

    private fun renderTree(t: Response.Tree): String {
        val head = buildList {
            add("package: ${t.pkg}")
            t.activity?.let { add("activity: $it") }
            add("${t.nodes.size} nodes (${t.pruned} pruned)")
        }.joinToString("\n")

        if (t.nodes.isEmpty()) {
            return "$head\n\n(no addressable nodes — the screen is probably canvas-drawn; " +
                "use screenshot instead)"
        }

        val body = t.nodes.joinToString("\n") { n ->
            val indent = "  ".repeat(minOf(n.depth, 12))
            val label = n.text ?: n.desc ?: ""
            val flags = listOfNotNull(
                if (n.clickable) "tap" else null,
                if (n.editable) "edit" else null,
                if (n.scrollable) "scroll" else null,
                if (n.checked == true) "checked" else null,
                if (n.checked == false) "unchecked" else null,
                if (!n.enabled) "disabled" else null,
            ).joinToString(",")
            val labelPart = if (label.isNotEmpty()) " \"$label\"" else ""
            val flagsPart = if (flags.isNotEmpty()) " [$flags]" else ""
            "$indent${n.id} ${n.cls}$labelPart$flagsPart @${n.bounds.centerX},${n.bounds.centerY}"
        }

        return "$head\n\n$body${changedSuffix(t.foregroundChanged)}"
    }

    private fun ack(resp: Response, success: String): String =
        if (!resp.ok) "error: ${resp.error}" else success + changedSuffix(resp.foregroundChanged)

    private fun errText(resp: Response): String = "error: ${resp.error ?: "unexpected response"}"

    private fun changedSuffix(changed: Boolean): String =
        if (!changed) ""
        else "\n\nWARNING: the foreground app changed during this action. " +
            "All node ids are stale — call ui_tree before doing anything else."

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun textArray(text: String): JsonArray = buildJsonArray { add(textBlock(text)) }

    private fun errorResult(msg: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray { add(textBlock("error: $msg")) })
        put("isError", true)
    }

    // ---- tool manifest (mirror of mcp/src/tools.ts) -------------------------

    private fun tool(name: String, description: String, inputSchema: JsonObject): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", inputSchema)
        }

    private fun objSchema(
        required: List<String> = emptyList(),
        props: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(props))
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.intProp(
        key: String,
        description: String? = null,
        min: Int? = null,
        max: Int? = null,
    ) = put(key, buildJsonObject {
        put("type", "integer")
        if (description != null) put("description", description)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    })

    private fun JsonObjectBuilder.strProp(key: String, description: String? = null) =
        put(key, buildJsonObject {
            put("type", "string")
            if (description != null) put("description", description)
        })

    private fun JsonObjectBuilder.boolProp(key: String, description: String? = null) =
        put(key, buildJsonObject {
            put("type", "boolean")
            if (description != null) put("description", description)
        })

    private fun JsonObjectBuilder.enumProp(key: String, values: List<String>) =
        put(key, buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(it) } }
        })

    private val alwaysTools: List<JsonObject> by lazy {
        listOf(
            tool(
                "capabilities",
                "Report which control backends the device has available. Call this first " +
                    "if anything behaves unexpectedly — a missing accessibility binding " +
                    "explains most silent failures.",
                objSchema(),
            ),
            tool(
                "session_begin",
                "Open a control session. Required before any other operation. The device " +
                    "holds its screen awake for the duration, so keep the TTL short and let " +
                    "it lapse rather than holding it open across idle periods.",
                objSchema {
                    intProp("ttlSec", "Session lifetime in seconds (30-1800, default 300).", 30, 1800)
                },
            ),
            tool(
                "session_end",
                "Close the session and release the device's screen. Call this when the " +
                    "task is done rather than leaving it to expire.",
                objSchema(),
            ),
            tool(
                "grants_list",
                "List which packages the user has authorized and with what scopes. " +
                    "Nothing outside this list can be observed or touched.",
                objSchema(),
            ),
            tool(
                "apps_list",
                "List installed applications. Defaults to authorized packages only.",
                objSchema {
                    boolProp(
                        "grantedOnly",
                        "When false, lists every launchable app. Prefer the default — a full " +
                            "inventory is disclosure the task rarely needs.",
                    )
                },
            ),
            tool(
                "ui_tree",
                "Read the current screen as a node tree. This is the primary way to see " +
                    "the device — prefer it over screenshot, which costs far more and conveys " +
                    "less. Node ids survive scrolling but not layout changes.",
                objSchema {
                    intProp("maxDepth", "Maximum tree depth to walk (default 40).", 1, 100)
                },
            ),
            tool(
                "screenshot",
                "Capture the screen as an image. Use only when ui_tree returns no " +
                    "addressable nodes — games, canvas-drawn UI, and some WebViews.",
                objSchema {
                    intProp("maxPx", "Longest edge in pixels (default 768).", 256, 2048)
                },
            ),
            tool(
                "notifications",
                "Read the notification shade, filtered to authorized packages.",
                objSchema(),
            ),
            tool(
                "tap",
                "Tap a node by id, or a raw coordinate. Prefer the node id; coordinates " +
                    "computed from an older tree will miss after any scroll.",
                objSchema {
                    strProp("nodeId", "Node id from ui_tree.")
                    intProp("x")
                    intProp("y")
                },
            ),
            tool(
                "long_press",
                "Press and hold a node or coordinate.",
                objSchema {
                    strProp("nodeId")
                    intProp("x")
                    intProp("y")
                    intProp("ms", "Hold duration (default 500).")
                },
            ),
            tool(
                "swipe",
                "Swipe between two coordinates. For scrolling a list, prefer the scroll " +
                    "tool — it uses the view's own scroll action and respects nesting.",
                objSchema(listOf("x1", "y1", "x2", "y2")) {
                    intProp("x1")
                    intProp("y1")
                    intProp("x2")
                    intProp("y2")
                    intProp("ms", "Duration (default 300).")
                },
            ),
            tool(
                "scroll",
                "Scroll a scrollable node in a direction.",
                objSchema(listOf("nodeId", "dir")) {
                    strProp("nodeId", "A node marked [scroll].")
                    enumProp("dir", listOf("UP", "DOWN", "LEFT", "RIGHT"))
                },
            ),
            tool(
                "type",
                "Type text into an editable field. Targets the focused field when no " +
                    "node id is given.",
                objSchema(listOf("text")) {
                    strProp("text")
                    strProp("nodeId", "A node marked [edit].")
                },
            ),
            tool(
                "key",
                "Press a global key.",
                objSchema(listOf("key")) {
                    enumProp("key", listOf("BACK", "HOME", "RECENTS", "ENTER", "DELETE", "NOTIFICATIONS"))
                },
            ),
            tool(
                "launch",
                "Bring an application to the foreground. Requires a LAUNCH grant for the " +
                    "target, not for the current app.",
                objSchema(listOf("pkg")) { strProp("pkg") },
            ),
            tool(
                "clipboard_get",
                "Read the clipboard. Requires root or Shizuku — Android forbids " +
                    "background clipboard reads outright.",
                objSchema(),
            ),
            tool(
                "clipboard_set",
                "Write the clipboard.",
                objSchema(listOf("text")) { strProp("text") },
            ),
        )
    }

    private val shellTool: JsonObject by lazy {
        tool(
            "shell",
            "Run a shell command. Reaches every package at once, so it is logged and " +
                "bracketed like any other action. Requires a SHELL grant.",
            objSchema(listOf("cmd")) { strProp("cmd") },
        )
    }

    private companion object {
        /** MCP protocol revision advertised on initialize. */
        const val PROTOCOL_VERSION = "2024-11-05"

        /** Kept in step with the desktop package version. */
        const val SERVER_VERSION = "0.3.0"
    }
}
