package de.tontaube.vorlesen.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import de.tontaube.core.DocumentFormat
import de.tontaube.core.TtsSettings
import de.tontaube.vorlesen.data.ProjectEntity
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    snackbar: SnackbarHostState,
    onOpenProject: (Long) -> Unit,
    onImport: (Uri, String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalContainer.current
    val projects by container.projects.projects.collectAsStateWithLifecycle(null)
    val status by container.servers.status.collectAsStateWithLifecycle()
    val active by container.servers.active.collectAsStateWithLifecycle()
    val playback by container.engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pasteOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, null)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Bibliothek") },
                actions = {
                    AssistChip(
                        onClick = { if (active == null) onOpenSettings() else container.servers.refresh() },
                        label = { Text(active?.name ?: "Server einrichten", maxLines = 1) },
                        leadingIcon = { StatusDot(status) },
                    )
                    IconButton(onClick = { pasteOpen = true }) { Icon(Icons.Rounded.ContentPaste, "Text einfügen") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(DocumentFormat.pickerMimeTypes) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Importieren") },
            )
        },
    ) { padding ->
        val list = projects
        when {
            list == null -> Box(Modifier.fillMaxSize())
            list.isEmpty() -> EmptyState(
                Icons.AutoMirrored.Rounded.MenuBook,
                "Noch keine Vorlese-Projekte",
                "Importiere ein PDF, EPUB, Word- oder Textdokument. Tontaube liest es dir vor, und du kannst jederzeit an der Stelle weiterhören.",
                Modifier.padding(padding),
            ) {
                if (active == null) {
                    FilledTonalButton(onClick = onOpenSettings) { Text("Zuerst Server verbinden") }
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val hero = list.firstOrNull { it.lastPlayedAt != null && !it.finished }
                if (hero != null) {
                    item(key = "hero") {
                        ContinueCard(hero, isCurrent = playback.projectId == hero.id && playback.isPlaying, onOpen = { onOpenProject(hero.id) }) {
                            container.engine.open(hero.id)
                            onOpenProject(hero.id)
                        }
                    }
                    item(key = "header") {
                        Text("Alle Projekte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                }
                items(list, key = { it.id }) { p ->
                    ProjectRow(
                        p,
                        onOpen = { onOpenProject(p.id) },
                        onRename = { title -> scope.launch { container.projects.rename(p.id, title) } },
                        onClearAudio = {
                            scope.launch {
                                container.projects.clearAudio(p.id)
                                snackbar.showSnackbar("Erzeugtes Audio gelöscht")
                            }
                        },
                        onDelete = {
                            scope.launch {
                                container.engine.onProjectDeleted(p.id)
                                container.projects.delete(p.id)
                                snackbar.showSnackbar("„${p.title}“ gelöscht")
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (pasteOpen) {
        PasteTextDialog(onDismiss = { pasteOpen = false }) { title, text ->
            pasteOpen = false
            scope.launch {
                val id = container.projects.importText(title, text)
                onOpenProject(id)
            }
        }
    }
}

@Composable
private fun Cover(p: ProjectEntity, size: Int = 56) {
    val (a, b) = coverColors(p.id * 7 + p.title.hashCode(), isSystemInDarkTheme())
    Box(
        Modifier.size(width = size.dp, height = (size * 1.3f).dp)
            .background(Brush.linearGradient(listOf(a, b)), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.title.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
            color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
    }
}

@UnstableApi
@Composable
private fun ContinueCard(p: ProjectEntity, isCurrent: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val (a, b) = coverColors(p.id * 7 + p.title.hashCode(), isSystemInDarkTheme())
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(a, b))).padding(20.dp)) {
            Column {
                Text("Weiterhören", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(p.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.author?.let { Text(it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { p.progress }, color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(p.progress * 100).toInt()} % · Abschnitt ${p.segmentIndex + 1} von ${p.segmentCount}",
                            color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledTonalButton(onClick = onPlay) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isCurrent) "Läuft" else "Weiter")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    p: ProjectEntity,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onClearAudio: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val settings = remember(p.settingsJson) { TtsSettings.decode(p.settingsJson) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(p)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    p.author,
                    runCatching { DocumentFormat.valueOf(p.format).label }.getOrNull(),
                    estimateListening(p.charCount),
                    de.tontaube.core.Languages.label(settings.language),
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.finished) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text("Fertig gehört", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.weight(1f).height(4.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${(p.progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mehr") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Umbenennen") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Erzeugtes Audio löschen") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = { menu = false; onClearAudio() })
                    DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(p.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Umbenennen") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Titel") }) },
            confirmButton = { TextButton(onClick = { onRename(title); renaming = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Projekt löschen?",
            "„${p.title}“ wird mit Leseposition und erzeugtem Audio gelöscht. Die Originaldatei bleibt erhalten.",
            "Löschen", onDelete, { confirmDelete = false },
        )
    }
}

@Composable
private fun PasteTextDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text einfügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onCreate(title.ifBlank { text.trim().take(40) }, text) }) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
