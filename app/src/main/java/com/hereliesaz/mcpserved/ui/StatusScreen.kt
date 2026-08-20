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
import com.hereliesaz.mcpserved.BuildConfig

/**
 * One button. Its label and action track exactly where the user is.
 *
 * The readiness signal itself is the one thing that differs by flavor:
 * `github` reads [MainViewModel.a11yConnected] (whether AccessibilityService
 * is bound — the classic path); `playstore`, which never declares that
 * service at all, reads [MainViewModel.shizukuReady] instead. Everything
 * downstream of "is the backend ready" — arming, disarming, the connected
 * state — is identical either way, since both flavors share the exact same
 * [com.hereliesaz.mcpserved.service.ControlService].
 *
 * 1. Backend not ready → **"Enable & connect"** / **"Connect Shizuku"** opens
 *    whatever system flow that flavor needs — accessibility settings (a
 *    switch no app can flip for itself), or Shizuku's own install/pair/
 *    permission chain (see [MainViewModel.connectShizuku]).
 * 2. The moment the user returns with it ready, [MainViewModel.refreshStatus]
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
    val isPlaystore = BuildConfig.FLAVOR == "playstore"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("MCPserved", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))

        // Keyed on `tick`: a11yConnected/shizukuReady/serviceRunning are plain
        // getters over static instances, not observable state, so this is what
        // forces a fresh read of them — and thus a redraw of the right button —
        // the moment refreshStatus() fires on ON_RESUME.
        key(tick) {
            val backendReady = if (isPlaystore) vm.shizukuReady else vm.a11yConnected
            val armed = vm.serviceRunning
            val connected = backendReady && armed

            Text(
                if (connected) "Connected. Nothing can touch an app you haven't granted."
                else "One step from ready. Nothing works until it's done, and nothing " +
                    "acts until you grant a specific app on the Grants tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))

            when {
                !backendReady -> Column {
                    Button(
                        onClick = if (isPlaystore) vm::connectShizuku else vm::openAccessibilitySettings,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isPlaystore) shizukuButtonLabel(vm) else "Enable & connect") }
                    if (isPlaystore && !vm.shizukuReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                !vm.shizukuInstalled ->
                                    "Opens Shizuku's Play Store listing. Install it, then come back " +
                                        "and tap this button again to pair."
                                else ->
                                    "Opens Developer options → Wireless debugging. Tap \"Pair device " +
                                        "with pairing code\", enter it in Shizuku, then come back here " +
                                        "— the permission prompt appears automatically."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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

/** Which rung of Shizuku setup the button's label should name. */
private fun shizukuButtonLabel(vm: MainViewModel): String = when {
    !vm.shizukuInstalled -> "Install Shizuku"
    else -> "Connect Shizuku"
}
