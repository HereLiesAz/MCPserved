package com.hereliesaz.mcpserved.desktop.net

import com.hereliesaz.mcpserved.desktop.adb.Adb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/**
 * Controls the device purely through `adb`, no on-device app required.
 *
 * This is the quick-connect path: enable USB debugging (or adb-over-Wi-Fi), plug
 * in, and a model can drive the phone. Every protocol op is answered by shelling
 * out — `input` for gestures and keys, `uiautomator dump` for the tree,
 * `screencap` for pixels — and shaped into the same response the on-device app
 * would return, so the tool layer never learns which backend it is talking to.
 *
 * What adb cannot honestly do, it says so about rather than faking. There is no
 * per-app grant model here: adb holds shell-level authority over the whole
 * device, disclosed in the capability report and the session notice rather than
 * dressed up as something narrower.
 */
class AdbLink : Link {
    override val label: String = "adb (device-wide shell authority)"

    /** Centres and bounds of the nodes from the last ui_tree, so taps address by id. */
    private val nodeCenters = HashMap<String, IntArray>()   // id -> [x, y]
    private val nodeBounds = HashMap<String, Bounds>()

    override fun close() {
        // Nothing persistent to close; every call spawns adb afresh.
    }

    override fun send(request: JsonObject, timeoutMs: Long): JsonObject {
        return try {
            when (request.opOr()) {
                "capabilities" -> capabilities()
                "session_begin" -> sessionBegin(request.intOr("ttlSec", 300))
                "session_end" -> ok("foregroundChanged" to JsonPrimitive(false))
                "grants_list" -> ok("grants" to JsonArray(emptyList()))
                "apps_list" -> appsList(request.boolOr("grantedOnly", true))
                "ui_tree" -> uiTree()
                "screenshot" -> screenshot()
                "notifications" -> notifications()
                "tap" -> tap(request)
                "long_press" -> longPress(request)
                "swipe" -> swipe(request)
                "scroll" -> scroll(request)
                "type" -> type(request)
                "key" -> key(request.str("key") ?: "")
                "launch" -> launch(request.str("pkg") ?: "")
                "shell" -> shell(request.str("cmd") ?: "")
                "clipboard_get", "clipboard_set" -> err(
                    "clipboard is not available over adb — pair the on-device app for clipboard access",
                )
                else -> err("unsupported op: ${request.opOr()}")
            }
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            if (message.contains("ENOENT")) {
                err("adb not found on PATH — install platform-tools, or set MCPSERVED_ADB to the adb binary")
            } else {
                err(message)
            }
        }
    }

    // ---- capabilities & session -------------------------------------------

    private fun capabilities(): JsonObject {
        if (!Adb.ready()) {
            return err(
                "no adb device — attach over USB and run `adb devices`, or `adb connect <ip>:5555` for Wi-Fi",
            )
        }

        var root = false
        try {
            val id = Adb.shellText("su -c id", timeoutMs = 5_000)
            root = Regex("uid=0").containsMatchIn(id)
        } catch (_: Exception) {
            root = false
        }

        // adb shell is itself a shell-level backend; report it so the shell tool
        // is offered. "shizuku" here stands for that ADB-level authority.
        val caps = mutableListOf(
            "TREE", "GESTURE", "TEXT_INPUT", "GLOBAL_KEYS", "CAPTURE_SILENT", "NOTIFICATIONS", "SHELL_SHIZUKU",
        )
        if (root) caps.add("SHELL_ROOT")

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("caps", buildJsonArray { caps.forEach { add(it) } })
            put("root", JsonPrimitive(root))
            put("shizuku", JsonPrimitive(true))
            put("a11y", JsonPrimitive(false))
        }
    }

    private fun sessionBegin(ttlSec: Int): JsonObject {
        try {
            Adb.shell("input keyevent 224", timeoutMs = 5_000) // WAKEUP
            Adb.shell("wm dismiss-keyguard", timeoutMs = 5_000)
        } catch (_: Exception) {
            // A device that will not wake is still worth trying to drive.
        }
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("sessionId", JsonPrimitive("adb"))
            put("expiresAtEpochMs", JsonPrimitive(System.currentTimeMillis() + ttlSec * 1000L))
            put("foregroundChanged", JsonPrimitive(false))
        }
    }

    // ---- reading ----------------------------------------------------------

    private fun appsList(grantedOnly: Boolean): JsonObject {
        val flag = if (grantedOnly) " -3" else ""
        val out = Adb.shellText("pm list packages$flag")
        val apps = out.split("\n").map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .map { pkg ->
                buildJsonObject {
                    put("pkg", JsonPrimitive(pkg))
                    put("label", JsonPrimitive(pkg))
                    put("granted", JsonPrimitive(true))
                }
            }
        return ok("apps" to JsonArray(apps))
    }

    private fun uiTree(): JsonObject {
        var dumpPath = "/sdcard/window_dump.xml"
        try {
            val out = Adb.shellText("uiautomator dump", timeoutMs = 15_000)
            Regex("""dumped to:\s*(\S+)""").find(out)?.let { dumpPath = it.groupValues[1] }
        } catch (e: Exception) {
            return err("uiautomator dump failed: ${e.message}")
        }

        val xmlRes = Adb.shell("cat ${Adb.shellQuote(dumpPath)}", binary = true, timeoutMs = 15_000)
        if (xmlRes.code != 0) return err("could not read the ui dump")
        val xml = String(xmlRes.stdout, Charsets.UTF_8)

        val parsed = parseUiAutomator(xml)
        nodeCenters.clear()
        nodeBounds.clear()
        for (n in parsed.nodes) {
            nodeCenters[n.id] = intArrayOf((n.bounds.l + n.bounds.r) / 2, (n.bounds.t + n.bounds.b) / 2)
            nodeBounds[n.id] = n.bounds
        }

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("pkg", JsonPrimitive(parsed.pkg))
            put("activity", JsonPrimitive(null as String?))
            put("nodes", JsonArray(parsed.nodes.map { it.toJson() }))
            put("pruned", JsonPrimitive(parsed.pruned))
            put("foregroundChanged", JsonPrimitive(false))
        }
    }

    private fun screenshot(): JsonObject {
        val res = Adb.shell("screencap -p", binary = true, timeoutMs = 20_000)
        if (res.code != 0 || res.stdout.isEmpty()) return err("screencap failed")
        val png = res.stdout
        val (w, h) = pngSize(png)
        // maxPx is not honoured: adb has no image scaler on the host side. The raw
        // frame is returned; the tool description already steers toward ui_tree.
        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("mime", JsonPrimitive("image/png"))
            put("b64", JsonPrimitive(Base64.getEncoder().encodeToString(png)))
            put("w", JsonPrimitive(w))
            put("h", JsonPrimitive(h))
            put("foregroundChanged", JsonPrimitive(false))
        }
    }

    private fun notifications(): JsonObject {
        val out = try {
            Adb.shellText("dumpsys notification --noredact", timeoutMs = 10_000)
        } catch (_: Exception) {
            return ok("items" to JsonArray(emptyList()))
        }

        val items = mutableListOf<JsonObject>()
        val blocks = out.split("NotificationRecord(").drop(1)
        for (block in blocks) {
            val pkg = Regex("""pkg=(\S+)""").find(block)?.groupValues?.get(1) ?: ""
            if (pkg.isEmpty()) continue
            val title = Regex("""android\.title=String \(([^)]*)\)""").find(block)?.groupValues?.get(1)
            val text = Regex("""android\.text=String \(([^)]*)\)""").find(block)?.groupValues?.get(1)
            items.add(buildJsonObject {
                put("pkg", JsonPrimitive(pkg))
                put("key", JsonPrimitive(pkg))
                put("title", JsonPrimitive(title))
                put("text", JsonPrimitive(text))
                put("postedAtEpochMs", JsonPrimitive(0))
            })
        }
        return ok("items" to JsonArray(items))
    }

    // ---- acting -----------------------------------------------------------

    private fun resolvePoint(req: JsonObject): IntArray? {
        req.str("nodeId")?.let { return nodeCenters[it] }
        val x = req.str("x")?.toIntOrNull()
        val y = req.str("y")?.toIntOrNull()
        return if (x != null && y != null) intArrayOf(x, y) else null
    }

    private fun tap(req: JsonObject): JsonObject {
        val p = resolvePoint(req) ?: return err("no node id (call ui_tree first) or x,y given")
        Adb.shellText("input tap ${p[0]} ${p[1]}")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun longPress(req: JsonObject): JsonObject {
        val p = resolvePoint(req) ?: return err("no node id or x,y given")
        val ms = req.intOr("ms", 500)
        Adb.shellText("input swipe ${p[0]} ${p[1]} ${p[0]} ${p[1]} $ms")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun swipe(req: JsonObject): JsonObject {
        val ms = req.intOr("ms", 300)
        val x1 = req.intOr("x1", 0); val y1 = req.intOr("y1", 0)
        val x2 = req.intOr("x2", 0); val y2 = req.intOr("y2", 0)
        Adb.shellText("input swipe $x1 $y1 $x2 $y2 $ms")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun scroll(req: JsonObject): JsonObject {
        val b = req.str("nodeId")?.let { nodeBounds[it] } ?: return err("unknown node id — call ui_tree first")
        val cx = (b.l + b.r) / 2
        val cy = (b.t + b.b) / 2
        val w = b.r - b.l
        val h = b.b - b.t
        var x1 = cx; var y1 = cy; var x2 = cx; var y2 = cy
        when (req.str("dir")) {
            "DOWN" -> { y1 = b.t + (h * 0.7).toInt(); y2 = b.t + (h * 0.3).toInt() }
            "UP" -> { y1 = b.t + (h * 0.3).toInt(); y2 = b.t + (h * 0.7).toInt() }
            "RIGHT" -> { x1 = b.l + (w * 0.7).toInt(); x2 = b.l + (w * 0.3).toInt() }
            "LEFT" -> { x1 = b.l + (w * 0.3).toInt(); x2 = b.l + (w * 0.7).toInt() }
            else -> return err("unknown direction: ${req.str("dir")}")
        }
        Adb.shellText("input swipe $x1 $y1 $x2 $y2 300")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun type(req: JsonObject): JsonObject {
        req.str("nodeId")?.let { id -> nodeCenters[id]?.let { Adb.shellText("input tap ${it[0]} ${it[1]}") } }
        // `input text` takes spaces as %s and cannot express newlines or most
        // non-ASCII. Good enough for field entry; the app path handles the rest.
        val encoded = (req.str("text") ?: "").replace(" ", "%s")
        Adb.shellText("input text ${Adb.shellQuote(encoded)}")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun key(key: String): JsonObject {
        if (key == "NOTIFICATIONS") {
            Adb.shellText("cmd statusbar expand-notifications")
            return ok("foregroundChanged" to JsonPrimitive(false))
        }
        val code = mapOf(
            "BACK" to "4", "HOME" to "3", "RECENTS" to "187", "ENTER" to "66", "DELETE" to "67",
        )[key] ?: return err("unknown key: $key")
        Adb.shellText("input keyevent $code")
        return ok("foregroundChanged" to JsonPrimitive(false))
    }

    private fun launch(pkg: String): JsonObject {
        val r = Adb.shell(
            "monkey -p ${Adb.shellQuote(pkg)} -c android.intent.category.LAUNCHER 1",
            timeoutMs = 10_000,
        )
        if (r.code != 0) return err("could not launch $pkg")
        return ok("foregroundChanged" to JsonPrimitive(true))
    }

    private fun shell(cmd: String): JsonObject {
        val r = Adb.shell(cmd, timeoutMs = 30_000)
        val text = (String(r.stdout, Charsets.UTF_8) + if (r.stderr.isNotEmpty()) "\n${r.stderr}" else "").trim()
        return buildJsonObject {
            put("ok", JsonPrimitive(r.code == 0))
            put("error", JsonPrimitive(if (r.code == 0) null else text.ifEmpty { "exited ${r.code}" }))
            put("text", JsonPrimitive(text))
        }
    }
}

// ---- parsing helpers ------------------------------------------------------

private data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int)

private class AdbNode(
    val id: String,
    val cls: String,
    val bounds: Bounds,
    val depth: Int,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val enabled: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("cls", JsonPrimitive(cls))
        put("bounds", buildJsonObject {
            put("l", JsonPrimitive(bounds.l)); put("t", JsonPrimitive(bounds.t))
            put("r", JsonPrimitive(bounds.r)); put("b", JsonPrimitive(bounds.b))
        })
        put("depth", JsonPrimitive(depth))
        put("text", JsonPrimitive(text))
        put("desc", JsonPrimitive(desc))
        put("clickable", JsonPrimitive(clickable))
        put("editable", JsonPrimitive(editable))
        put("scrollable", JsonPrimitive(scrollable))
        put("checked", JsonPrimitive(checked))
        put("enabled", JsonPrimitive(enabled))
    }
}

private class ParsedTree(val pkg: String, val nodes: List<AdbNode>, val pruned: Int)

private fun decodeEntities(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

private fun parseBounds(s: String?): Bounds? {
    val m = s?.let { Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(it) } ?: return null
    val (a, b, c, d) = m.destructured
    return Bounds(a.toInt(), b.toInt(), c.toInt(), d.toInt())
}

/**
 * Parses a uiautomator hierarchy dump into the flat node list the tools expect.
 *
 * Pure layout containers — nodes with no text, no content description, and no
 * interactive flag — are dropped and counted, matching what the on-device Pruner
 * does, so the tree the model reads is interaction, not scaffolding.
 */
private fun parseUiAutomator(xml: String): ParsedTree {
    val nodes = mutableListOf<AdbNode>()
    var pruned = 0
    var pkg = ""
    var depth = 0
    var seq = 0

    val tag = Regex("""<node\b([^>]*?)(/?)>|</node>""")
    val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    for (m in tag.findAll(xml)) {
        if (m.value == "</node>") {
            depth = maxOf(0, depth - 1)
            continue
        }
        val attrsRaw = m.groupValues[1]
        val selfClosing = m.groupValues[2] == "/"
        val attrs = HashMap<String, String>()
        for (a in attrRe.findAll(attrsRaw)) attrs[a.groupValues[1]] = decodeEntities(a.groupValues[2])

        val bounds = parseBounds(attrs["bounds"])
        val thisDepth = depth
        if (!selfClosing) depth += 1
        if (bounds == null) continue
        if (pkg.isEmpty() && attrs["package"] != null) pkg = attrs["package"]!!

        val text = attrs["text"]?.ifEmpty { null }
        val desc = attrs["content-desc"]?.ifEmpty { null }
        val clickable = attrs["clickable"] == "true"
        val longClickable = attrs["long-clickable"] == "true"
        val scrollable = attrs["scrollable"] == "true"
        val checkable = attrs["checkable"] == "true"
        val rawCls = attrs["class"] ?: ""
        val cls = rawCls.substringAfterLast(".").ifEmpty { rawCls.ifEmpty { "View" } }
        val editable = Regex("EditText", RegexOption.IGNORE_CASE).containsMatchIn(rawCls)

        val interactive = clickable || longClickable || scrollable || checkable || editable
        if (!interactive && text == null && desc == null) { pruned += 1; continue }
        if (bounds.r - bounds.l <= 0 || bounds.b - bounds.t <= 0) { pruned += 1; continue }

        val rid = attrs["resource-id"]
        val idBase = if (!rid.isNullOrEmpty()) rid.substringAfterLast("/").ifEmpty { rid } else cls.lowercase()
        val id = "$idBase#${seq++}"

        nodes.add(
            AdbNode(
                id = id, cls = cls, bounds = bounds, depth = thisDepth,
                text = text, desc = desc, clickable = clickable, editable = editable,
                scrollable = scrollable,
                checked = if (checkable) attrs["checked"] == "true" else null,
                enabled = attrs["enabled"] != "false",
            ),
        )
    }
    return ParsedTree(pkg, nodes, pruned)
}

/** Reads width and height from a PNG's IHDR chunk. */
private fun pngSize(png: ByteArray): Pair<Int, Int> {
    if (png.size >= 24 && String(png, 12, 4, Charsets.US_ASCII) == "IHDR") {
        fun u32(off: Int): Int =
            ((png[off].toInt() and 0xff) shl 24) or ((png[off + 1].toInt() and 0xff) shl 16) or
                ((png[off + 2].toInt() and 0xff) shl 8) or (png[off + 3].toInt() and 0xff)
        return u32(16) to u32(20)
    }
    return 0 to 0
}
