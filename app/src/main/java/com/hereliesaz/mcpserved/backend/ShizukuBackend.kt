package com.hereliesaz.mcpserved.backend

import android.content.pm.PackageManager
import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * ADB-level control via Shizuku, for unrooted devices.
 *
 * Reaches everything `adb shell` reaches — `input`, `am`, `pm`, `settings` — and
 * nothing beyond it. Notably, `screencap` under shell uid cannot read the
 * framebuffer on modern releases, so this backend advertises no capture
 * capability and screenshots fall through to MediaProjection.
 *
 * Shizuku is bound to a service the user starts by pairing over wireless
 * debugging, and that service dies on reboot. Without root, every restart costs
 * a manual re-pair. The backend reports unavailability cleanly in that state
 * rather than failing per-call, so the tool surface shrinks instead of breaking.
 *
 * Process creation uses `Shizuku.newProcess`, which is not public API. It is
 * reached by reflection and guarded: a signature change upstream degrades this
 * backend to unavailable rather than crashing the service.
 */
class ShizukuBackend : ControlBackend {

    override val name = "shizuku"

    private val newProcess = runCatching {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }.getOrNull()

    private val available: Boolean
        get() = newProcess != null &&
            runCatching {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    override val caps: Set<Cap>
        get() = if (!available) emptySet() else setOf(
            Cap.SHELL_SHIZUKU,
            Cap.GESTURE,
            Cap.TEXT_INPUT,
            Cap.GLOBAL_KEYS,
            Cap.CLIPBOARD
        )

    /**
     * Runs [cmd] through the Shizuku-hosted shell.
     *
     * @return combined stdout, or a failure carrying stderr when the exit code
     *   is non-zero. Shell uid, not root — permission denials here are real
     *   limits of ADB, not misconfiguration.
     */
    private suspend fun sh(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        val m = newProcess ?: return@withContext ControlBackend.unsupported("shell", name)
        if (!available) return@withContext ControlBackend.unsupported("shell", name)
        runCatching {
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0) error("exit ${proc.exitValue()}: ${err.trim().take(400)}")
            out
        }
    }

    override suspend fun tree(maxDepth: Int): Result<Pruner.Result> =
        ControlBackend.unsupported("tree", name)

    override suspend fun foregroundPackage(): Result<String> =
        resumed().map { it.substringBefore('/') }

    override suspend fun foregroundActivity(): Result<String?> =
        resumed().map { r -> r.substringAfter('/', "").takeIf { it.isNotEmpty() } }

    private suspend fun resumed(): Result<String> =
        sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1")
            .mapCatching { line ->
                Regex("""([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)""").find(line)?.value
                    ?: error("could not parse resumed activity")
            }

    override suspend fun tap(x: Int, y: Int): Result<Unit> = sh("input tap $x $y").map { }

    override suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit> =
        sh("input swipe $x $y $x $y $ms").map { }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit> =
        sh("input swipe $x1 $y1 $x2 $y2 $ms").map { }

    override suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit> =
        ControlBackend.unsupported("scroll", name)

    override suspend fun type(text: String, nodeId: String?): Result<Unit> {
        if (nodeId != null) return ControlBackend.unsupported("type(nodeId)", name)
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
        return sh("input text '$escaped'").map { }
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
        return sh("input keyevent $code").map { }
    }

    override suspend fun launch(pkg: String): Result<Unit> =
        sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1").map { }

    /** Shell uid cannot read the framebuffer; MediaProjection owns this path. */
    override suspend fun capture(maxPx: Int): Result<CapturedImage> =
        ControlBackend.unsupported("capture", name)

    override suspend fun shell(cmd: String): Result<String> = sh(cmd)
}
