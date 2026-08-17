package com.hereliesaz.mcpserved.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * This phone, and nothing else. The only path here that needs no second
 * device of any kind — a relay dial-out, so an AI session running anywhere
 * (including one that has no local network path to this phone at all: a
 * cloud session, most notably) can reach it. Mesh bind, below it, is a
 * secondary option for someone who already has a private mesh and a
 * different device on it — it still needs that other device, so it isn't
 * "phone only" in the same sense.
 *
 * Both paths are off by default, gated behind one shared disclosure the
 * first time either is turned on.
 */
@Composable
fun RemoteAccessScreen(vm: MainViewModel) {
    val wildcardBind by vm.wildcardMcpBind.collectAsState()
    val relayEnabled by vm.relayEnabled.collectAsState()
    val relayUrl by vm.relayUrl.collectAsState()
    val roomToken by vm.relayRoomToken.collectAsState()
    val hasAcceptedDisclosure by vm.hasAcceptedRemoteAccessDisclosure.collectAsState()
    val context = LocalContext.current

    // Turning either path on for the first time shows a one-time prominent
    // disclosure, same pattern as the accessibility gate on first launch.
    // Turning one off never needs it. The pending action is what actually
    // flips the switch, deferred until the dialog is confirmed.
    var pendingEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun requestEnable(action: () -> Unit) {
        if (hasAcceptedDisclosure) action() else pendingEnable = action
    }

    pendingEnable?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingEnable = null },
            title = { Text("Remote access") },
            text = {
                Text(
                    "This lets MCPserved be reached beyond the USB cable or Wi-Fi " +
                        "network you're on right now — by a relay you point it at, or " +
                        "by a private mesh you join separately. Neither is on until " +
                        "you confirm here, and both stay off unless you turn them on " +
                        "explicitly below."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.acceptRemoteAccessDisclosure()
                    action()
                    pendingEnable = null
                }) { Text("I understand and agree") }
            },
            dismissButton = {
                TextButton(onClick = { pendingEnable = null }) { Text("Not now") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            "Off by default. This is the only tab that needs no computer, no " +
                "cable, no second device at all — just this phone and an AI " +
                "session running anywhere, including in the cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ---- Relay: the phone-only path -----------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dial out to a relay", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = relayEnabled,
                onCheckedChange = { on ->
                    if (on) requestEnable { vm.setRelayEnabled(true) } else vm.setRelayEnabled(false)
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Carries only the already end-to-end encrypted sealed-frame " +
                "protocol; the relay operator, whoever that is, forwards " +
                "ciphertext it cannot open. A relay has to exist somewhere first " +
                "— see relay/README.md to run your own — but once one is up, " +
                "reaching it from here is everything below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = relayUrl,
            onValueChange = vm::setRelayUrl,
            label = { Text("Relay URL (wss://…)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Text("Room token", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(roomToken, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, vm.relayConnectString())
                }
                context.startActivity(Intent.createChooser(intent, "Send to your AI assistant"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send connect instructions to your AI assistant")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste these into a chat with an assistant that can run commands " +
                "(Claude Code, for instance) — it reads them and connects itself. " +
                "No terminal of your own required.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = vm::rotateRelayRoomToken,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rotate room token")
        }

        Spacer(Modifier.height(36.dp))

        // ---- Mesh bind: secondary, still needs another device -------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bind for a private mesh", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = wildcardBind,
                onCheckedChange = { on ->
                    if (on) requestEnable { vm.setWildcardMcpBind(true) } else vm.setWildcardMcpBind(false)
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "For operators who already have a private mesh (Tailscale, WireGuard) " +
                "and a second device on it — still needs that other device, just not " +
                "adb or the same Wi-Fi. Widens the MCP endpoint's bind from loopback " +
                "to every interface; the bearer token on the Direct tab is still the " +
                "only thing that gets an answer, but on an untrusted or shared " +
                "network this hands anyone on it a login-free shot at guessing it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (wildcardBind) {
            Spacer(Modifier.height(8.dp))
            val addresses = vm.localAddresses
            Text(
                if (addresses.isEmpty()) "No non-loopback addresses found yet."
                else "Reachable at: ${addresses.joinToString(", ")}:8791",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
