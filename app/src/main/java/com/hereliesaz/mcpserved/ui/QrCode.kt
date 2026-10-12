package com.hereliesaz.mcpserved.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Color as AndroidColor

/**
 * Renders [payload] as a QR bitmap, or null if encoding fails. Shared by the
 * screens that show a QR (pairing, remote-access connector).
 */
internal fun renderQr(payload: String, size: Int = 640): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    // Fill a flat pixel array and hand it over in one setPixels call. Per-pixel
    // setPixel here means size*size JNI crossings on the composition thread —
    // hundreds of thousands of them — which visibly stalls the frame.
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x, y]) AndroidColor.WHITE else AndroidColor.BLACK
        }
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    bmp
}.getOrNull()
