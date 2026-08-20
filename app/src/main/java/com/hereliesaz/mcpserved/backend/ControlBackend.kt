package com.hereliesaz.mcpserved.backend

import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner

/**
 * One way of touching the device.
 *
 * Implementations are not tiers. Root is not uniformly superior to
 * accessibility — `su -c input tap` spawns a process per gesture and lands
 * around two hundred milliseconds behind `dispatchGesture`, while root is the
 * only path to a screenshot that skips the MediaProjection dialog. [Resolver]
 * therefore dispatches per operation rather than per privilege level.
 *
 * Every method returns [Result]; an unsupported operation fails rather than
 * throwing, so a partially-capable device degrades instead of crashing.
 */
interface ControlBackend {

    /** What this backend can actually do on this device, probed at startup. */
    val caps: Set<Cap>

    /** Human-readable name for the session log. */
    val name: String

    suspend fun tree(maxDepth: Int): Result<Pruner.Result>

    /**
     * Resolves a [com.hereliesaz.mcpserved.tree.NodeId] to its current
     * screen-center point.
     *
     * Node-id addressing was, until now, wired straight from
     * [com.hereliesaz.mcpserved.service.Dispatcher] into
     * [com.hereliesaz.mcpserved.service.McpAccessibilityService.findById] —
     * a live re-walk of the accessibility tree, needing no cache. A backend
     * with no live tree (see [com.hereliesaz.mcpserved.backend.UiAutomatorTree])
     * resolves against whatever its most recent [tree] call cached instead.
     * [com.hereliesaz.mcpserved.backend.Resolver.resolveNode] is now the one
     * place that decides which.
     */
    suspend fun resolveNode(nodeId: String): Result<Pair<Int, Int>>

    suspend fun foregroundPackage(): Result<String>

    suspend fun foregroundActivity(): Result<String?>

    suspend fun tap(x: Int, y: Int): Result<Unit>

    suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit>

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit>

    suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit>

    suspend fun type(text: String, nodeId: String?): Result<Unit>

    suspend fun key(key: GlobalKey): Result<Unit>

    suspend fun launch(pkg: String): Result<Unit>

    suspend fun capture(maxPx: Int): Result<CapturedImage>

    suspend fun shell(cmd: String): Result<String>

    /** Thrown-free marker for operations a backend does not implement. */
    companion object {
        fun <T> unsupported(op: String, backend: String): Result<T> =
            Result.failure(UnsupportedOperationException("$op unavailable on $backend"))
    }
}

/** Encoded screenshot, already downscaled. */
data class CapturedImage(val mime: String, val bytes: ByteArray, val w: Int, val h: Int) {
    override fun equals(other: Any?): Boolean =
        other is CapturedImage && mime == other.mime && w == other.w && h == other.h &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (mime.hashCode() * 31 + w) * 31 + h
}
