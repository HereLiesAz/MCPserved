package com.hereliesaz.mcpserved.backend

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.hereliesaz.mcpserved.transport.Cap
import com.hereliesaz.mcpserved.transport.GlobalKey
import com.hereliesaz.mcpserved.transport.ScrollDir
import com.hereliesaz.mcpserved.tree.Pruner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Screen capture via MediaProjection — the fallback when neither root's
 * `screencap` (bypasses the consent dialog entirely, see [RootBackend]) nor
 * accessibility is available. The only capability this backend offers;
 * everything else is [ControlBackend.unsupported] and falls through
 * [Resolver]'s chain to whatever else can do it.
 *
 * Requires a one-time system consent dialog the *user* must grant from a
 * foreground Activity (`MediaProjectionManager.createScreenCaptureIntent()`
 * — there is no way to skip it, by platform design). [MainActivity] launches
 * that dialog and hands the resulting `(resultCode, data)` to [grant]; until
 * that happens [caps] reports empty, and an unrooted, no-accessibility
 * device (the expected `playstore`-flavor case) gets an honest "screenshot
 * unavailable" rather than a crash.
 *
 * The grant is process-scoped — Android does not let it survive a process
 * restart, so a killed-and-relaunched app needs the dialog again. This
 * backend does not try to auto-restore across restarts; the UI simply shows
 * "not enabled" until the operator taps through it again.
 *
 * **Unverified against a real device.** Written against MediaProjection's
 * documented behavior, including the API 34+ requirement that the
 * *foreground service* invoking it declare the `mediaProjection` type (see
 * `ControlService`'s manifest entry and `startForeground` call) — not
 * exercised on real hardware.
 */
class MediaProjectionBackend(private val appContext: Context) : ControlBackend {

    override val name = "media-projection"

    private val manager: MediaProjectionManager? =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

    @Volatile
    private var projection: MediaProjection? = null

    override val caps: Set<Cap>
        get() = if (projection != null) setOf(Cap.CAPTURE_PROJECTION) else emptySet()

    /** The `Intent` a caller launches via `ActivityResultLauncher` to request consent. */
    fun captureIntent(): Intent? = manager?.createScreenCaptureIntent()

    /**
     * Consumes the consent result from an `ActivityResultLauncher` callback.
     * Call this from [MainActivity] the moment the system dialog returns OK.
     */
    fun grant(resultCode: Int, data: Intent) {
        val m = manager ?: return
        runCatching {
            projection?.stop()
            val p = m.getMediaProjection(resultCode, data) ?: return@runCatching
            p.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        projection = null
                    }
                },
                Handler(Looper.getMainLooper())
            )
            projection = p
        }
    }

    /** Revokes the current grant, if any. The next [capture] falls through to whatever's next. */
    fun revoke() {
        projection?.stop()
        projection = null
    }

    override suspend fun tree(maxDepth: Int): Result<Pruner.Result> = ControlBackend.unsupported("tree", name)
    override suspend fun resolveNode(nodeId: String): Result<Pair<Int, Int>> =
        ControlBackend.unsupported("resolveNode", name)
    override suspend fun foregroundPackage(): Result<String> = ControlBackend.unsupported("foregroundPackage", name)
    override suspend fun foregroundActivity(): Result<String?> = ControlBackend.unsupported("foregroundActivity", name)
    override suspend fun tap(x: Int, y: Int): Result<Unit> = ControlBackend.unsupported("tap", name)
    override suspend fun longPress(x: Int, y: Int, ms: Int): Result<Unit> = ControlBackend.unsupported("longPress", name)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Result<Unit> =
        ControlBackend.unsupported("swipe", name)
    override suspend fun scroll(nodeId: String, dir: ScrollDir): Result<Unit> = ControlBackend.unsupported("scroll", name)
    override suspend fun type(text: String, nodeId: String?): Result<Unit> = ControlBackend.unsupported("type", name)
    override suspend fun key(key: GlobalKey): Result<Unit> = ControlBackend.unsupported("key", name)
    override suspend fun launch(pkg: String): Result<Unit> = ControlBackend.unsupported("launch", name)
    override suspend fun shell(cmd: String): Result<String> = ControlBackend.unsupported("shell", name)

    override suspend fun capture(maxPx: Int): Result<CapturedImage> {
        val p = projection ?: return ControlBackend.unsupported("capture", name)
        return captureOnce(p, maxPx)
    }

    /**
     * Captures exactly one frame: builds a throwaway [VirtualDisplay] +
     * [ImageReader] pair sized to the real display metrics, waits for the
     * first frame, tears both down immediately. A short-lived display per
     * call rather than a held one — nothing here runs often enough for the
     * setup cost to matter, and a display that never went away would be a
     * second, harder-to-notice thing capturing the screen indefinitely.
     *
     * `createVirtualDisplay`/`ImageReader` callbacks are main-thread APIs.
     */
    private suspend fun captureOnce(projection: MediaProjection, maxPx: Int): Result<CapturedImage> =
        withContext(Dispatchers.Main) {
            val dm = appContext.resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels
            val density = dm.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            var display: VirtualDisplay? = null
            try {
                val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        reader.setOnImageAvailableListener(
                            { r ->
                                val image = r.acquireLatestImage()
                                if (image == null) return@setOnImageAvailableListener
                                val outcome = runCatching {
                                    val bmp = imageToBitmap(image, width, height)
                                    image.close()
                                    try {
                                        val (bytes, w, h) = downscaleAndEncode(bmp, maxPx)
                                        CapturedImage("image/jpeg", bytes, w, h)
                                    } finally {
                                        bmp.recycle()
                                    }
                                }
                                if (cont.isActive) cont.resume(outcome)
                            },
                            Handler(Looper.getMainLooper())
                        )

                        display = projection.createVirtualDisplay(
                            "mcpserved-capture",
                            width, height, density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            reader.surface, null, null
                        )
                    }
                }
                result ?: Result.failure(IllegalStateException("no frame within ${CAPTURE_TIMEOUT_MS}ms"))
            } finally {
                display?.release()
                reader.close()
            }
        }

    /** Copies an RGBA image into a [Bitmap], accounting for row-stride padding. */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    /** Downscales to [maxPx] on the longest edge and re-encodes as JPEG, mirroring [RootBackend.capture]. */
    private fun downscaleAndEncode(src: Bitmap, maxPx: Int): Triple<ByteArray, Int, Int> {
        val scale = maxPx.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale >= 1f) src else Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
        )
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Triple(out.toByteArray(), bmp.width, bmp.height)
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
