package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The prominent disclosure shown before anything else can happen.
 *
 * The AccessibilityService is the whole product and the permission most abused by
 * malware, so the user sees, in plain terms and before any settings screen, what
 * granting it will and will not allow — and has to affirmatively accept it. This
 * is both the honest thing to do and what the Play policy requires of an app that
 * uses accessibility for control rather than for the user's own assistive needs.
 *
 * Nothing here is load-bearing on its own: the service still cannot bind until
 * the user enables it in system settings, cannot connect until they pair, and
 * cannot act until they grant a package. The disclosure states that plainly
 * rather than implying the button is the last line of defence.
 */
@Composable
fun DisclosureScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Before you begin", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(16.dp))

        Text(
            "MCPserved lets a desktop client you pair with this device read the " +
                "screen and perform taps, swipes, and text entry on your behalf, so " +
                "that an assistant running on your own computer can operate the phone " +
                "for you.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(16.dp))

        Section(
            "It uses the Accessibility Service",
            "To read the screen and dispatch input it relies on Android's " +
                "Accessibility Service. That is a powerful permission. This app uses " +
                "it only to carry out the actions the paired client requests, and only " +
                "for the applications you explicitly grant."
        )

        Section(
            "It stays on your device",
            "The client connects over a local connection you set up yourself with " +
                "adb (USB, or adb-over-Wi-Fi). The app makes no connection to the " +
                "internet and sends your screen contents to no server. Nothing is " +
                "reachable from the network."
        )

        Section(
            "You stay in control",
            "Nothing can happen until you enable the service, pair a client, arm the " +
                "app, and grant specific packages. Each session is time-limited and " +
                "shown in an ongoing notification you can stop from at any moment, and " +
                "every action is logged while a session is open. An empty grant list " +
                "leaves the app unable to touch anything."
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "By continuing, you agree to let MCPserved use the Accessibility Service " +
                "for this purpose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("I understand and agree")
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Not now")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Top) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}
