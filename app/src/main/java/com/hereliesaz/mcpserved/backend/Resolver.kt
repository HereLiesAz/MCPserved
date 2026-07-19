package com.hereliesaz.mcpserved.backend

import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner

/**
 * Routes each operation to the backend best suited to it.
 *
 * The ordering is per-operation and deliberately non-uniform. Accessibility wins
 * gestures because [android.accessibilityservice.AccessibilityService.dispatchGesture]
 * reports genuine completion and costs no process spawn, while `su -c input tap`
 * costs roughly two hundred milliseconds and reports success the moment the shell
 * exits — whether or not anything was touched. Root wins capture because
 * `screencap` needs no MediaProjection consent dialog, and wins shell because
 * nothing else can offer it.
 *
 * Every operation falls through its preference list until one backend returns a
 * success. A [Result.failure] from a capable backend is not retried on a lesser
 * one: a rejected gesture means the window refused it, and repeating the attempt
 * through a coarser path would land the touch somewhere unintended.
 *
 * @param backends candidate backends, constructed once at service start
 */
class Resolver(private val backends: List<ControlBackend>) {

    /** Union of every capability any live backend advertises. */
    val caps: Set<Cap>
        get() = backends.flatMapTo(mutableSetOf()) { it.caps }

    val hasRoot: Boolean get() = Cap.SHELL_ROOT in caps
    val hasShizuku: Boolean get() = Cap.SHELL_SHIZUKU in caps
    val hasA11y: Boolean get() = Cap.TREE in caps

    /**
     * First backend advertising [cap], in construction order.
     *
     * Construction order encodes preference. [com.hereliesaz.mcpserved.service.ControlService]
     * builds the list as accessibility, root, Shizuku — so observation and
     * gestures resolve to accessibility even on a rooted device.
     */
    private fun first(cap: Cap): ControlBackend? = backends.firstOrNull { cap in it.caps }

    /** Tries [preferred] in order, returning the first success or the last failure. */
    private suspend fun <T> chain(
        vararg preferred: Cap,
        op: suspend (ControlBackend) -> Result<T>
    ): Result<T> {
        var last: Result<T>? = null
        for (cap in preferred) {
            val b = first(cap) ?: continue
            val r = op(b)
            if (r.isSuccess) return r
            last = r
        }
        return last ?: Result.failure(
            UnsupportedOperationException("no backend for ${preferred.joinToString()}")
        )
    }

    suspend fun tree(maxDepth: Int): Result<Pruner.Result> =
        chain(Cap.TREE) { it.tree(maxDepth) }

    /**
     * Current foreground package.
     *
     * Accessibility is authoritative here — its value comes from a window state
     * event rather than a poll, so it cannot report a package that was foreground
     * only at the moment of asking. Root's `dumpsys` fallback exists solely for
     * devices where the service has been torn down mid-session.
     */
    suspend fun foregroundPackage(): Result<String> =
        chain(Cap.TREE, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.foregroundPackage() }

    suspend fun foregroundActivity(): Result<String?> =
        chain(Cap.TREE, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.foregroundActivity() }

    suspend fun tap(x: Int, y: Int): Result<Unit> =
        chain(Cap.GESTURE, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.tap(x, y) }

    suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit> =
        chain(Cap.GESTURE, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.longPress(x, y, ms) }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit> =
        chain(Cap.GESTURE, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.swipe(x1, y1, x2, y2, ms) }

    /** Scroll requires a resolvable node id, so it is accessibility-only. */
    suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit> =
        chain(Cap.TREE) { it.scroll(nodeId, dir) }

    suspend fun type(text: String, nodeId: String?): Result<Unit> =
        chain(Cap.TEXT_INPUT, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.type(text, nodeId) }

    suspend fun key(key: GlobalKey): Result<Unit> =
        chain(Cap.GLOBAL_KEYS, Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.key(key) }

    /** Accessibility has no launch path; this is privileged-only by construction. */
    suspend fun launch(pkg: String): Result<Unit> =
        chain(Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.launch(pkg) }

    suspend fun capture(maxPx: Int): Result<CapturedImage> =
        chain(Cap.CAPTURE_SILENT, Cap.CAPTURE_PROJECTION) { it.capture(maxPx) }

    suspend fun shell(cmd: String): Result<String> =
        chain(Cap.SHELL_ROOT, Cap.SHELL_SHIZUKU) { it.shell(cmd) }

    /** Backend names in preference order, for the session log. */
    fun describe(): List<String> = backends.filter { it.caps.isNotEmpty() }.map { it.name }
}
