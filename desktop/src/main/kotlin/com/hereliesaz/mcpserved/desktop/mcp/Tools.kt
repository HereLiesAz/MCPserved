package com.hereliesaz.mcpserved.desktop.mcp

import com.hereliesaz.mcpserved.desktop.net.Link
import com.hereliesaz.mcpserved.desktop.net.ProtoJson
import com.hereliesaz.mcpserved.desktop.net.boolOr
import com.hereliesaz.mcpserved.desktop.net.errorText
import com.hereliesaz.mcpserved.desktop.net.isOk
import com.hereliesaz.mcpserved.desktop.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Tool surface exposed to the model — a direct port of the reference server's
 * `tools.ts`.
 *
 * The list is built after the device reports its capabilities, so an operation
 * the hardware cannot perform is *absent* rather than present-and-failing. A
 * disabled tool invites the model to try it, read the refusal, and look for a way
 * around it; a tool that was never listed is simply not part of the world.
 *
 * Results are returned as content, never as protocol errors. A denied grant or a
 * lost accessibility binding is information the model needs in order to change
 * course, and an exception thrown at the host discards it.
 */

data class Capabilities(
    val caps: List<String>,
    val root: Boolean,
    val shizuku: Boolean,
    val a11y: Boolean,
)

class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (args: JsonObject, link: Link) -> String,
)

private fun schema(json: String): JsonObject = ProtoJson.parseToJsonElement(json).jsonObject

/** Builds a protocol request: the op plus any arg fields the tool forwards. */
private fun req(op: String, vararg extra: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    put("op", JsonPrimitive(op))
    extra.forEach { (k, v) -> put(k, v) }
}

/** Merge caller args (a JSON schema-shaped object) onto an op for pass-through tools. */
private fun reqWithArgs(op: String, args: JsonObject, keys: List<String>): JsonObject = buildJsonObject {
    put("op", JsonPrimitive(op))
    for (k in keys) args[k]?.let { put(k, it) }
}

private fun now(): Long = System.currentTimeMillis()

/**
 * Appends the foreground-change warning.
 *
 * The single most load-bearing line in any response: the window moved between the
 * permission check and the action, so every node id the model is holding now
 * refers to a layout that is gone.
 */
private fun changedSuffix(res: JsonObject): String {
    if (!res.boolOr("foregroundChanged", false)) return ""
    return "\n\nWARNING: the foreground app changed during this action. " +
        "All node ids are stale — call ui_tree before doing anything else."
}

private fun ack(res: JsonObject, success: String): String =
    if (!res.isOk()) "error: ${res.errorText()}" else "$success${changedSuffix(res)}"

/** Renders a node tree as indented text — a third the tokens of JSON, and easier to read. */
private fun renderTree(res: JsonObject): String {
    if (!res.isOk()) return "error: ${res.errorText()}"

    val nodes = (res["nodes"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    val pruned = res.str("pruned")?.toIntOrNull() ?: 0
    val head = buildList {
        add("package: ${res.str("pkg") ?: ""}")
        res.str("activity")?.let { add("activity: $it") }
        add("${nodes.size} nodes ($pruned pruned)")
    }.joinToString("\n")

    if (nodes.isEmpty()) {
        return "$head\n\n(no addressable nodes — the screen is probably canvas-drawn; " +
            "use screenshot instead)"
    }

    val body = nodes.joinToString("\n") { n ->
        val depth = (n.str("depth")?.toIntOrNull() ?: 0).coerceAtMost(12)
        val indent = "  ".repeat(depth)
        val label = n.str("text") ?: n.str("desc") ?: ""
        val flags = buildList {
            if (n.boolOr("clickable", false)) add("tap")
            if (n.boolOr("editable", false)) add("edit")
            if (n.boolOr("scrollable", false)) add("scroll")
            when ((n["checked"] as? JsonPrimitive)?.let { if (it.content == "null") null else it.content }) {
                "true" -> add("checked")
                "false" -> add("unchecked")
            }
            if (n.str("enabled") == "false") add("disabled")
        }.joinToString(",")
        val bounds = (n["bounds"] as? JsonObject)
        val l = bounds?.str("l")?.toIntOrNull() ?: 0
        val r = bounds?.str("r")?.toIntOrNull() ?: 0
        val t = bounds?.str("t")?.toIntOrNull() ?: 0
        val b = bounds?.str("b")?.toIntOrNull() ?: 0
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val id = n.str("id") ?: ""
        val cls = n.str("cls") ?: ""
        "$indent$id $cls${if (label.isNotEmpty()) " \"$label\"" else ""}" +
            (if (flags.isNotEmpty()) " [$flags]" else "") + " @$cx,$cy"
    }
    return "$head\n\n$body${changedSuffix(res)}"
}

private val ALWAYS: List<ToolDef> = listOf(
    ToolDef(
        name = "capabilities",
        description = "Report which control backends the device has available. Call this first if " +
            "anything behaves unexpectedly — a missing accessibility binding explains most silent failures.",
        inputSchema = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
        handler = { _, link ->
            val r = link.send(req("capabilities"))
            if (!r.isOk()) "error: ${r.errorText()}"
            else {
                val caps = (r["caps"] as? JsonArray)?.joinToString(", ") { it.str() ?: "" } ?: ""
                listOf(
                    "accessibility: ${if (r.boolOr("a11y", false)) "connected" else "NOT CONNECTED"}",
                    "root: ${r.boolOr("root", false)}",
                    "shizuku: ${r.boolOr("shizuku", false)}",
                    "capabilities: $caps",
                ).joinToString("\n")
            }
        },
    ),
    ToolDef(
        name = "session_begin",
        description = "Open a control session. Required before any other operation. The device holds " +
            "its screen awake for the duration, so keep the TTL short and let it lapse rather than " +
            "holding it open across idle periods.",
        inputSchema = schema(
            """{"type":"object","properties":{"ttlSec":{"type":"integer","description":"Session lifetime in seconds (30-1800, default 300).","minimum":30,"maximum":1800}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val ttl = args.str("ttlSec")?.toIntOrNull() ?: 300
            val r = link.send(req("session_begin", "ttlSec" to JsonPrimitive(ttl)))
            if (!r.isOk()) "error: ${r.errorText()}"
            else {
                val secs = ((r.str("expiresAtEpochMs")?.toLongOrNull() ?: now()) - now()) / 1000
                "session ${r.str("sessionId")} open, expires in ${secs}s"
            }
        },
    ),
    ToolDef(
        name = "session_end",
        description = "Close the session and release the device's screen. Call this when the task is " +
            "done rather than leaving it to expire.",
        inputSchema = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
        handler = { _, link -> ack(link.send(req("session_end")), "session closed") },
    ),
    ToolDef(
        name = "grants_list",
        description = "List which packages the user has authorized and with what scopes. Nothing " +
            "outside this list can be observed or touched.",
        inputSchema = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
        handler = { _, link ->
            val r = link.send(req("grants_list"))
            if (!r.isOk()) return@ToolDef "error: ${r.errorText()}"
            val grants = (r["grants"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            if (grants.isEmpty()) "no grants — the user has not authorized any package"
            else grants.joinToString("\n") { g ->
                val scopes = (g["scopes"] as? JsonArray)?.joinToString(", ") { it.str() ?: "" } ?: ""
                val exp = g.str("expiresAtEpochMs")?.toLongOrNull()
                    ?.let { " (expires in ${(it - now()) / 1000}s)" } ?: ""
                "${g.str("pkg")}: $scopes$exp"
            }
        },
    ),
    ToolDef(
        name = "apps_list",
        description = "List installed applications. Defaults to authorized packages only.",
        inputSchema = schema(
            """{"type":"object","properties":{"grantedOnly":{"type":"boolean","description":"When false, lists every launchable app. Prefer the default — a full inventory is disclosure the task rarely needs."}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val grantedOnly = args.boolOr("grantedOnly", true)
            val r = link.send(req("apps_list", "grantedOnly" to JsonPrimitive(grantedOnly)))
            if (!r.isOk()) return@ToolDef "error: ${r.errorText()}"
            val apps = (r["apps"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            apps.joinToString("\n") { a ->
                "${a.str("pkg")} — ${a.str("label")}${if (a.boolOr("granted", false)) " [granted]" else ""}"
            }
        },
    ),
    ToolDef(
        name = "ui_tree",
        description = "Read the current screen as a node tree. This is the primary way to see the " +
            "device — prefer it over screenshot, which costs far more and conveys less. Node ids " +
            "survive scrolling but not layout changes.",
        inputSchema = schema(
            """{"type":"object","properties":{"maxDepth":{"type":"integer","description":"Maximum tree depth to walk (default 40).","minimum":1,"maximum":100}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val maxDepth = args.str("maxDepth")?.toIntOrNull() ?: 40
            renderTree(link.send(req("ui_tree", "maxDepth" to JsonPrimitive(maxDepth))))
        },
    ),
    ToolDef(
        name = "screenshot",
        description = "Capture the screen as an image. Use only when ui_tree returns no addressable " +
            "nodes — games, canvas-drawn UI, and some WebViews.",
        inputSchema = schema(
            """{"type":"object","properties":{"maxPx":{"type":"integer","description":"Longest edge in pixels (default 768).","minimum":256,"maximum":2048}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val maxPx = args.str("maxPx")?.toIntOrNull() ?: 768
            val r = link.send(req("screenshot", "maxPx" to JsonPrimitive(maxPx)))
            if (!r.isOk()) "error: ${r.errorText()}"
            else "[image ${r.str("w")}x${r.str("h")} ${r.str("mime")}]\n${r.str("b64")}${changedSuffix(r)}"
        },
    ),
    ToolDef(
        name = "notifications",
        description = "Read the notification shade, filtered to authorized packages.",
        inputSchema = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
        handler = { _, link ->
            val r = link.send(req("notifications"))
            if (!r.isOk()) return@ToolDef "error: ${r.errorText()}"
            val items = (r["items"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            if (items.isEmpty()) "no notifications from authorized packages"
            else items.joinToString("\n") { n -> "${n.str("pkg")}: ${n.str("title") ?: ""} — ${n.str("text") ?: ""}" }
        },
    ),
    ToolDef(
        name = "tap",
        description = "Tap a node by id, or a raw coordinate. Prefer the node id; coordinates computed " +
            "from an older tree will miss after any scroll.",
        inputSchema = schema(
            """{"type":"object","properties":{"nodeId":{"type":"string","description":"Node id from ui_tree."},"x":{"type":"integer"},"y":{"type":"integer"}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link -> ack(link.send(reqWithArgs("tap", args, listOf("nodeId", "x", "y"))), "tapped") },
    ),
    ToolDef(
        name = "long_press",
        description = "Press and hold a node or coordinate.",
        inputSchema = schema(
            """{"type":"object","properties":{"nodeId":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"},"ms":{"type":"integer","description":"Hold duration (default 500)."}},"required":[],"additionalProperties":false}""",
        ),
        handler = { args, link -> ack(link.send(reqWithArgs("long_press", args, listOf("nodeId", "x", "y", "ms"))), "held") },
    ),
    ToolDef(
        name = "swipe",
        description = "Swipe between two coordinates. For scrolling a list, prefer the scroll tool — it " +
            "uses the view's own scroll action and respects nesting.",
        inputSchema = schema(
            """{"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"ms":{"type":"integer","description":"Duration (default 300)."}},"required":["x1","y1","x2","y2"],"additionalProperties":false}""",
        ),
        handler = { args, link -> ack(link.send(reqWithArgs("swipe", args, listOf("x1", "y1", "x2", "y2", "ms"))), "swiped") },
    ),
    ToolDef(
        name = "scroll",
        description = "Scroll a scrollable node in a direction.",
        inputSchema = schema(
            """{"type":"object","properties":{"nodeId":{"type":"string","description":"A node marked [scroll]."},"dir":{"type":"string","enum":["UP","DOWN","LEFT","RIGHT"]}},"required":["nodeId","dir"],"additionalProperties":false}""",
        ),
        handler = { args, link -> ack(link.send(reqWithArgs("scroll", args, listOf("nodeId", "dir"))), "scrolled") },
    ),
    ToolDef(
        name = "type",
        description = "Type text into an editable field. Targets the focused field when no node id is given.",
        inputSchema = schema(
            """{"type":"object","properties":{"text":{"type":"string"},"nodeId":{"type":"string","description":"A node marked [edit]."}},"required":["text"],"additionalProperties":false}""",
        ),
        handler = { args, link -> ack(link.send(reqWithArgs("type", args, listOf("text", "nodeId"))), "typed") },
    ),
    ToolDef(
        name = "key",
        description = "Press a global key.",
        inputSchema = schema(
            """{"type":"object","properties":{"key":{"type":"string","enum":["BACK","HOME","RECENTS","ENTER","DELETE","NOTIFICATIONS"]}},"required":["key"],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val key = args.str("key") ?: ""
            ack(link.send(req("key", "key" to JsonPrimitive(key))), "pressed $key")
        },
    ),
    ToolDef(
        name = "launch",
        description = "Bring an application to the foreground. Requires a LAUNCH grant for the target, " +
            "not for the current app.",
        inputSchema = schema(
            """{"type":"object","properties":{"pkg":{"type":"string"}},"required":["pkg"],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val pkg = args.str("pkg") ?: ""
            ack(link.send(req("launch", "pkg" to JsonPrimitive(pkg))), "launched $pkg")
        },
    ),
    ToolDef(
        name = "clipboard_get",
        description = "Read the clipboard. Requires root or Shizuku — Android forbids background " +
            "clipboard reads outright.",
        inputSchema = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
        handler = { _, link ->
            val r = link.send(req("clipboard_get"))
            if (!r.isOk()) "error: ${r.errorText()}" else "${r.str("text")}${changedSuffix(r)}"
        },
    ),
    ToolDef(
        name = "clipboard_set",
        description = "Write the clipboard.",
        inputSchema = schema(
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            ack(link.send(reqWithArgs("clipboard_set", args, listOf("text"))), "clipboard set")
        },
    ),
)

/**
 * Shell, listed only when a privileged backend exists.
 *
 * Separated from [ALWAYS] because its absence is the point. On an unprivileged
 * device this tool never appears in the manifest at all, and no amount of
 * reasoning about it will produce a call.
 */
private val PRIVILEGED: List<ToolDef> = listOf(
    ToolDef(
        name = "shell",
        description = "Run a shell command. Reaches every package at once, so it is logged and " +
            "bracketed like any other action. Requires a SHELL grant.",
        inputSchema = schema(
            """{"type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"],"additionalProperties":false}""",
        ),
        handler = { args, link ->
            val r = link.send(reqWithArgs("shell", args, listOf("cmd")))
            if (!r.isOk()) "error: ${r.errorText()}" else "${r.str("text")}${changedSuffix(r)}"
        },
    ),
)

/** Builds the tool list appropriate to a device's reported capabilities. */
fun buildTools(caps: Capabilities): List<ToolDef> {
    val privileged = caps.root || caps.shizuku
    return if (privileged) ALWAYS + PRIVILEGED else ALWAYS
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.let { if (it.content == "null" && !it.isString) null else it.content }
