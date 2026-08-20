package com.hereliesaz.mcpserved.backend

import android.util.Xml
import com.hereliesaz.mcpserved.transport.Bounds
import com.hereliesaz.mcpserved.transport.UiNode
import com.hereliesaz.mcpserved.tree.NodeId
import com.hereliesaz.mcpserved.tree.Pruner
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the window tree via `uiautomator dump`, for backends with no live
 * accessibility node tree to walk directly — [ShizukuBackend] and
 * [RootBackend].
 *
 * Produces the same [UiNode] shape, and the same [NodeId] hash
 * (resource-id | class | sibling-ordinal | depth), that a live accessibility
 * walk does — computed from the dump's XML attributes instead of an
 * `AccessibilityNodeInfo`, since `uiautomator dump`'s `resource-id` and
 * `class` attributes are themselves serialized straight from
 * `getViewIdResourceName()`/`getClassName()`. A caller holding a node id
 * from either source resolves it identically; nothing downstream needs to
 * know which one produced it.
 *
 * `uiautomator dump` costs upward of a second and carries no explicit
 * visibility flag the way a live node tree does — every dumped node is
 * treated as visible, since a dump is inherently window-scoped. This is a
 * strictly worse tree than accessibility provides when accessibility is
 * live, which is exactly why [Resolver] only ever reaches this when it
 * isn't: slower-and-lesser beats absent, on a build with no accessibility
 * path at all.
 *
 * **Unverified against a real device.** `uiautomator`'s dumped attribute
 * set has drifted across Android releases; the `editable` heuristic in
 * particular (there is no direct "is this an edit field" attribute in the
 * dump the way `AccessibilityNodeInfo.isEditable` provides one live) is a
 * best-effort class-name match, not a guarantee.
 *
 * @param shell runs a command and returns its stdout — [ShizukuBackend]'s or
 *   [RootBackend]'s own shell channel, so this parser is agnostic to which
 *   privileged path is actually running the dump.
 */
class UiAutomatorTree(private val shell: suspend (String) -> Result<String>) {

    /** The most recent dump's node id → screen bounds, for [resolve] and scroll geometry. */
    private val lastBounds = AtomicReference<Map<String, Bounds>>(emptyMap())

    suspend fun tree(maxDepth: Int): Result<Pruner.Result> {
        val xml = dumpXml().getOrElse { return Result.failure(it) }
        return runCatching { parse(xml, maxDepth) }
    }

    /** Screen-center point for a node id from the most recent [tree] call. */
    fun resolve(nodeId: String): Result<Pair<Int, Int>> {
        val b = lastBounds.get()[nodeId]
            ?: return Result.failure(IllegalStateException("node $nodeId not found — call ui_tree first"))
        return Result.success(b.centerX to b.centerY)
    }

    /** Cached bounds for a node id, for a swipe-based scroll fallback's geometry. */
    fun boundsOf(nodeId: String): Bounds? = lastBounds.get()[nodeId]

    private suspend fun dumpXml(): Result<String> {
        val dumped = shell("uiautomator dump").getOrElse { return Result.failure(it) }
        val path = DUMPED_TO.find(dumped)?.groupValues?.get(1) ?: DEFAULT_DUMP_PATH
        return shell("cat $path")
    }

    private fun parse(xml: String, maxDepth: Int): Pruner.Result {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        val out = ArrayList<UiNode>(128)
        val boundsById = HashMap<String, Bounds>(128)
        var pruned = 0
        var depth = -1
        // One counter per currently-open ancestor: how many of its children have
        // been seen so far. A new node's ordinal is its parent's counter value
        // *before* incrementing — mirrors Pruner.flatten's walk(child, index, depth+1).
        val childCounters = ArrayDeque<Int>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "node") {
                    val ordinal = childCounters.lastOrNull() ?: 0
                    if (childCounters.isNotEmpty()) childCounters[childCounters.size - 1] = ordinal + 1
                    childCounters.addLast(0)
                    depth++

                    if (depth <= maxDepth) {
                        val bounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                        val resId = parser.getAttributeValue(null, "resource-id") ?: ""
                        val cls = parser.getAttributeValue(null, "class") ?: ""
                        val text = parser.getAttributeValue(null, "text")?.takeIf { it.isNotBlank() }
                        val desc = parser.getAttributeValue(null, "content-desc")?.takeIf { it.isNotBlank() }
                        val clickable = parser.attr("clickable")
                        val longClickable = parser.attr("long-clickable")
                        val scrollable = parser.attr("scrollable")
                        val checkable = parser.attr("checkable")
                        val checked = parser.attr("checked")
                        val enabled = parser.getAttributeValue(null, "enabled") != "false"
                        // No direct "editable" attribute in a uiautomator dump; approximate
                        // from the class name, the same signal a caller reading raw XML would use.
                        val editable = cls.contains("EditText", ignoreCase = true)
                        val interactive = clickable || longClickable || scrollable || checkable || editable

                        if (bounds != null && !bounds.isDegenerate &&
                            (interactive || text != null || desc != null)
                        ) {
                            val id = NodeId.raw(resId, cls, ordinal, depth)
                            boundsById[id] = bounds
                            out += UiNode(
                                id = id,
                                cls = cls.substringAfterLast('.').ifBlank { "View" },
                                bounds = bounds,
                                depth = depth,
                                text = text,
                                desc = desc,
                                clickable = clickable,
                                editable = editable,
                                scrollable = scrollable,
                                checked = if (checkable) checked else null,
                                enabled = enabled
                            )
                        } else {
                            pruned++
                        }
                    } else {
                        pruned++
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "node") {
                    childCounters.removeLast()
                    depth--
                }
            }
            event = parser.next()
        }

        lastBounds.set(boundsById)
        return Pruner.Result(out, pruned)
    }

    private fun XmlPullParser.attr(name: String): Boolean = getAttributeValue(null, name) == "true"

    private fun parseBounds(s: String?): Bounds? {
        val m = s?.let { BOUNDS.find(it) } ?: return null
        return Bounds(
            l = m.groupValues[1].toInt(), t = m.groupValues[2].toInt(),
            r = m.groupValues[3].toInt(), b = m.groupValues[4].toInt()
        )
    }

    private companion object {
        val DUMPED_TO = Regex("""dumped to:\s*(\S+)""")
        val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        const val DEFAULT_DUMP_PATH = "/sdcard/window_dump.xml"
    }
}
