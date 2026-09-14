package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import soy.engindearing.omnitak.mobile.data.ImportedServerConfig
import soy.engindearing.omnitak.mobile.data.ServerImportPreview
import soy.engindearing.omnitak.mobile.ui.theme.TacticalAccent
import soy.engindearing.omnitak.mobile.ui.theme.TacticalSurface

/**
 * Confirmation shown before a server from a deep link / QR is added and
 * auto-connected. `MainActivity` is exported with a BROWSABLE VIEW filter
 * for `tak://`, `atak://`, `omnitak://`, so any web page, app, NFC tag, or
 * camera scan can deliver one of these links; without this step a single
 * tap made the device stream its position to an attacker-chosen host
 * (audit 2026-09-14, H3). Mirrors the ImportPreviewDialog used for profiles.
 */
@Composable
fun ServerImportConfirmDialog(
    cfg: ImportedServerConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = ServerImportPreview.from(cfg)
    val onBg = MaterialTheme.colorScheme.onBackground
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = {
            Text(
                if (preview.willEnroll) "Enroll with TAK Server?" else "Add TAK Server?",
                color = onBg,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    preview.name,
                    color = onBg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                DetailRow("Server", preview.endpoint)
                DetailRow("Transport", if (preview.useTLS) "TLS" else "Plain TCP")
                preview.username?.let { DetailRow("Username", it) }
                DetailRow(
                    "Action",
                    if (preview.willEnroll) "Request a client certificate, then connect" else "Connect",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    preview.consequence,
                    color = onBg.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
                preview.warnings.forEach { warning ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Warning: $warning",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    if (preview.willEnroll) "Enroll & Connect" else "Add & Connect",
                    color = TacticalAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = onBg.copy(alpha = 0.7f)) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
    }
}
