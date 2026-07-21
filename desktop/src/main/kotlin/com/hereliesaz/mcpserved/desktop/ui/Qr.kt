package com.hereliesaz.mcpserved.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/**
 * Renders a string as a QR bitmap the device's camera can read.
 *
 * The device scans this to complete the pairing exchange — the desktop's public
 * key coming back the other way, out of band, so nothing in the middle ever holds
 * both halves of the secret.
 */
fun qrImage(text: String, size: Int = 320): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        for (x in 0 until size) {
            image.setRGB(x, y, if (matrix[x, y]) black else white)
        }
    }
    return image.toComposeImageBitmap()
}
