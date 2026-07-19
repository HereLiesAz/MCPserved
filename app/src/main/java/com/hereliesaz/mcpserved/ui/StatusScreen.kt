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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Readiness at a glance, and the buttons to fix whatever is not ready.
 *
 * The three preconditions — accessibility bound, notification access granted,
 * paired — are each shown with the action that resolves them, because a status
 * line that reports a problem without offering its remedy is a line the user has
 * to leave the app to act on.
 *
 * The order is deliberate. Accessibility first, because nothing observes or acts
 * without it and every other readiness check is moot while it is off.
 */
@Composable
fun StatusScreen(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("MCPserved", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "A remote holds no power here until three things are true and a grant " +
                "exists. This screen is those three things.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        ReadyRow(
            label = "Accessibility",
            ready = vm.a11yConnected,
            detail = if (vm.a11yConnected) "Bound. The screen can be read and touched."
            else "Off. Nothing works until this is enabled.",
            action = "Open settings",
            onAction = vm::openAccessibilitySettings
        )

        Spacer(Modifier.height(20.dp))

        ReadyRow(
            label = "Notification access",
            ready = true,
            detail = "Optional. Enables reading the notification shade.",
            action = "Open settings",
            onAction = vm::openNotificationSettings
        )

        Spacer(Modifier.height(20.dp))

        ReadyRow(
            label = "Service",
            ready = vm.serviceRunning,
            detail = if (vm.serviceRunning) "Armed. Listening for a session request."
            else "Stopped. Cannot be woken.",
            action = if (vm.serviceRunning) "Disarm" else "Arm",
            onAction = { if (vm.serviceRunning) vm.stopService() else vm.startService() }
        )
    }
}

@Composable
private fun ReadyRow(
    label: String,
    ready: Boolean,
    detail: String,
    action: String,
    onAction: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) "ready" else "not ready",
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (ready && action == "Disarm") {
            OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        } else {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}
