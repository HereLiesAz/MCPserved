package com.hereliesaz.mcpserved.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Root-backed control via a persistent `su` shell.
 *
 * Availability is probed exactly once, at construction, by executing a real
 * command rather than by looking for the `su` binary. Magisk hides its binary
 * from callers that ask the naive way, so a filesystem check reports absence on
 * devices that are in fact rooted. The probe result is cached because repeated
 * `su` invocations trigger the superuser prompt on some managers, and a session
 * that asks fifty times is a session the user denies once and abandons.
 *
 * This backend deliberately does not implement [tree]. `uiautomator dump` writes
 * XML to disk, takes upward of a second, and yields strictly less than the
 * accessibility node tree already provides.
 *
 * @param overrideAvailable when non-null, bypasses probing. Exposed for the
 *   manual toggle in settings, because root detection is a question the device
 *   is permitted to lie about.
 */
class RootBackend(overrideAvailable: Boolean? = null) : ControlBackend {

    override val name = "root"

    private val available: Boolean = overrideAvailable ?: probe()

    override val caps: Set<Cap>
        get() = if (!available) emptySet() else setOf(
            Cap.SHELL_ROOT,
            Cap.CAPTURE_SILENT,
            Cap.GESTURE,
            Cap.TEXT_INPUT,
            Cap.GLOBAL_KEYS,
            Cap.CLIPBOARD
        )

    /** Executes `id` and confirms uid 0. Anything else is treated as unrooted. */
    private fun probe(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        p.exitValue() == 0 && out.contains("uid=0")
    }.getOrDefault(false)

    /**
     * Runs [cmd] as root.
     *
     * Each call spawns a process. That cost — roughly two hundred milliseconds —
     * is why [Resolver] routes gestures elsewhere when accessibility is live.
     */
    private suspend fun su(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        if (!available) return@withContext ControlBackend.unsupported("shell", name)
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes()
            p.waitFor()
            if (p.exitValue() != 0) {
                error("exit ${p.exitValue()}: ${String(out).trim().take(400)}")
            }
            String(out)
        }
    }

    /** Binary-safe variant, for `screencap` output that must not become a String. */
    private suspend fun suBytes(cmd: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (!available) return@withContext ControlBackend.unsupported("shell", name)
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val out = p.inputStream.readBytes()
            p.waitFor()
            if (p.exitValue() != 0) error("exit ${p.exitValue()}")
            out
        }
    }

    override suspend fun tree(maxDepth: Int): Result<Pruner.Result> =
        ControlBackend.unsupported("tree", name)

    /**
     * Reads the resumed activity from `dumpsys activity activities`.
     *
     * A fallback only. The output format has changed across releases and will
     * change again; accessibility's event-driven value is preferred everywhere
     * it is available.
     */
    override suspend fun foregroundPackage(): Result<String> =
        resumed().map { it.substringBefore('/') }

    override suspend fun foregroundActivity(): Result<String?> =
        resumed().map { r -> r.substringAfter('/', "").takeIf { it.isNotEmpty() } }

    private suspend fun resumed(): Result<String> =
        su("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1")
            .mapCatching { line ->
                Regex("""([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)""").find(line)?.value
                    ?: error("could not parse resumed activity")
            }

    override suspend fun tap(x: Int, y: Int): Result<Unit> =
        su("input tap $x $y").map { }

    override suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit> =
        su("input swipe $x $y $x $y $ms").map { }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit> =
        su("input swipe $x1 $y1 $x2 $y2 $ms").map { }

    override suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit> =
        ControlBackend.unsupported("scroll", name)

    /**
     * Types [text] into the focused field.
     *
     * `input text` cannot address a specific node, so [nodeId] is rejected rather
     * than silently ignored — typing into the wrong field is the kind of failure
     * that looks like success until much later.
     */
    override suspend fun type(text: String, nodeId: String?): Result<Unit> {
        if (nodeId != null) return ControlBackend.unsupported("type(nodeId)", name)
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
        return su("input text '$escaped'").map { }
    }

    override suspend fun key(key: GlobalKey): Result<Unit> {
        val code = when (key) {
            GlobalKey.BACK -> 4
            GlobalKey.HOME -> 3
            GlobalKey.RECENTS -> 187
            GlobalKey.ENTER -> 66
            GlobalKey.DELETE -> 67
            GlobalKey.NOTIFICATIONS -> 83
        }
        return su("input keyevent $code").map { }
    }

    override suspend fun launch(pkg: String): Result<Unit> =
        su("monkey -p $pkg -c android.intent.category.LAUNCHER 1").map { }

    /**
     * Captures the screen with `screencap -p`, bypassing the MediaProjection
     * consent dialog entirely. This is the single clearest reason to prefer root.
     *
     * Output is decoded, downscaled to [maxPx] on its longest edge, and re-encoded
     * as JPEG. A raw framebuffer PNG runs several megabytes and would dominate the
     * cost of every observation that used it.
     */
    override suspend fun capture(maxPx: Int): Result<CapturedImage> =
        suBytes("screencap -p").mapCatching { png ->
            val src = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: error("screencap produced undecodable output")
            val scale = maxPx.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale >= 1f) src else Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            CapturedImage("image/jpeg", out.toByteArray(), bmp.width, bmp.height)
        }

    override suspend fun shell(cmd: String): Result<String> = su(cmd)
}
