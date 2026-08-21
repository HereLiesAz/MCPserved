package com.hereliesaz.mcpserved.macro

import com.hereliesaz.mcpserved.transport.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Records a macro by reading raw touchscreen events directly (`getevent`,
 * root only) instead of watching accessibility semantics — the richer
 * recorder wherever root is available, and the *only* recorder at all on a
 * `playstore`-flavor device (no accessibility to watch in the first place).
 *
 * Strictly more capable than [MacroRecorder]: it observes the actual touch
 * stream, so swipes — a gesture accessibility's event stream cannot report
 * at all — become recordable alongside taps and long presses. What it
 * cannot do is resolve *what* was touched to a node id; every step it
 * records is coordinate-based, so a recording is good only for the exact
 * screen layout it was captured against — more so than [MacroRecorder]'s
 * node-id-addressed steps, which survive scrolling.
 *
 * Assumes Multi-touch Protocol Type B (slot-addressed touch points,
 * `ABS_MT_TRACKING_ID` marking a touch's start/end) — universal on Android
 * touchscreens built in roughly the last decade, with no fallback for an
 * older Type A device. Tracks only the first active touch; multi-finger
 * gestures are not decomposed into anything meaningful.
 *
 * **Unverified against real hardware.** Written against the documented
 * kernel input protocol (`Documentation/input/multi-touch-protocol.txt`)
 * and `getevent -lt`'s documented output shape, not exercised against an
 * actual touchscreen — device auto-detection and coordinate scaling in
 * particular are the likeliest places a specific device's quirks could
 * break this.
 *
 * @param shell runs a *one-shot* root command — used only for device
 *   discovery (`getevent -pl`). The event stream itself is read from its
 *   own long-lived process in [start], since it has to stream rather than
 *   return once.
 * @param screenWidth @param screenHeight real display resolution, for
 *   scaling the device's raw coordinate range to screen pixels.
 */
class RawGestureRecorder(
    private val shell: suspend (String) -> Result<String>,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    data class TouchDeviceInfo(val path: String, val maxX: Int, val maxY: Int)

    private val _steps = MutableStateFlow<List<Request>>(emptyList())
    val steps: StateFlow<List<Request>> = _steps

    @Volatile
    private var process: Process? = null

    @Volatile
    private var reading = false

    /**
     * Finds the touchscreen device and streams its events until [stop] is
     * called. Runs on the calling coroutine until stopped — callers launch
     * this in its own coroutine rather than awaiting it inline.
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        val device = discoverTouchDevice().getOrElse { return@withContext Result.failure(it) }
        runCatching {
            val proc = ProcessBuilder("su", "-c", "getevent -lt ${device.path}")
                .redirectErrorStream(true)
                .start()
            process = proc
            reading = true
            val tracker = GestureTracker(device, screenWidth, screenHeight) { append(it) }
            proc.inputStream.bufferedReader().use { reader ->
                while (reading) {
                    val line = reader.readLine() ?: break
                    tracker.onLine(line)
                }
            }
        }
    }

    /** Stops the event stream and returns whatever was captured. */
    fun stop(): List<Request> {
        reading = false
        process?.destroy()
        process = null
        return _steps.value
    }

    private fun append(req: Request) {
        _steps.value = _steps.value + req
    }

    private suspend fun discoverTouchDevice(): Result<TouchDeviceInfo> {
        val listing = shell("getevent -pl").getOrElse { return Result.failure(it) }
        return runCatching {
            parseDeviceListing(listing) ?: error("no touchscreen device found in getevent -pl")
        }
    }

    /**
     * Parses `getevent -pl`'s device listing. Blocks look like:
     * ```
     * add device 1: /dev/input/event3
     *   name:     "goodix-ts"
     *     ABS_MT_POSITION_X    : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
     *     ABS_MT_POSITION_Y    : value 0, min 0, max 2559, fuzz 0, flat 0, resolution 0
     * ```
     * Returns the first device block that declares both absolute axes.
     */
    private fun parseDeviceListing(listing: String): TouchDeviceInfo? {
        var path: String? = null
        var maxX: Int? = null
        var maxY: Int? = null

        for (rawLine in listing.lineSequence()) {
            val line = rawLine.trim()
            ADD_DEVICE.find(line)?.let {
                path = it.groupValues[1]
                maxX = null
                maxY = null
            }
            val p = path ?: continue
            if (line.startsWith("ABS_MT_POSITION_X")) maxX = MAX_VALUE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (line.startsWith("ABS_MT_POSITION_Y")) maxY = MAX_VALUE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val x = maxX
            val y = maxY
            if (x != null && y != null) return TouchDeviceInfo(p, x, y)
        }
        return null
    }

    private companion object {
        val ADD_DEVICE = Regex("""add device \d+: (\S+)""")
        val MAX_VALUE = Regex("""max (-?\d+)""")
    }
}

/**
 * Turns a `getevent -lt` line stream into tap/long-press/swipe [Request]s.
 * See [RawGestureRecorder]'s doc for the protocol assumptions this relies on.
 *
 * `getevent -lt` prints numeric values as bare hex (no `0x` prefix), and
 * `ABS_MT_TRACKING_ID`'s "no touch" sentinel (-1) arrives as the 32-bit
 * two's-complement hex `ffffffff` — parsed as `Long` then narrowed to `Int`
 * to recover the correct signed value.
 */
private class GestureTracker(
    private val device: RawGestureRecorder.TouchDeviceInfo,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onGesture: (Request) -> Unit,
) {
    private var touchActive = false
    private var startCaptured = false
    private var startX = 0
    private var startY = 0
    private var curX = 0
    private var curY = 0
    private var startTimeMs = 0L

    fun onLine(raw: String) {
        val m = LINE.find(raw) ?: return
        val type = m.groupValues[1]
        val code = m.groupValues[2]
        val hex = m.groupValues[3]

        when {
            type == "EV_ABS" && code == "ABS_MT_TRACKING_ID" -> {
                val id = hex.toLongOrNull(16)?.toInt() ?: return
                if (!touchActive && id != -1) {
                    touchActive = true
                    startCaptured = false
                    startTimeMs = System.currentTimeMillis()
                } else if (touchActive && id == -1) {
                    finishTouch()
                    touchActive = false
                }
            }
            type == "EV_ABS" && code == "ABS_MT_POSITION_X" ->
                hex.toLongOrNull(16)?.toInt()?.let { curX = it }
            type == "EV_ABS" && code == "ABS_MT_POSITION_Y" ->
                hex.toLongOrNull(16)?.toInt()?.let { curY = it }
            type == "EV_SYN" && code == "SYN_REPORT" -> onFrame()
        }
    }

    /** A full input frame just closed — X and Y for it, if any, have both landed by now. */
    private fun onFrame() {
        if (touchActive && !startCaptured) {
            startX = curX
            startY = curY
            startCaptured = true
        }
    }

    private fun finishTouch() {
        if (!startCaptured) return
        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceIn(1, 5_000)
        val sx = scaleX(startX)
        val sy = scaleY(startY)
        val ex = scaleX(curX)
        val ey = scaleY(curY)
        val distance = kotlin.math.hypot((ex - sx).toDouble(), (ey - sy).toDouble())

        val req = when {
            distance > TAP_SLOP_PX -> Request.Swipe(sx, sy, ex, ey, durationMs.toInt())
            durationMs >= LONG_PRESS_MS -> Request.LongPress(x = ex, y = ey, ms = durationMs.toInt())
            else -> Request.Tap(x = ex, y = ey)
        }
        onGesture(req)
    }

    private fun scaleX(raw: Int): Int =
        (raw.toLong() * screenWidth / (device.maxX + 1)).toInt().coerceIn(0, screenWidth - 1)

    private fun scaleY(raw: Int): Int =
        (raw.toLong() * screenHeight / (device.maxY + 1)).toInt().coerceIn(0, screenHeight - 1)

    private companion object {
        // getevent -lt line shape: "[  1234.5678] /dev/input/eventN: EV_ABS ABS_MT_POSITION_X 000001a4"
        val LINE = Regex("""^\[\s*[\d.]+]\s*\S+:\s*(\S+)\s+(\S+)\s+(\S+)\s*$""")
        const val TAP_SLOP_PX = 24
        const val LONG_PRESS_MS = 500L
    }
}
