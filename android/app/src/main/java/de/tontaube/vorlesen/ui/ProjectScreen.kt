package de.tontaube.vorlesen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import de.tontaube.core.SegmentedText
import de.tontaube.core.TtsSettings
import de.tontaube.vorlesen.net.ServerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(projectId: Long, snackbar: SnackbarHostState, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val container = LocalContainer.current
    val engine = container.engine
    val project by container.projects.observe(projectId).collectAsStateWithLifecycle(null)
    val playback by engine.state.collectAsStateWithLifecycle()
    val server by container.servers.active.collectAsStateWithLifecycle()
    val status by container.servers.status.collectAsStateWithLifecycle()
    val text by produceState<SegmentedText?>(null, projectId) { value = runCatching { container.projects.textAsync(projectId) }.getOrNull() }
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }

    val isCurrent = playback.projectId == projectId
    val currentIndex = if (isCurrent) playback.segmentIndex else project?.segmentIndex ?: 0
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    // Mitlaufen: aktuellen Abschnitt im Blick behalten
    LaunchedEffect(currentIndex, text, follow) {
        if (text != null && follow) list.animateScrollToItem(currentIndex.coerceAtLeast(0), scrollOffset = -120)
    }
    val currentVisible by remember(currentIndex) {
        derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.index == currentIndex } }
    }
    // Nur echtes Wischen durch den Nutzer beendet das Mitlaufen (nicht das automatische Scrollen)
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val chapter = text?.chapterOf(currentIndex)?.title
                        if (chapter != null && chapter != project?.title) {
                            Text(chapter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                actions = {
                    if ((text?.chapters?.size ?: 0) > 1) {
                        IconButton(onClick = { chaptersOpen = true }) { Icon(Icons.AutoMirrored.Rounded.List, "Kapitel") }
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Tune, "Stimme und Einstellungen") }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(!currentVisible && text != null) {
                SmallFloatingActionButton(onClick = { follow = true; scope.launch { list.animateScrollToItem(currentIndex, -120) } }) {
                    Icon(Icons.Rounded.MyLocation, "Zur aktuellen Stelle")
                }
            }
        },
        bottomBar = {
            PlayerPanel(
                isCurrent = isCurrent,
                hasServer = server != null,
                serverStatus = status,
                segmentIndex = currentIndex,
                segmentCount = text?.segments?.size ?: project?.segmentCount ?: 0,
                progress = project?.progress ?: 0f,
                speed = if (isCurrent) playback.speed else TtsSettings.decode(project?.settingsJson).playbackSpeed,
                onStart = { follow = true; engine.open(projectId) },
                onSeekSegment = { follow = true; if (isCurrent) engine.seekToSegment(it) else engine.open(projectId, it) },
                onSpeed = { engine.setSpeed(projectId, it) },
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        val t = text
        if (t == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(t.segments, key = { i, _ -> i }) { i, seg ->
                    val chapterStart = t.chapters.firstOrNull { it.firstSegment == i && t.chapters.size > 1 }
                    Column {
                        if (chapterStart != null) {
                            Text(
                                chapterStart.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (i == 0) 8.dp else 32.dp, bottom = 8.dp),
                            )
                        }
                        SegmentText(seg.text, active = i == currentIndex, activePlaying = isCurrent && playback.isPlaying) { jumpTo = i }
                    }
                }
            }
        }
    }

    jumpTo?.let { index ->
        AlertDialog(
            onDismissRequest = { jumpTo = null },
            title = { Text("Ab hier vorlesen?") },
            text = { Text(text?.segments?.getOrNull(index)?.text?.take(160)?.plus("…").orEmpty(), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    follow = true
                    if (isCurrent) engine.seekToSegment(index) else engine.open(projectId, index)
                    jumpTo = null
                }) { Text("Vorlesen") }
            },
            dismissButton = { TextButton(onClick = { jumpTo = null }) { Text("Abbrechen") } },
        )
    }

    if (chaptersOpen && text != null) {
        AlertDialog(
            onDismissRequest = { chaptersOpen = false },
            title = { Text("Kapitel") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(text!!.chapters) { _, ch ->
                        val active = text!!.chapterOf(currentIndex) == ch
                        ListItem(
                            headlineContent = { Text(ch.title, fontWeight = if (active) FontWeight.Bold else null) },
                            supportingContent = { Text("Abschnitt ${ch.firstSegment + 1}") },
                            modifier = Modifier.clickable {
                                chaptersOpen = false
                                follow = true
                                if (isCurrent) engine.seekToSegment(ch.firstSegment) else engine.open(projectId, ch.firstSegment)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chaptersOpen = false }) { Text("Schließen") } },
        )
    }

    if (settingsOpen && project != null) {
        SettingsSheet(
            title = "Stimme und Einstellungen",
            initial = TtsSettings.decode(project!!.settingsJson),
            note = "Gilt nur für dieses Projekt.",
            onDismiss = { settingsOpen = false },
        ) { newSettings ->
            settingsOpen = false
            scope.launch {
                container.projects.updateSettings(projectId, newSettings)
                engine.onSettingsChanged(projectId)
                snackbar.showSnackbar("Einstellungen gespeichert")
            }
        }
    }
}

@Composable
private fun SegmentText(text: String, active: Boolean, activePlaying: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent, label = "bg")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier.width(3.dp).height(if (active) 24.dp else 0.dp)
                .background(if (activePlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        if (active) Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = ReadingStyle,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        )
    }
}

@UnstableApi
@Composable
private fun PlayerPanel(
    isCurrent: Boolean,
    hasServer: Boolean,
    serverStatus: ServerStatus,
    segmentIndex: Int,
    segmentCount: Int,
    progress: Float,
    speed: Float,
    onStart: () -> Unit,
    onSeekSegment: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val engine = LocalContainer.current.engine
    val s by engine.state.collectAsStateWithLifecycle()
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (!hasServer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text("Noch kein Server verbunden.", Modifier.weight(1f))
                    Button(onClick = onOpenSettings) { Text("Einrichten") }
                }
                return@Column
            }
            // Fortschritt im ganzen Dokument (Abschnitte)
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val shownIndex = if (dragging) dragValue.toInt() else segmentIndex
            Slider(
                value = if (dragging) dragValue else segmentIndex.toFloat(),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; onSeekSegment(dragValue.toInt()) },
                valueRange = 0f..(segmentCount - 1).coerceAtLeast(1).toFloat(),
                enabled = segmentCount > 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Abschnitt ${shownIndex + 1} von $segmentCount",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent && s.durationMs != C.TIME_UNSET && s.durationMs > 0) {
                    Text("${formatDuration(s.positionMs)} / ${formatDuration(s.durationMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (isCurrent && s.isPlaying) {
                    Text(formatDuration(s.positionMs) + " · live", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val error = if (isCurrent) s.error else null
            if (error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { engine.retry() }) { Icon(Icons.Rounded.Refresh, "Erneut versuchen", tint = MaterialTheme.colorScheme.onErrorContainer) }
                    }
                }
            } else if (serverStatus is ServerStatus.Error && !(isCurrent && s.isPlaying)) {
                Text(
                    "Server: ${serverStatus.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SpeedChip(speed, onSpeed)
                IconButton(onClick = { if (isCurrent) engine.previous() else onSeekSegment((segmentIndex - 1).coerceAtLeast(0)) }) {
                    Icon(Icons.Rounded.SkipPrevious, "Voriger Abschnitt")
                }
                IconButton(onClick = { engine.skip(-10_000) }, enabled = isCurrent) { Icon(Icons.Rounded.Replay10, "10 Sekunden zurück") }
                Box(contentAlignment = Alignment.Center) {
                    FilledIconButton(
                        onClick = { if (isCurrent) engine.togglePlay() else onStart() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) {
                        Icon(
                            if (isCurrent && s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (isCurrent && s.isPlaying) "Pause" else "Vorlesen",
                            Modifier.size(36.dp),
                        )
                    }
                    if (isCurrent && s.isBuffering) {
                        CircularProgressIndicator(Modifier.size(76.dp), strokeWidth = 3.dp)
                    }
                }
                IconButton(onClick = { engine.skip(10_000) }, enabled = isCurrent) { Icon(Icons.Rounded.Forward10, "10 Sekunden vor") }
                IconButton(onClick = { if (isCurrent) engine.next() else onSeekSegment((segmentIndex + 1).coerceAtMost(segmentCount - 1)) }) {
                    Icon(Icons.Rounded.SkipNext, "Nächster Abschnitt")
                }
            }
            if (isCurrent && s.isBuffering) {
                var waited by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { while (true) { delay(1000); waited++ } }
                val target = s.bufferTargetMs
                Text(
                    when {
                        target >= 10_000 -> "Puffert für flüssiges Vorlesen … ${s.bufferedMs / 1000} von ${target / 1000} s"
                        waited < 4 -> "Abschnitt wird erzeugt …"
                        else -> "Abschnitt wird erzeugt … ($waited s)"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (target >= 10_000) {
                    LinearProgressIndicator(
                        progress = { (s.bufferedMs.toFloat() / target).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedChip(speed: Float, onSpeed: (Float) -> Unit) {
    val steps = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
    AssistChip(
        onClick = {
            val next = steps.firstOrNull { it > speed + 0.01f } ?: steps.first()
            onSpeed(next)
        },
        label = { Text(String.format(Locale.GERMANY, "%.2f", speed).trimEnd('0').trimEnd(',') + "×") },
    )
}
