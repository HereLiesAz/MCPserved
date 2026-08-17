package com.hereliesaz.mcpserved.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hereliesaz.mcpserved.service.ControlService

/**
 * This phone, and nothing else. Three of the four paths here need no second
 * device of any kind: IPv6, the most direct of the three — the phone's own
 * global address, no router or relay involved, limited only by whether the
 * network's firewall allows inbound; UPnP, which asks the router for a port
 * mapping instead (Wi-Fi only, never cellular, but works even when the
 * network doesn't hand out a reachable IPv6 address); and a relay dial-out,
 * which works from anywhere, including cellular, but needs a relay
 * deployed first — a one-command, free Cloudflare Worker deploy, not a
 * server anyone has to run continuously; see relay/cloudflare/README.md.
 * Mesh bind, last, is a secondary option for
 * someone who already has a private mesh and a *different* device on it —
 * it still needs that other device, so it isn't "phone only" in the same
 * sense.
 *
 * All four paths are off by default, gated behind one shared disclosure the
 * first time any of them is turned on.
 */
@Composable
fun RemoteAccessScreen(vm: MainViewModel) {
    val wildcardBind by vm.wildcardMcpBind.collectAsState()
    val relayEnabled by vm.relayEnabled.collectAsState()
    val relayUrl by vm.relayUrl.collectAsState()
    val roomToken by vm.relayRoomToken.collectAsState()
    val upnpEnabled by vm.upnpEnabled.collectAsState()
    val upnpMapping by vm.upnpMapping.collectAsState()
    val ipv6Enabled by vm.ipv6Enabled.collectAsState()
    val cloudflareDeployState by vm.cloudflareDeployState.collectAsState()
    var cloudflareTokenInput by remember { mutableStateOf("") }
    val tunnelState by vm.tunnelState.collectAsState()
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
                        "network you're on right now — by listening on this phone's " +
                        "own IPv6 address, by asking your router to open a port " +
                        "automatically, by a relay you point it at, or by a private " +
                        "mesh you join separately. None of these is on until you " +
                        "confirm here, and all stay off unless you turn them on " +
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

        // ---- The one-tap path: no account, nothing pre-deployed -----------
        Text("Start a tunnel and connect", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "No Cloudflare account, no token, nothing deployed ahead of time — " +
                "the phone opens a tunnel to itself, then hands the address " +
                "straight to your AI assistant's share sheet. One tap. It dies " +
                "the moment you stop it or close the app; start a fresh one next " +
                "time. Want an address that stays the same across sessions " +
                "instead? See \"Deploy your own relay\" further down.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // One tap, not two: pressing this starts the tunnel AND, the moment a
        // URL exists, opens the share sheet with connect instructions already
        // filled in — no separate "now go press Send" step. awaitingShare is
        // scoped to *this* press, so reopening the screen on an already-
        // running tunnel from an earlier session never re-pops the sheet.
        var awaitingShare by remember { mutableStateOf(false) }
        LaunchedEffect(tunnelState, awaitingShare) {
            if (!awaitingShare) return@LaunchedEffect
            val state = tunnelState
            if (state is ControlService.Companion.TunnelState.Running) {
                awaitingShare = false
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, vm.relayConnectString())
                }
                context.startActivity(Intent.createChooser(intent, "Send to your AI assistant"))
            } else if (state is ControlService.Companion.TunnelState.Error) {
                awaitingShare = false
            }
        }
        Button(
            onClick = {
                if (tunnelState is ControlService.Companion.TunnelState.Running) {
                    vm.stopTunnel()
                } else {
                    requestEnable {
                        awaitingShare = true
                        vm.startTunnel()
                    }
                }
            },
            enabled = tunnelState !is ControlService.Companion.TunnelState.Starting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (tunnelState) {
                    is ControlService.Companion.TunnelState.Starting -> "Starting…"
                    is ControlService.Companion.TunnelState.Running -> "Stop tunnel"
                    else -> "Start tunnel and send to your AI assistant"
                }
            )
        }
        when (val state = tunnelState) {
            is ControlService.Companion.TunnelState.Running -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reachable at: ${state.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is ControlService.Companion.TunnelState.Error -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }

        Spacer(Modifier.height(36.dp))

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
                "ciphertext it cannot open. A relay has to be deployed first " +
                "— see relay/cloudflare/README.md for a free, one-command " +
                "deploy with no server to keep running — but once one is up, " +
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

        Spacer(Modifier.height(20.dp))

        // ---- Deploy a relay to Cloudflare, from the phone, right here -----
        Text("Deploy your own relay (beta)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "No relay exists until one is deployed somewhere. This does it from " +
                "the phone — no computer, no terminal — using Cloudflare's API " +
                "directly: get a token below, paste it in, tap Deploy, and the " +
                "URL above fills itself in. Free at this scale; nothing to " +
                "maintain afterward. Unverified against a live account as of " +
                "this build — if it fails, relay/cloudflare/README.md in the " +
                "repo has the same deploy done the well-tested way, with " +
                "`wrangler`.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://dash.cloudflare.com/profile/api-tokens"),
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get a Cloudflare API token")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Opens Cloudflare's token page in your browser. Tap \"Create Token\" " +
                "→ the \"Edit Cloudflare Workers\" template, then come back here " +
                "and paste it in below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cloudflareTokenInput,
            onValueChange = {
                cloudflareTokenInput = it
                vm.setCloudflareApiToken(it)
            },
            label = { Text("Cloudflare API token") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::deployCloudflareRelay,
            enabled = cloudflareDeployState !is MainViewModel.DeployState.Deploying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (cloudflareDeployState is MainViewModel.DeployState.Deploying) "Deploying…" else "Deploy"
            )
        }
        when (val state = cloudflareDeployState) {
            is MainViewModel.DeployState.Done -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Deployed: ${state.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is MainViewModel.DeployState.Error -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }

        Spacer(Modifier.height(36.dp))

        // ---- UPnP: zero hosting, zero third party, Wi-Fi only -------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Try to open a port automatically (UPnP)", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = upnpEnabled,
                onCheckedChange = { on ->
                    if (on) requestEnable { vm.setUpnpEnabled(true) } else vm.setUpnpEnabled(false)
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "No relay, no hosting, no third party — asks your router to forward " +
                "a port straight to this phone. Only works on a Wi-Fi network whose " +
                "router supports and allows UPnP; never on cellular, and some home " +
                "routers ship with it turned off. Reaches the same already end-to-" +
                "end encrypted sealed-frame protocol as the relay above. Your " +
                "address can change if your router reassigns it or the mapping " +
                "renews on a different port — it's rechecked every few minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (upnpEnabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                upnpMapping?.let { "Reachable at: ${it.externalAddress}:${it.externalPort}" }
                    ?: "Not mapped yet — arm the service, or this network doesn't support UPnP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(36.dp))

        // ---- IPv6: the most direct path there is — no relay, no router ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Listen on this phone's IPv6 address", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = ipv6Enabled,
                onCheckedChange = { on ->
                    if (on) requestEnable { vm.setIpv6Enabled(true) } else vm.setIpv6Enabled(false)
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "IPv6 has no NAT to begin with, so there's no router to ask and no " +
                "relay to run — this device's own global IPv6 address, straight, " +
                "reaching the same already end-to-end encrypted sealed-frame " +
                "protocol as the paths above. Whether it's actually reachable " +
                "depends on your network's firewall rather than any router " +
                "setting; unlike UPnP this isn't limited to Wi-Fi, since a " +
                "cellular connection can hand out a routable IPv6 address too — " +
                "just not always one that accepts unsolicited connections.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (ipv6Enabled) {
            Spacer(Modifier.height(8.dp))
            val ipv6Addresses = vm.ipv6Addresses
            Text(
                if (ipv6Addresses.isEmpty()) "No global IPv6 address found yet."
                else "Reachable at: ${ipv6Addresses.joinToString(", ") { "[$it]:8790" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
