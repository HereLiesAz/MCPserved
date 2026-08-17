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
import androidx.compose.material3.OutlinedButton
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
 * One button: copy the config for the host you used last time (Claude Code,
 * the first time) straight to the clipboard. Paste it in and you're connected
 * — no picking through a list of six clients you don't use.
 *
 * "Choose a different host" is one tap away, collapsed by default, for the
 * cases that aren't "the usual one" — picking from it also becomes the new
 * one-tap default from then on.
 */
@Composable
fun DirectConnectScreen(vm: MainViewModel) {
    val bearer by vm.mcpBearer.collectAsState()
    val preferred by vm.preferredHost.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showOtherHosts by remember { mutableStateOf(false) }
    var justCopied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "This device is itself an MCP server — no desktop process needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = {
                clipboard.setText(AnnotatedString(vm.hostConfig(preferred)))
                justCopied = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy config for ${preferred.label}")
        }

        if (justCopied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Copied. Paste it into ${preferred.label} and you're connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = { showOtherHosts = !showOtherHosts }) {
            Text(if (showOtherHosts) "Hide other hosts" else "Not ${preferred.label}? Choose a different host")
        }

        if (showOtherHosts) {
            Spacer(Modifier.height(8.dp))
            vm.quickConnectHosts.filter { it.id != preferred.id }.forEach { host ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(host.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        host.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.hostConfig(host)))
                            vm.setPreferredHost(host)
                            justCopied = true
                            showOtherHosts = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy ${host.label} config & make it the default")
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Advanced",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(vm.mcpEndpoint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Text(bearer, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(bearer)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy token only")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = vm::rotateMcpToken,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Rotate token")
        }
    }
}
