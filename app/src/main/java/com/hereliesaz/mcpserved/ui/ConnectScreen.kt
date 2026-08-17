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
 * "Connect" ties together every way a model reaches this device, as separate
 * tabs rather than one long scroll — each is a distinct flow with its own
 * audience, and mixing them read as one undifferentiated wall of settings.
 *
 * **This phone** leads, and is the default tab. It's the only path that
 * needs nothing else — no computer, no cable, no separate device of any
 * kind — which is the thing most people opening this screen actually want:
 * an AI session running anywhere, including in the cloud, reaching this
 * phone with nothing but the phone itself in the loop. **Direct** and
 * **Desktop bridge** both assume a second device (a computer with `adb`,
 * or the desktop app) is available and come after, for operators who have one.
 */
private enum class ConnectTab(val label: String) {
    REMOTE("This phone"),
    DIRECT("Direct"),
    DESKTOP("Desktop bridge"),
}

@Composable
fun ConnectScreen(vm: MainViewModel) {
    var tab by remember { mutableStateOf(ConnectTab.REMOTE) }

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
            ConnectTab.REMOTE -> RemoteAccessScreen(vm)
            ConnectTab.DIRECT -> DirectConnectScreen(vm)
            ConnectTab.DESKTOP -> DesktopBridgeScreen(vm)
        }
    }
}
