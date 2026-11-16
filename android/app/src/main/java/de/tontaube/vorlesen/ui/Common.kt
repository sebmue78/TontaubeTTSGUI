package de.tontaube.vorlesen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.tontaube.vorlesen.net.ServerStatus
import java.util.Locale

@Composable
fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(20.dp))
                Text(message)
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action()
        }
    }
}

@Composable
fun StatusDot(status: ServerStatus) {
    val color = when (status) {
        is ServerStatus.Ready -> Color(0xFF2E9E5B)
        ServerStatus.Loading, ServerStatus.Checking -> Color(0xFFE0A000)
        is ServerStatus.Error -> MaterialTheme.colorScheme.error
        ServerStatus.Unknown -> MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
}

fun ServerStatus.label(): String = when (this) {
    is ServerStatus.Ready -> "Verbunden"
    ServerStatus.Loading -> "Server lädt Modelle …"
    ServerStatus.Checking -> "Prüfe Verbindung …"
    is ServerStatus.Error -> message
    ServerStatus.Unknown -> "Kein Server eingerichtet"
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.GERMANY, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format(Locale.GERMANY, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
}

fun formatDuration(ms: Long): String {
    if (ms < 0) return "–:–"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Grobe Hördauer: ca. 14 Zeichen pro Sekunde bei normalem Tempo. */
fun estimateListening(chars: Int): String {
    val minutes = chars / 14 / 60
    return when {
        minutes < 1 -> "< 1 Min."
        minutes < 60 -> "ca. $minutes Min."
        else -> "ca. ${minutes / 60} Std. ${minutes % 60} Min."
    }
}

