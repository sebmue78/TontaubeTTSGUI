package de.tontaube.vorlesen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.tontaube.core.BufferPolicy
import de.tontaube.core.ConnectLink
import de.tontaube.core.TtsSettings
import de.tontaube.vorlesen.BuildConfig
import de.tontaube.vorlesen.data.ServerEntity
import de.tontaube.vorlesen.net.ServerStatus
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val servers by container.servers.servers.collectAsStateWithLifecycle(emptyList())
    val active by container.servers.active.collectAsStateWithLifecycle()
    val status by container.servers.status.collectAsStateWithLifecycle()
    val defaults by container.prefs.defaults.collectAsStateWithLifecycle(TtsSettings())
    val theme by container.prefs.theme.collectAsStateWithLifecycle("system")
    val bufferMode by container.prefs.bufferMode.collectAsStateWithLifecycle(BufferPolicy.AUTO)
    val playback by container.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerEntity?>(null) }
    var adding by remember { mutableStateOf<ConnectLink?>(null) }
    var defaultsOpen by remember { mutableStateOf(false) }
    var audioBytes by remember { mutableLongStateOf(-1L) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { audioBytes = container.projects.totalAudioBytes() }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val link = code.rawValue?.let { ConnectLink.parse(it) }
                if (link == null) scope.launch { snackbar.showSnackbar("Das ist kein Tontaube-QR-Code.") } else adding = link
            }
            .addOnFailureListener { e ->
                scope.launch { snackbar.showSnackbar("QR-Scanner nicht verfügbar (${e.message}). Bitte Adresse und Key manuell eingeben.") }
            }
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Einstellungen") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Tontaube-Server") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(status)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(active?.name ?: "Kein Server", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    active?.let { "${it.url} · ${status.label()}" } ?: "Füge den Server der Desktop-App hinzu.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (active != null) IconButton(onClick = { container.servers.refresh() }) { Icon(Icons.Rounded.Refresh, "Erneut prüfen") }
                        }
                        Text(
                            "In der Desktop-App unter „Im Netzwerk freigeben (Android-App)“ den Schalter aktivieren und den QR-Code scannen.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scan() }) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("QR-Code scannen") }
                            OutlinedButton(onClick = { adding = ConnectLink("", null, null) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Manuell") }
                        }
                    }
                }
            }
            if (servers.size > 1 || (servers.size == 1 && active == null)) {
                items(servers, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.url) },
                        leadingContent = { RadioButton(selected = active?.id == s.id, onClick = { scope.launch { container.servers.setActive(s.id) } }) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = s }) { Icon(Icons.Rounded.Edit, "Bearbeiten") }
                                IconButton(onClick = { scope.launch { container.servers.delete(s) } }) { Icon(Icons.Rounded.Delete, "Entfernen") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            } else if (active != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = active }) { Text("Bearbeiten") }
                        TextButton(onClick = { scope.launch { container.servers.delete(active!!) } }) { Text("Entfernen") }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Neue Projekte") }
            item {
                ListItem(
                    headlineContent = { Text("Standard-Einstellungen") },
                    supportingContent = {
                        Text("${de.tontaube.core.Languages.label(defaults.language)} · ${de.tontaube.core.Styles.label(defaults.tag)} · Stimme, Regler und Tempo für neu importierte Dokumente")
                    },
                    leadingContent = { Icon(Icons.Rounded.Tune, null) },
                    modifier = Modifier.padding(0.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    trailingContent = { FilledTonalButton(onClick = { defaultsOpen = true }) { Text("Ändern") } },
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Wiedergabe") }
            item {
                val options = listOf(BufferPolicy.AUTO) + BufferPolicy.manualOptions
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Dropdown(
                        label = "Vorpuffer",
                        options = options.map { BufferPolicy.label(it) to it.toString() },
                        value = bufferMode.toString(),
                        onChange = { v -> scope.launch { container.prefs.setBufferMode(v.toInt()) } },
                    )
                    val factor = playback.realtimeFactor
                    Text(
                        if (bufferMode == BufferPolicy.AUTO) {
                            "So viel Audio wird vorab erzeugt, bevor das Vorlesen startet oder nach einem Stocken weitergeht. " +
                                "Automatisch richtet sich das nach der gemessenen Geschwindigkeit deines Servers: Ist er langsamer als Echtzeit, " +
                                "wird länger gepuffert, damit danach etwa ${BufferPolicy.LISTEN_TARGET_S / 60} Minuten am Stück laufen. " +
                                if (factor != null) {
                                    "Zuletzt gemessen: ${BufferPolicy.describeFactor(factor)} → ${BufferPolicy.targetSeconds(BufferPolicy.AUTO, factor, start = false)} s Puffer."
                                } else {
                                    "Gemessen wird beim ersten Vorlesen."
                                }
                        } else {
                            "Vor dem Start und nach jedem Stocken werden ${BufferPolicy.label(bufferMode)} Audio abgewartet. Mehr Puffer heißt: " +
                                "länger warten, dafür seltener Unterbrechungen." +
                                (factor?.let { " Gemessen: ${BufferPolicy.describeFactor(it)}." } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Speicher") }
            item {
                ListItem(
                    headlineContent = { Text("Erzeugtes Audio") },
                    supportingContent = {
                        Text(
                            (if (audioBytes >= 0) formatBytes(audioBytes) else "…") +
                                " · Bereits vorgelesene Abschnitte bleiben gespeichert, damit Fortsetzen und erneutes Hören ohne Server klappen.",
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Storage, null) },
                    trailingContent = { TextButton(onClick = { confirmClear = true }, enabled = audioBytes > 0) { Text("Leeren") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Darstellung") }
            item {
                val options = listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = theme == key, onClick = { scope.launch { container.prefs.setTheme(key) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                ListItem(
                    headlineContent = { Text("Tontaube Vorlesen ${BuildConfig.VERSION_NAME}") },
                    supportingContent = {
                        Text(
                            "Sprachsynthese mit TontaubeV1.1 auf deinem eigenen Server. Das Modell steht unter der Tontaube Community Model License; " +
                                "bitte deren Bedingungen beachten, besonders bei geklonten Stimmen.",
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Dns, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    adding?.let { link ->
        ServerDialog(
            initialName = link.name.orEmpty(), initialUrl = link.url, initialKey = link.apiKey.orEmpty(),
            onDismiss = { adding = null },
            onSaved = { adding = null; scope.launch { snackbar.showSnackbar("Server „$it“ gespeichert") } },
        )
    }
    editing?.let { s ->
        ServerDialog(
            serverId = s.id, initialName = s.name, initialUrl = s.url, initialKey = s.apiKey.orEmpty(),
            onDismiss = { editing = null }, onSaved = { editing = null },
        )
    }
    if (defaultsOpen) {
        SettingsSheet(
            title = "Standard für neue Projekte",
            initial = defaults,
            onDismiss = { defaultsOpen = false },
        ) {
            defaultsOpen = false
            scope.launch { container.prefs.setDefaults(it) }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            "Erzeugtes Audio löschen?",
            "Alle gespeicherten Abschnitte werden gelöscht (${formatBytes(audioBytes)}). Leseposition und Projekte bleiben erhalten; das Audio wird beim nächsten Hören neu erzeugt.",
            "Löschen",
            onConfirm = { scope.launch { container.projects.clearAllAudio(); audioBytes = container.projects.totalAudioBytes() } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
}

/** Server hinzufügen oder bearbeiten, mit Verbindungstest. */
@UnstableApi
@Composable
fun ServerDialog(
    serverId: Long? = null,
    initialName: String,
    initialUrl: String,
    initialKey: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<ServerStatus>(ServerStatus.Unknown) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (initialUrl.isNotBlank() && serverId == null) {
            test = ServerStatus.Checking
            test = container.servers.test(url, key)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (serverId == null) "Server hinzufügen" else "Server bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it; test = ServerStatus.Unknown },
                    label = { Text("Adresse") }, placeholder = { Text("192.168.1.20:8080") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    key, { key = it; test = ServerStatus.Unknown },
                    label = { Text("API-Key") }, singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showKey) "Verbergen" else "Anzeigen")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(test)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (test == ServerStatus.Unknown) "Noch nicht getestet" else test.label(),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { test = ServerStatus.Checking; test = container.servers.test(url, key) } }, enabled = url.isNotBlank()) {
                        Text("Testen")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    scope.launch {
                        try {
                            val id = container.servers.save(name, url, key, serverId)
                            container.servers.setActive(id)
                            onSaved(name.ifBlank { ConnectLink.normalizeUrl(url).orEmpty() })
                        } catch (e: IllegalArgumentException) {
                            error = "Ungültige Adresse. Beispiel: 192.168.1.20:8080"
                        }
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
