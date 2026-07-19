package com.hereliesaz.mcpserved.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.mcpserved.grant.SessionLog
import com.hereliesaz.mcpserved.service.ControlService
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of everything the remote caller has attempted this session.
 *
 * The log is in-memory and clears when the session ends, by design — a durable
 * record of every action taken against the phone would itself be a record of
 * everything on the phone. This screen exists to be watched during a session and
 * to make a wrong action visible the instant it happens, not to be consulted
 * afterwards.
 *
 * A denied entry is the one thing rendered in colour. On a monochrome screen a
 * single red line cannot be scrolled past without being seen, which is the whole
 * reason the interface has no other colour to compete with it.
 */
@Composable
fun SessionLogScreen() {
    val log = ControlService.instance?.log
    // Stand-in so the composable renders identically when the service is absent.
    val fallback = remember { MutableStateFlow<List<SessionLog.Entry>>(emptyList()) }
    val entries by (log?.entries ?: fallback).collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Session log", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                log == null -> "Service not running."
                entries.isEmpty() -> "Nothing yet. Actions appear here as they happen."
                else -> "${entries.size} actions this session."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { entry ->
                LogRow(entry)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun LogRow(entry: SessionLog.Entry) {
    val time = remember(entry.atEpochMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.atEpochMs))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        entry.denied -> DeniedRed
                        !entry.ok -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        entry.denied -> "DENIED"
                        entry.ok -> "ok"
                        else -> "failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.denied) DeniedRed
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                entry.pkg + (entry.note?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
