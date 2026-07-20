package com.hereliesaz.mcpserved.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.graphics.Color as AndroidColor

/**
 * One-time key exchange with the desktop MCP server.
 *
 * Both public keys travel by camera, in both directions, so no third party ever
 * sits in the exchange that establishes trust. The shared secret is then what
 * authenticates the desktop server when it later connects to the device's
 * loopback control port through an `adb forward` tunnel.
 *
 * Pairing confers no authority. It establishes that two endpoints share a
 * secret; what may actually be done is decided afterwards, per package, on the
 * grants screen.
 */
@Composable
fun PairingScreen(vm: MainViewModel) {
    val payload by vm.pairPayload.collectAsState()
    val paired by vm.isPaired.collectAsState()
    val bearer by vm.mcpBearer.collectAsState()
    val clipboard = LocalClipboardManager.current
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        scanError = when {
            contents == null -> null
            vm.completePairing(contents) -> null
            else -> "That is not an MCPserved pairing code."
        }
    }

    val qr = remember(payload) { renderQr(payload) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- Primary: the device is itself the MCP server --------------------
        Text(
            "Connect a model",
            style = MaterialTheme.typography.displaySmall
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "This device is itself an MCP server. Point a host at the endpoint " +
                "below, with the token as a bearer header — no desktop server " +
                "needed. Reach it over an `adb forward` tunnel (USB or Wi-Fi).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Text(vm.mcpEndpoint, style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(12.dp))

        Text(
            "Bearer token",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(bearer, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { clipboard.setText(AnnotatedString(vm.mcpConfigJson())) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy host config")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(bearer)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy token only")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = vm::rotateMcpToken,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rotate token")
        }

        Spacer(Modifier.height(40.dp))

        // ---- Optional: the desktop bridge, paired by QR ----------------------
        Text(
            if (paired) "Desktop bridge · paired" else "Desktop bridge · optional",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(4.dp))

        Text(
            if (paired) {
                "A desktop server holds the matching key. It still cannot touch " +
                    "anything without a grant."
            } else {
                "Only for the desktop `mcpserved` bridge — the adb quick-connect " +
                    "path. Run `npx mcpserved pair` and give it the string below."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        qr?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Pairing code",
                modifier = Modifier.size(260.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            payload,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the code the server printed")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan the server's code")
        }

        scanError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(40.dp))

        OutlinedButton(
            onClick = vm::rotateIdentity,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rotate identity")
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Mints a new key and forgets the old peer. The only revocation that " +
                "also stops it connecting at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Renders a payload as a QR bitmap.
 *
 * Inverted — white modules on black — because the rest of the interface is black
 * and a white card would be the brightest object on a screen the user is asked
 * to point a camera at. Scanners read either polarity.
 */
private fun renderQr(payload: String, size: Int = 640): Bitmap? = runCatching {
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
