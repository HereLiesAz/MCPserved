package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Connect" ties together every way a model reaches this device, as three
 * separate tabs rather than one long scroll — each is a distinct flow with
 * its own audience, and mixing them read as one undifferentiated wall of
 * settings:
 *
 * - **Direct**: the device is itself the MCP server. No desktop process, no
 *   pairing — just an endpoint and a bearer token.
 * - **Remote**: opt-in, off by default. Widens *how* the Direct endpoint is
 *   reached, for a host with no local network path to the phone.
 * - **Desktop bridge**: the separate `mcpserved` adb quick-connect path,
 *   paired by QR — its own identity, its own revocation.
 */
private enum class ConnectTab(val label: String) {
    DIRECT("Direct"),
    REMOTE("Remote"),
    DESKTOP("Desktop bridge"),
}

@Composable
fun ConnectScreen(vm: MainViewModel) {
    var tab by remember { mutableStateOf(ConnectTab.DIRECT) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Connect a model",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)
        )

        TabRow(selectedTabIndex = tab.ordinal) {
            ConnectTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label) }
                )
            }
        }

        when (tab) {
            ConnectTab.DIRECT -> DirectConnectScreen(vm)
            ConnectTab.REMOTE -> RemoteAccessScreen(vm)
            ConnectTab.DESKTOP -> DesktopBridgeScreen(vm)
        }
    }
}
