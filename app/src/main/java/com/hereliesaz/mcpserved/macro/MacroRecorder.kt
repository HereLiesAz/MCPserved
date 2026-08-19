package com.hereliesaz.mcpserved.macro

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hereliesaz.mcpserved.transport.Request
import com.hereliesaz.mcpserved.tree.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns the accessibility event stream into recorded [Request] steps while
 * a recording is active for [pkg].
 *
 * Only three event types translate into a step: a click becomes [Request.Tap],
 * a long click becomes [Request.LongPress], and text changes in a field
 * become one [Request.Type] per field (coalesced — see [onEvent] — rather
 * than one per keystroke). Swipes, scrolls, and global keys are not
 * captured: the accessibility event stream carries no raw gesture data, and
 * inferring a scroll's direction and distance from `TYPE_VIEW_SCROLLED`
 * alone is not reliable enough across arbitrary view types to record
 * blind. [isRecordableStep] still permits those shapes in a stored
 * [Macro], for whatever other path might one day produce them; live
 * recording just never emits them.
 *
 * Events outside [pkg] are ignored outright — a recording only ever
 * describes the one app it was started against, matching how [Macro.pkg]
 * gates playback.
 */
class MacroRecorder(private val pkg: String) {

    private val _steps = MutableStateFlow<List<Request>>(emptyList())
    val steps: StateFlow<List<Request>> = _steps

    private var pendingTypeNodeId: String? = null
    private var pendingTypeText: String = ""

    /**
     * Feeds one accessibility event in.
     *
     * [root] is called lazily and only when a node id actually needs
     * resolving, since it means walking the live tree — no reason to pay
     * for that on every keystroke of a text-changed run.
     */
    fun onEvent(event: AccessibilityEvent, root: () -> AccessibilityNodeInfo?) {
        if (event.packageName?.toString() != pkg) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                flushPendingType()
                locate(event, root)?.let { (id, x, y) -> append(Request.Tap(id, x, y)) }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                flushPendingType()
                locate(event, root)?.let { (id, x, y) -> append(Request.LongPress(id, x, y)) }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val id = locate(event, root)?.first
                if (id != pendingTypeNodeId) flushPendingType()
                pendingTypeNodeId = id
                pendingTypeText = event.text.joinToString("")
            }
            else -> Unit
        }
    }

    /** Ends the recording, flushing any in-progress text field, and returns the steps. */
    fun stop(): List<Request> {
        flushPendingType()
        return _steps.value
    }

    private fun flushPendingType() {
        val id = pendingTypeNodeId ?: return
        pendingTypeNodeId = null
        if (pendingTypeText.isNotEmpty()) append(Request.Type(pendingTypeText, id))
        pendingTypeText = ""
    }

    private fun append(req: Request) {
        _steps.value = _steps.value + req
    }

    /** Resolves an event's source node to a [NodeId], falling back to its screen center. */
    private fun locate(
        event: AccessibilityEvent,
        root: () -> AccessibilityNodeInfo?,
    ): Triple<String?, Int?, Int?>? {
        val source = event.source ?: return null
        val id = resolveNodeId(root(), source)
        if (id != null) return Triple(id, null, null)
        val r = Rect().also { source.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return null
        return Triple(null, r.centerX(), r.centerY())
    }

    /**
     * Walks from [root] to find [target]'s ordinal-among-siblings and depth,
     * the same way [com.hereliesaz.mcpserved.service.McpAccessibilityService.findById]
     * walks in the other direction — so a recorded id resolves through the
     * exact same lookup an AI-issued `tap(nodeId=...)` would use.
     */
    private fun resolveNodeId(root: AccessibilityNodeInfo?, target: AccessibilityNodeInfo): String? {
        if (root == null) return null
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, ordinal: Int, depth: Int) {
            if (node == null || found != null) return
            if (node == target) {
                found = NodeId.of(node, ordinal, depth)
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), i, depth + 1)
        }
        walk(root, 0, 0)
        return found
    }
}
