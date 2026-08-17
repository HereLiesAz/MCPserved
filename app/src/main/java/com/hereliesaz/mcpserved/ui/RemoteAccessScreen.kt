package com.hereliesaz.mcpserved.ui

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Both opt-in remote-access paths, off by default: a wider [com.hereliesaz.mcpserved.transport.McpServer]
 * bind for a private mesh (Tailscale, WireGuard), and a relay dial-out for a
 * host with no local network path to the phone at all. Neither is required
 * for the "Direct" tab's flow — this screen only matters once `adb forward`
 * genuinely is not an option.
 */
@Composable
fun RemoteAccessScreen(vm: MainViewModel) {
    val wildcardBind by vm.wildcardMcpBind.collectAsState()
    val relayEnabled by vm.relayEnabled.collectAsState()
    val relayUrl by vm.relayUrl.collectAsState()
    val roomToken by vm.relayRoomToken.collectAsState()
    val hasAcceptedDisclosure by vm.hasAcceptedRemoteAccessDisclosure.collectAsState()
    val clipboard = LocalClipboardManager.current

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
                        "network you're on right now — by a private mesh you join " +
                        "separately, or by a relay you point it at. Neither is on " +
                        "until you confirm here, and both stay off unless you turn " +
                        "them on explicitly below."
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
            "Off by default. Both widen who can reach this device beyond the same " +
                "USB cable or Wi-Fi network — read what each one actually does before " +
                "turning it on. Changes take effect the next time the service arms.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // ---- Mesh bind: for Tailscale / WireGuard, no adb needed --------------
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
            "Widens the MCP endpoint's bind from loopback to every interface. Only " +
                "meaningful with a private mesh (Tailscale, WireGuard) already installed " +
                "and joined separately — this device makes no attempt to find one. The " +
                "bearer token on the Direct tab is still the only thing that gets an " +
                "answer, but on an untrusted or shared network this hands anyone on it " +
                "a login-free shot at guessing it.",
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

        Spacer(Modifier.height(28.dp))

        // ---- Relay: for a host with no local network path at all --------------
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
            "For a host with no local network path to this device at all — a cloud " +
                "session, for instance. Carries only the already end-to-end encrypted " +
                "sealed-frame protocol; the relay operator, whoever that is, forwards " +
                "ciphertext it cannot open. See relay/README.md before pointing this at " +
                "someone else's relay.",
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

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(vm.relayConnectString())) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy relay connect command")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = vm::rotateRelayRoomToken,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rotate room token")
        }
    }
}
