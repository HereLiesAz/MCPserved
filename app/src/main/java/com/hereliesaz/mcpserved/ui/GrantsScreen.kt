package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.hereliesaz.mcpserved.transport.Scope

/**
 * Per-package authorization.
 *
 * The list is the security model in its entirety. There is no denylist of
 * sensitive applications, because a denylist enumerates badness and is wrong the
 * moment something is installed or rebranded. Nothing is reachable until it
 * appears here with a scope, and an empty table renders the whole service inert.
 *
 * Granted packages sort to the top. What has been authorized should be the first
 * thing visible on opening the screen, not something to be found by scrolling
 * past two hundred applications that have not been.
 */
@Composable
fun GrantsScreen(vm: MainViewModel) {
    val apps by vm.apps.collectAsState()
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MainViewModel.AppRow?>(null) }
    var confirmRevokeAll by remember { mutableStateOf(false) }

    val visible = remember(apps, filter) {
        if (filter.isBlank()) apps
        else apps.filter {
            it.label.contains(filter, true) || it.pkg.contains(filter, true)
        }
    }

    val grantedCount = apps.count { it.scopes.isNotEmpty() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (grantedCount == 0) "Nothing authorized"
                else "$grantedCount authorized",
                style = MaterialTheme.typography.displaySmall
            )
            if (grantedCount > 0) {
                TextButton(onClick = { confirmRevokeAll = true }) {
                    Text("Revoke all", color = DeniedRed)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.pkg }) { row ->
                AppListRow(row) { editing = row }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    editing?.let { row ->
        ScopeDialog(
            row = row,
            onDismiss = { editing = null },
            onConfirm = { scopes, ttl ->
                vm.setScopes(row.pkg, scopes, ttl)
                editing = null
            }
        )
    }

    if (confirmRevokeAll) {
        AlertDialog(
            onDismissRequest = { confirmRevokeAll = false },
            title = { Text("Revoke everything?") },
            text = {
                Text(
                    "Every grant is removed. Any live session immediately loses " +
                        "access to all packages."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.revokeAll()
                    confirmRevokeAll = false
                }) { Text("Revoke", color = DeniedRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AppListRow(row: MainViewModel.AppRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (row.scopes.isNotEmpty()) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.background
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium)
            Text(
                row.pkg + if (row.isSystem) "  · system" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (row.scopes.isNotEmpty()) {
            Text(
                row.scopes.joinToString(" ") { it.name.take(3) },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Scope selection for one package.
 *
 * Scopes are individually selectable rather than offered only as presets. The
 * presets cover the common cases, but the difference between observing an
 * application and typing into it is exactly the distinction a user might want to
 * draw for one particular app, and collapsing it into three tiers would remove
 * the only reason to have separate scopes at all.
 */
@Composable
private fun ScopeDialog(
    row: MainViewModel.AppRow,
    onDismiss: () -> Unit,
    onConfirm: (Set<Scope>, Int?) -> Unit
) {
    var selected by remember { mutableStateOf(row.scopes) }
    var bounded by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.label) },
        text = {
            Column {
                Text(
                    row.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Scope.entries.forEach { scope ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = scope in selected,
                            onClick = {
                                selected = if (scope in selected) selected - scope
                                else selected + scope
                            },
                            label = { Text(scope.name) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            describe(scope),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                FilterChip(
                    selected = bounded,
                    onClick = { bounded = !bounded },
                    label = { Text(if (bounded) "1 hour" else "Until revoked") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, if (bounded) 3600 else null) }) {
                Text(if (selected.isEmpty()) "Revoke" else "Grant")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun describe(scope: Scope): String = when (scope) {
    Scope.OBSERVE -> "read the screen and notifications"
    Scope.INTERACT -> "tap, swipe, scroll, press keys"
    Scope.TYPE -> "enter text and set the clipboard"
    Scope.LAUNCH -> "bring this app to the foreground"
    Scope.SHELL -> "run shell commands while this app is open"
}
