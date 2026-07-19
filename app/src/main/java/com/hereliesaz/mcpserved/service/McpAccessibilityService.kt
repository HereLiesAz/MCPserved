package com.hereliesaz.mcpserved.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hereliesaz.mcpserved.tree.NodeId
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-lived accessibility service. Owns the only handle to the window tree and
 * the only cheap source of the current foreground package.
 *
 * The service publishes itself to [instance] on connect. Nothing else in the
 * app may retain a reference across a disconnect: Android tears the service
 * down on toggle, on update, and occasionally for no stated reason, and a
 * retained stale binder yields silent no-ops rather than errors.
 *
 * [foreground] is updated from `TYPE_WINDOW_STATE_CHANGED` and read by
 * [com.hereliesaz.mcpserved.grant.Enforcer] before and after every mutating action.
 */
class McpAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: McpAccessibilityService? = null
            private set
    }

    private val fg = AtomicReference(Foreground("", null))

    /** Last observed foreground package and activity. */
    data class Foreground(val pkg: String, val activity: String?)

    val foreground: Foreground get() = fg.get()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val activity = event.className?.toString()?.takeIf { it.contains('.') }
        fg.set(Foreground(pkg, activity))
    }

    /**
     * Current window root.
     *
     * Returns null when the device is locked or the active window belongs to a
     * process that has revoked its node tree. Callers must treat null as
     * "cannot observe", never as "empty screen".
     */
    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Resolves a pruned node id back to a live node by re-walking the tree. */
    fun findById(id: String, maxDepth: Int = 40): AccessibilityNodeInfo? {
        val root = root() ?: return null
        var found: AccessibilityNodeInfo? = null

        fun walk(node: AccessibilityNodeInfo?, ordinal: Int, depth: Int) {
            if (node == null || found != null || depth > maxDepth) return
            if (NodeId.of(node, ordinal, depth) == id) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), i, depth + 1)
        }

        walk(root, 0, 0)
        return found
    }
}
