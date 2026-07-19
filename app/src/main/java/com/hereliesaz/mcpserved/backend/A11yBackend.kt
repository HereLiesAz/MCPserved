package com.hereliesaz.mcpserved.backend

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hereliesaz.mcpserved.service.McpAccessibilityService
import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility-backed control. Always present when the service is enabled, and
 * the preferred path for observation and gesture dispatch regardless of whether
 * root is available.
 *
 * Cannot capture the screen, cannot run shell commands, and cannot reach the
 * ENTER or DELETE keys — those route to [RootBackend] or [ShizukuBackend], or
 * are simply absent from the advertised tool surface.
 */
class A11yBackend : ControlBackend {

    override val name = "accessibility"

    private val svc: McpAccessibilityService?
        get() = McpAccessibilityService.instance

    override val caps: Set<Cap>
        get() = if (svc == null) emptySet()
        else setOf(Cap.TREE, Cap.GESTURE, Cap.TEXT_INPUT, Cap.GLOBAL_KEYS)

    override suspend fun tree(maxDepth: Int): Result<Pruner.Result> {
        val s = svc ?: return err("accessibility service not connected")
        val root = s.root()
            ?: return err("no active window; device may be locked")
        return Result.success(Pruner(maxDepth).flatten(root))
    }

    override suspend fun foregroundPackage(): Result<String> {
        val s = svc ?: return err("accessibility service not connected")
        return Result.success(s.foreground.pkg)
    }

    override suspend fun foregroundActivity(): Result<String?> {
        val s = svc ?: return err("accessibility service not connected")
        return Result.success(s.foreground.activity)
    }

    override suspend fun tap(x: Int, y: Int): Result<Unit> =
        gesture(path(x, y), 0, 1)

    override suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit> =
        gesture(path(x, y), 0, ms.toLong())

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit> =
        gesture(
            Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
            0, ms.toLong()
        )

    override suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit> {
        val s = svc ?: return err("accessibility service not connected")
        val node = s.findById(nodeId) ?: return err("node $nodeId not found")
        // Prefer the node's own scroll action; it respects nested scrolling in a
        // way a synthetic swipe over the same bounds does not.
        val action = when (dir) {
            ScrollDir.DOWN, ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDir.UP, ScrollDir.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (node.performAction(action)) return Result.success(Unit)

        val r = Rect().also { node.getBoundsInScreen(it) }
        val dx = r.width() / 3
        val dy = r.height() / 3
        return when (dir) {
            ScrollDir.DOWN -> swipe(r.centerX(), r.centerY() + dy, r.centerX(), r.centerY() - dy, 300)
            ScrollDir.UP -> swipe(r.centerX(), r.centerY() - dy, r.centerX(), r.centerY() + dy, 300)
            ScrollDir.LEFT -> swipe(r.centerX() + dx, r.centerY(), r.centerX() - dx, r.centerY(), 300)
            ScrollDir.RIGHT -> swipe(r.centerX() - dx, r.centerY(), r.centerX() + dx, r.centerY(), 300)
        }
    }

    override suspend fun type(text: String, nodeId: String?): Result<Unit> {
        val s = svc ?: return err("accessibility service not connected")
        val node = if (nodeId != null) s.findById(nodeId)
        else s.root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) return err("no editable target")
        if (!node.isEditable) return err("target is not editable")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            Result.success(Unit) else err("ACTION_SET_TEXT rejected")
    }

    override suspend fun key(key: GlobalKey): Result<Unit> {
        val s = svc ?: return err("accessibility service not connected")
        val action = when (key) {
            GlobalKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            // No global action exists for these; the privileged backends own them.
            GlobalKey.ENTER, GlobalKey.DELETE ->
                return ControlBackend.unsupported("key $key", name)
        }
        return if (s.performGlobalAction(action)) Result.success(Unit)
        else err("global action $key rejected")
    }

    override suspend fun launch(pkg: String): Result<Unit> =
        ControlBackend.unsupported("launch", name)

    override suspend fun capture(maxPx: Int): Result<CapturedImage> =
        ControlBackend.unsupported("capture", name)

    override suspend fun shell(cmd: String): Result<String> =
        ControlBackend.unsupported("shell", name)

    private fun path(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    /**
     * Dispatches a gesture and suspends until the system reports completion or
     * cancellation. A cancelled gesture is a genuine failure — usually another
     * window stealing touch — and must not be reported as success.
     */
    private suspend fun gesture(p: Path, startMs: Long, durationMs: Long): Result<Unit> {
        val s = svc ?: return err("accessibility service not connected")
        val stroke = GestureDescription.StrokeDescription(p, startMs, durationMs.coerceAtLeast(1))
        val desc = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = s.dispatchGesture(
                desc,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(err("gesture cancelled"))
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) cont.resume(err("dispatchGesture refused"))
        }
    }

    private fun <T> err(msg: String): Result<T> = Result.failure(IllegalStateException(msg))
}
