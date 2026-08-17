package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * One button. Its label and action track exactly where the user is:
 *
 * 1. Accessibility off → **"Enable & connect"** opens the one system settings
 *    screen Android requires for this — no app can flip that switch for
 *    itself, by OS design, so this is the one tap this screen cannot remove.
 * 2. The moment the user returns with it on, [MainViewModel.refreshStatus]
 *    (fired from `ON_RESUME` below) arms the service automatically. No second
 *    tap, no separate "Arm" step to remember.
 * 3. Connected → the button becomes a plain confirmation, with "Disarm" as a
 *    small secondary link underneath for the rare case someone wants it off.
 *
 * Notification access is optional and never blocks this button; it is a
 * one-line secondary link, not a row competing for the same attention.
 */
@Composable
fun StatusScreen(vm: MainViewModel) {
    val tick by vm.statusTick.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStatus() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("MCPserved", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))

        // Keyed on `tick`: a11yConnected/serviceRunning are plain getters over
        // static instances, not observable state, so this is what forces a
        // fresh read of them — and thus a redraw of the right button — the
        // moment refreshStatus() fires on ON_RESUME.
        key(tick) {
            val a11yReady = vm.a11yConnected
            val armed = vm.serviceRunning
            val connected = a11yReady && armed

            Text(
                if (connected) "Connected. Nothing can touch an app you haven't granted."
                else "One step from ready. Nothing works until it's done, and nothing " +
                    "acts until you grant a specific app on the Grants tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))

            when {
                !a11yReady -> Button(
                    onClick = vm::openAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Enable & connect") }

                !armed -> Button(
                    onClick = vm::startService,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }

                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Connected ✓") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = vm::stopService) { Text("Disarm") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = vm::openNotificationSettings) {
            Text("Also enable notification access (optional)")
        }
    }
}
