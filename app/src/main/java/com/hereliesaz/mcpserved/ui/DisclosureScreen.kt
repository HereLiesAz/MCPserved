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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.mcpserved.R

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
        Text(
            stringResource(R.string.disclosure_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.disclosure_intro),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(16.dp))

        Section(
            stringResource(R.string.disclosure_a11y_title),
            stringResource(R.string.disclosure_a11y_body)
        )

        Section(
            stringResource(R.string.disclosure_local_title),
            stringResource(R.string.disclosure_local_body)
        )

        Section(
            stringResource(R.string.disclosure_control_title),
            stringResource(R.string.disclosure_control_body)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.disclosure_agree),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.disclosure_accept))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.disclosure_decline))
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
