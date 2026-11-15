package de.tontaube.vorlesen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import de.tontaube.core.Languages
import de.tontaube.core.Styles
import de.tontaube.core.TtsSettings
import de.tontaube.core.VoiceRef
import de.tontaube.vorlesen.data.VoiceEntity
import de.tontaube.vorlesen.data.VoiceException
import de.tontaube.vorlesen.data.VoiceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(snackbar: SnackbarHostState) {
    val container = LocalContainer.current
    val custom by container.voices.custom.collectAsStateWithLifecycle(emptyList())
    val sample by container.engine.sample.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var sampleRef by remember { mutableStateOf<String?>(null) }

    fun playSample(ref: String, language: String) {
        if (sampleRef == ref && (sample.playing || sample.loading)) {
            container.engine.stopSample(); sampleRef = null
        } else {
            sampleRef = ref
            container.engine.playSample(sampleText(language), TtsSettings(language = language, voice = ref))
        }
    }
    LaunchedEffect(sample.error) { sample.error?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Stimmen") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Neue Stimme") })
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("Eigene Stimmen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
            }
            if (custom.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Eine eigene Stimme anlegen", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Nimm 5 bis 60 Sekunden Sprache auf oder wähle eine Audiodatei. Tontaube übernimmt Klang und Sprechweise " +
                                    "und liest damit deine Projekte vor. Nur Stimmen verwenden, für die du die Erlaubnis hast.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            items(custom, key = { it.id }) { v ->
                CustomVoiceRow(
                    v,
                    sampling = sampleRef == VoiceRef.custom(v.id) && (sample.playing || sample.loading),
                    onSample = { playSample(VoiceRef.custom(v.id), v.language) },
                    onRename = { name, lang -> scope.launch { container.voices.rename(v, name, lang) } },
                    onDelete = {
                        scope.launch {
                            container.voices.delete(v)
                            container.projects.resetVoice(VoiceRef.custom(v.id))
                            snackbar.showSnackbar("Stimme „${v.name}“ gelöscht. Projekte damit nutzen wieder die Standardstimme.")
                        }
                    },
                )
            }
            item {
                Text("Mitgelieferte Stimmen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp))
            }
            items(container.voices.bundled, key = { it.path }) { v ->
                val ref = VoiceRef.server(v.path)
                val active = sampleRef == ref && (sample.playing || sample.loading)
                ListItem(
                    headlineContent = { Text(v.name) },
                    supportingContent = {
                        Text(listOfNotNull(Languages.label(v.language), Styles.label(v.style).takeIf { v.style.isNotEmpty() }).joinToString(" · "))
                    },
                    leadingContent = { Icon(Icons.Rounded.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        IconButton(onClick = { playSample(ref, v.language.ifEmpty { "german" }) }) {
                            Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, "Hörprobe")
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        CreateVoiceDialog(
            onDismiss = { creating = false },
            onCreated = { name ->
                creating = false
                scope.launch { snackbar.showSnackbar("Stimme „$name“ angelegt. Du kannst sie jetzt in jedem Projekt wählen.") }
            },
        )
    }
}

@Composable
private fun CustomVoiceRow(
    v: VoiceEntity,
    sampling: Boolean,
    onSample: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        ListItem(
            headlineContent = { Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("${Languages.label(v.language)} · Referenz ${formatDuration(v.durationMs)}") },
            leadingContent = {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            },
            trailingContent = {
                Row {
                    IconButton(onClick = onSample) { Icon(if (sampling) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, "Hörprobe") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mehr") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Bearbeiten") }, { menu = false; editing = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text("Löschen") }, { menu = false; confirm = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                        }
                    }
                }
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
    if (editing) {
        var name by remember { mutableStateOf(v.name) }
        var lang by remember { mutableStateOf(v.language) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Stimme bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    Dropdown("Sprache der Aufnahme", Languages.all, lang) { lang = it }
                }
            },
            confirmButton = { TextButton(onClick = { onRename(name, lang); editing = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Abbrechen") } },
        )
    }
    if (confirm) {
        ConfirmDialog("Stimme löschen?", "„${v.name}“ und das Referenzaudio werden gelöscht.", "Löschen", onDelete) { confirm = false }
    }
}

private val readAloudTexts = mapOf(
    "german" to "Der Nordwind und die Sonne stritten sich einst, wer von ihnen der Stärkere wäre, als ein Wanderer, " +
        "der in einen warmen Mantel gehüllt war, des Weges kam. Sie wurden einig, dass derjenige für den Stärkeren gelten sollte, " +
        "der den Wanderer zwingen würde, seinen Mantel abzunehmen.",
    "english" to "The North Wind and the Sun were disputing which was the stronger, when a traveler came along wrapped " +
        "in a warm cloak. They agreed that the one who first succeeded in making the traveler take his cloak off should be " +
        "considered stronger than the other.",
)

@UnstableApi
@Composable
private fun CreateVoiceDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("german") }
    var recorded by remember { mutableStateOf<File?>(null) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopRecording() {
        recorder.value?.let { r ->
            runCatching { r.stop() }.onFailure { error = "Aufnahme fehlgeschlagen."; recorded?.delete(); recorded = null }
            r.release()
        }
        recorder.value = null
        recording = false
        if (elapsed < VoiceRepository.MIN_MS && recorded != null) error = "Bitte mindestens 5 Sekunden aufnehmen."
    }

    fun startRecording() {
        error = null
        picked = null
        val file = container.voices.newRecordingFile()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setMaxDuration(VoiceRepository.MAX_MS.toInt())
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording() }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            error = "Mikrofon nicht verfügbar: ${e.message}"
            return
        }
        recorded?.delete()
        recorded = file
        recorder.value = r
        recording = true
        elapsed = 0
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "Ohne Mikrofon-Berechtigung ist keine Aufnahme möglich. Du kannst stattdessen eine Datei wählen."
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = uri
            recorded?.delete(); recorded = null
            pickedName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            error = null
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(100)
            elapsed += 100
            level = ((recorder.value?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.value?.let { runCatching { it.stop() }; it.release() }
            previewPlayer?.release()
        }
    }

    Dialog(onDismissRequest = { if (!recording && !saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Neue Stimme", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dropdown("Sprache der Aufnahme", Languages.all, language) { language = it }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Aufnehmen", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Ruhige Umgebung, eine Person, natürlich und deutlich sprechen. 10 bis 30 Sekunden sind ideal. Zum Beispiel:",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "„${readAloudTexts[language] ?: readAloudTexts.getValue("english")}“",
                            style = ReadingStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(
                                onClick = {
                                    if (recording) stopRecording()
                                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier.size(56.dp),
                                colors = if (recording) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error) else IconButtonDefaults.filledIconButtonColors(),
                            ) { Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (recording) "Stopp" else "Aufnehmen") }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        recording -> "Aufnahme läuft · ${formatDuration(elapsed)} / 1:00"
                                        recorded != null -> "Aufnahme: ${formatDuration(elapsed)}"
                                        else -> "Noch keine Aufnahme"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (recording) level else elapsed / VoiceRepository.MAX_MS.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                )
                            }
                        }
                        recorded?.takeIf { !recording }?.let { file ->
                            OutlinedButton(onClick = {
                                previewPlayer?.release()
                                previewPlayer = MediaPlayer().apply {
                                    setDataSource(file.absolutePath); prepare(); start()
                                }
                            }) {
                                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Anhören")
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }, enabled = !recording) {
                        Icon(Icons.Rounded.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Audiodatei wählen")
                    }
                    Spacer(Modifier.width(12.dp))
                    pickedName?.takeIf { picked != null }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !recording && !saving) { Text("Abbrechen") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !recording && !saving && name.isNotBlank() && (picked != null || (recorded != null && elapsed >= VoiceRepository.MIN_MS)),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val uri = picked
                                    if (uri != null) container.voices.addFromUri(name, language, uri)
                                    else container.voices.addFile(name, language, recorded!!)
                                    onCreated(name)
                                } catch (e: VoiceException) {
                                    error = e.message
                                } catch (e: Exception) {
                                    error = "Speichern fehlgeschlagen: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) { Text(if (saving) "Speichere …" else "Stimme speichern") }
                }
            }
        }
    }
}
