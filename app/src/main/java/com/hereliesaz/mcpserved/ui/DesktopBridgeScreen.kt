package com.hereliesaz.mcpserved.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.graphics.Color as AndroidColor

/**
 * The desktop `mcpserved` bridge — the adb quick-connect path, for hosts that
 * only launch stdio servers. One-time key exchange: both public keys travel
 * by camera, in both directions, so no third party ever sits in the exchange
 * that establishes trust.
 *
 * Pairing confers no authority. It establishes that two endpoints share a
 * secret; what may actually be done is decided afterwards, per package, on
 * the Grants tab.
 */
@Composable
fun DesktopBridgeScreen(vm: MainViewModel) {
    val payload by vm.pairPayload.collectAsState()
    val paired by vm.isPaired.collectAsState()
    val pushStatus by vm.pairPushStatus.collectAsState()
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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
        // ---- Nearby desktops: the other half of Wi-Fi discovery --------------
        val desktops by vm.discoveredDesktops.collectAsState()
        Text("Nearby desktops", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (desktops.isEmpty()) {
            Text(
                "No desktops found on this Wi-Fi yet. Open MCPserved on a computer " +
                    "on the same network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            desktops.forEach { d ->
                Text(
                    "• ${d.name}  (${d.host})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(32.dp))

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
                "Open MCPserved on your computer — its Pair screen shows a QR right away. " +
                    "Tap \"Scan the server's code\" below and point this phone's camera at " +
                    "it; pairing finishes on its own. The code and button above that are " +
                    "only for the adb-only, no-shared-Wi-Fi case: `npx mcpserved pair` in a " +
                    "terminal."
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

        Spacer(Modifier.height(12.dp))

        // The QR above only helps if something with a camera is pointed at
        // this screen — a desktop app has no reliable way to do that. Typing
        // or hand-copying a payload this long across two different devices
        // isn't realistic either. Sharing it is: whatever channel already
        // moves text from this phone to that computer (a synced clipboard
        // app, a chat open on both, email-to-self) works without asking the
        // operator to transcribe a pairing key by hand.
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                context.startActivity(Intent.createChooser(intent, "Send to your computer"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send this code to your computer")
        }

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

        when (val status = pushStatus) {
            is MainViewModel.PairPushStatus.Pushing -> {
                Spacer(Modifier.height(16.dp))
                Row {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Sending your key to the desktop…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is MainViewModel.PairPushStatus.Failed -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Couldn't reach the desktop: ${status.message}. Make sure both are on the same " +
                        "Wi-Fi, then scan its code again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is MainViewModel.PairPushStatus.Idle -> {}
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
