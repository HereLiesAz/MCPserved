package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Record a sequence of taps, holds, and typed text against one app, save it
 * under a name, and run it again later — from here, or from an AI host,
 * since both go through the same `macro_run` op on the same
 * [com.hereliesaz.mcpserved.service.Dispatcher]. A macro is just a stored
 * list of the same [com.hereliesaz.mcpserved.transport.Request]s an AI
 * action would send, so it can never do more than its app's grant permits.
 *
 * Recording works by watching accessibility events for the target app —
 * clicks, long clicks, and finished text entry — not by capturing raw
 * touches. That means swipes, scrolls, and global keys (back/home/recents)
 * cannot be recorded; a macro built entirely from taps and typed text
 * covers most real flows, but not gestures.
 */
@Composable
fun MacrosScreen(vm: MainViewModel) {
    val macros by vm.macros.collectAsState()
    val recording by vm.recordingState.collectAsState()
    val runResult by vm.macroRunResult.collectAsState()
    val apps by vm.apps.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Macros", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Record taps and typed text in one app, save the sequence, and run it " +
                "again — yourself, or by asking the AI to. Swipes, scrolls, and global " +
                "keys can't be captured this way; only taps, holds, and finished text " +
                "entry are.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        when (val state = recording) {
            is MainViewModel.RecordingState.Recording -> {
                var name by remember { mutableStateOf("") }
                Text("Recording — ${state.pkg}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${state.steps} step${if (state.steps == 1) "" else "s"} captured. " +
                            "Switch to ${state.pkg} to keep going, or name it and stop below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Macro name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = { vm.stopRecording(name) },
                        enabled = name.isNotBlank() && state.steps > 0
                    ) { Text("Stop and save") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = vm::cancelRecording) { Text("Cancel") }
                }
            }

            MainViewModel.RecordingState.Idle -> {
                Text("Record a new macro", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val recordable = apps.filter { it.scopes.isNotEmpty() }
                if (recordable.isEmpty()) {
                    Text(
                        "No authorized apps yet — grant one on the Grants tab first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Pick an app. It opens, the recording starts, and it keeps " +
                            "capturing until you come back here and stop it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    recordable.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.label, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = {
                                if (vm.startRecording(row.pkg)) {
                                    context.packageManager.getLaunchIntentForPackage(row.pkg)
                                        ?.let { context.startActivity(it) }
                                }
                            }) { Text("Record") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Saved macros", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        runResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }

        if (macros.isEmpty()) {
            Text(
                "Nothing recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            macros.forEach { macro ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(macro.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${macro.pkg} · ${macro.steps.size} step${if (macro.steps.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { vm.runMacro(macro.name) }) { Text("Run") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.deleteMacro(macro.name) }) { Text("Delete") }
                }
            }
        }
    }
}
