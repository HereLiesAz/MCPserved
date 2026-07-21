package com.hereliesaz.mcpserved.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.mcpserved.desktop.hosts.Hosts

/**
 * The desktop app window.
 *
 * Four screens: find the device on the LAN and check the connection, pair with
 * it, wire up the popular AI hosts in one click, and watch the log. Everything a
 * user needs to go from "installed" to "an AI can drive my phone" without opening
 * a terminal.
 */
fun launchGui() = application {
    val state = rememberWindowState(width = 1000.dp, height = 720.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "MCPserved",
        state = state,
        icon = painterResource("icon.png"),
    ) {
        val scope = rememberCoroutineScope()
        val controller = remember { AppController(scope) }
        DisposableEffect(Unit) { onDispose { controller.dispose() } }

        McpTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Rail(controller)
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        when (controller.selectedTab) {
                            Tab.Devices -> DevicesScreen(controller)
                            Tab.Pair -> PairScreen(controller)
                            Tab.Hosts -> HostsScreen(controller)
                            Tab.Log -> LogScreen(controller)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Rail(controller: AppController) {
    NavigationRail {
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(
            selected = controller.selectedTab == Tab.Devices,
            onClick = { controller.selectedTab = Tab.Devices },
            icon = { Text("📱") },
            label = { Text("Devices") },
        )
        NavigationRailItem(
            selected = controller.selectedTab == Tab.Pair,
            onClick = { controller.selectedTab = Tab.Pair },
            icon = { Text("⧉") },
            label = { Text("Pair") },
        )
        NavigationRailItem(
            selected = controller.selectedTab == Tab.Hosts,
            onClick = { controller.selectedTab = Tab.Hosts },
            icon = { Text("⚡") },
            label = { Text("AI Hosts") },
        )
        NavigationRailItem(
            selected = controller.selectedTab == Tab.Log,
            onClick = { controller.selectedTab = Tab.Log },
            icon = { Text("▤") },
            label = { Text("Log") },
        )
    }
}

@Composable
private fun Heading(text: String, subtitle: String? = null) {
    Column {
        Text(text, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DevicesScreen(controller: AppController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading(
            "Devices",
            "Devices advertise themselves on your Wi-Fi once the app is armed. The paired key still " +
                "authenticates every connection — being discoverable is not being reachable.",
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = {},
                label = { Text(if (controller.paired) "paired: ${controller.pairedDeviceId?.take(8)}…" else "not paired") },
            )
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { controller.refreshAdb() },
                label = {
                    Text(
                        when (controller.adbReady) {
                            true -> "adb: device ready"
                            false -> "adb: no device"
                            null -> "adb: checking…"
                        },
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { controller.toggleDiscovery() },
                label = { Text(if (controller.discovering) "Wi-Fi discovery: on" else "Wi-Fi discovery: off") },
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.testConnection() }, enabled = !controller.busy) {
                Text("Test connection")
            }
            if (controller.busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp))
            }
            controller.backendLabel?.let {
                Spacer(Modifier.width(12.dp))
                Text("backend: $it", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (controller.capabilitiesText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors()) {
                SelectionContainer {
                    Text(
                        controller.capabilitiesText,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Background service", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keeps looking for the phone even when this window is closed, so a " +
                            "connection is always ready. ${controller.serviceDetail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (controller.serviceInstalled) {
                    OutlinedButton(onClick = { controller.removeService() }, enabled = !controller.busy) {
                        Text("Remove")
                    }
                } else {
                    FilledTonalButton(onClick = { controller.installService() }, enabled = !controller.busy) {
                        Text("Run at startup")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Discovered on Wi-Fi", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (controller.devices.isEmpty()) {
            Text(
                "No devices yet. Open MCPserved on your phone, arm the service, and make sure it is on " +
                    "the same Wi-Fi network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            controller.devices.forEach { device ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${device.host}:${device.port} · ${device.deviceId.take(12)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { controller.testDevice(device) }, enabled = !controller.busy) {
                            Text("Test")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairScreen(controller: AppController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading(
            "Pair",
            "A one-time key exchange. Both public keys travel by QR in both directions, so nothing in " +
                "the middle ever holds the secret.",
        )

        Text(
            "1.  On the phone, open MCPserved → Pair and copy the string under its QR code.\n" +
                "2.  Paste it below and press Pair.\n" +
                "3.  Scan the QR that appears here back on the phone to finish.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = controller.pairInput,
            onValueChange = { controller.pairInput = it },
            label = { Text("Device pairing payload (mcpserved:2:…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.pair() }, enabled = !controller.busy) { Text("Pair") }
            if (controller.paired) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { controller.unpair() }) { Text("Unpair") }
            }
        }
        controller.pairError?.let {
            Spacer(Modifier.height(8.dp))
            Text("Pairing failed: $it", color = MaterialTheme.colorScheme.error)
        }

        controller.pairReply?.let { reply ->
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Scan this on the phone to complete pairing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Image(bitmap = remember(reply) { qrImage(reply) }, contentDescription = "pairing QR")
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(reply, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The phone shows 'Paired' once it has the key. Then arm the service — the desktop finds " +
                    "it on Wi-Fi automatically, or bridges over adb.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostsScreen(controller: AppController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading(
            "AI Hosts",
            "One click wires this server into an AI client's config, pointed at this app in stdio mode. " +
                "No file editing.",
        )

        Button(onClick = { controller.installAll() }) {
            Text("⚡  Connect all popular AIs")
        }
        Spacer(Modifier.height(16.dp))

        HostRow("Claude Code", "Registered via the claude CLI (user scope).") { controller.installClaudeCode() }
        Hosts.targets.forEach { target ->
            HostRow(target.label, target.note) { controller.installHost(target) }
        }

        if (controller.hostOutcomes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Results", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            controller.hostOutcomes.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun HostRow(label: String, note: String?, onInstall: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onInstall) { Text("Install") }
        }
    }
}

@Composable
private fun LogScreen(controller: AppController) {
    Column(Modifier.fillMaxSize()) {
        Heading("Log")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(controller.logLines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
