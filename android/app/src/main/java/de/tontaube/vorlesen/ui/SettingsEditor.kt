package de.tontaube.vorlesen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import de.tontaube.core.ControlSpec
import de.tontaube.core.Languages
import de.tontaube.core.ModelSpec
import de.tontaube.core.Models
import de.tontaube.core.Styles
import de.tontaube.core.TtsSettings
import de.tontaube.core.VoiceRef
import de.tontaube.vorlesen.data.VoiceEntity
import java.util.Locale
import kotlin.math.roundToInt

private val sampleTexts = mapOf(
    "german" to "Es war einmal ein kleiner Leuchtturm am Rand der Welt. Jede Nacht schickte er sein Licht über das Meer.",
    "english" to "Once upon a time, a small lighthouse stood at the edge of the world. Every night it sent its light across the sea.",
    "spanish" to "Había una vez un pequeño faro en el borde del mundo. Cada noche enviaba su luz sobre el mar.",
    "french" to "Il était une fois un petit phare au bord du monde. Chaque nuit, il envoyait sa lumière sur la mer.",
    "italian" to "C'era una volta un piccolo faro ai confini del mondo. Ogni notte mandava la sua luce sul mare.",
    "dutch" to "Er was eens een kleine vuurtoren aan de rand van de wereld. Elke nacht stuurde hij zijn licht over de zee.",
    "portuguese" to "Era uma vez um pequeno farol na beira do mundo. Todas as noites ele enviava a sua luz sobre o mar.",
)

fun sampleText(language: String) = sampleTexts[language] ?: sampleTexts.getValue("german")

/**
 * Bottom-Sheet mit allen Tontaube-Einstellungen. Wird für Projekte und für die Standardwerte neuer Projekte genutzt.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    title: String,
    initial: TtsSettings,
    note: String? = null,
    onDismiss: () -> Unit,
    onSave: (TtsSettings) -> Unit,
) {
    val container = LocalContainer.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var s by remember { mutableStateOf(initial) }
    val controls by container.prefs.controls.collectAsStateWithLifecycle(ControlSpec.defaults)
    val models by container.prefs.models.collectAsStateWithLifecycle(listOf(ModelSpec.tontaube))
    val spec = models.firstOrNull { it.id == s.modelId }
        ?: if (s.isTontaube) ModelSpec.tontaube else ModelSpec(s.modelId, s.modelId)
    val customVoices by container.voices.custom.collectAsStateWithLifecycle(emptyList())
    val sample by container.engine.sample.collectAsStateWithLifecycle()
    val changesAudio = initial.copy(playbackSpeed = 1f) != s.copy(playbackSpeed = 1f)

    ModalBottomSheet(onDismissRequest = { container.engine.stopSample(); onDismiss() }, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (models.size > 1 || !s.isTontaube) {
                val options = (models.map { it.name to it.id } +
                    listOfNotNull(("${s.modelId} (auf diesem Server nicht verfügbar)" to s.modelId).takeIf { models.none { m -> m.id == s.modelId } }))
                Dropdown("Modell", options, s.modelId) { id -> s = withModel(s, models.firstOrNull { it.id == id }) }
                spec.description.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            VoicePicker(s.voice, s.language, customVoices) { s = s.copy(voice = it) }

            val langOptions = Languages.all.filter { spec.languages.isEmpty() || it.second in spec.languages }
            if (s.modelId == Models.CHATTERBOX_TURBO) {
                TagsInfo(spec.tags)
            } else {
                Dropdown("Sprache", langOptions.ifEmpty { Languages.all }, s.language) { s = s.copy(language = it) }
            }

            if (s.isTontaube) Column {
                Text("Sprechstil", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Styles.all.forEachIndexed { i, (label, code) ->
                        SegmentedButton(
                            selected = s.tag == code,
                            onClick = { s = s.copy(tag = code) },
                            shape = SegmentedButtonDefaults.itemShape(i, Styles.all.size),
                        ) { Text(label.substringBefore(" ("), maxLines = 1) }
                    }
                }
            }

            val tempDefault = spec.params.firstOrNull { it.name == "temperature" }?.default ?: 0.7f
            OptionalSlider(
                label = "Temperatur",
                hint = "niedriger = textgetreuer, höher = lebendiger (Standard ${String.format(Locale.GERMANY, "%.1f", tempDefault)})",
                value = s.temperature, default = tempDefault, range = 0.1f..1.5f, steps = 13,
                format = { String.format(Locale.GERMANY, "%.1f", it) },
            ) { s = s.copy(temperature = it) }

            if (s.isTontaube) {
                HorizontalDivider()
                Text("Stil-Regler", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nicht aktivierte Regler überlässt das Modell sich selbst.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                controls.forEach { c ->
                    ControlRow(c, s.controls[c.name]) { v ->
                        s = s.copy(controls = if (v == null) s.controls - c.name else s.controls + (c.name to v))
                    }
                }
            }
            if (s.modelId == Models.CHATTERBOX) {
                OptionalSlider(
                    label = "Emotion",
                    hint = "0,5 = neutral, höher = ausdrucksstärker und etwas schneller",
                    value = s.exaggeration, default = 0.5f, range = 0.2f..2.0f, steps = 17,
                    format = { String.format(Locale.GERMANY, "%.1f", it) },
                ) { s = s.copy(exaggeration = it) }
                OptionalSlider(
                    label = "Führung (CFG)",
                    hint = "niedriger = ruhiger und langsamer; bei hoher Emotion etwa 0,3",
                    value = s.cfgWeight, default = 0.5f, range = 0f..1f, steps = 9,
                    format = { String.format(Locale.GERMANY, "%.1f", it) },
                ) { s = s.copy(cfgWeight = it) }
            }
            if (s.modelId == Models.QWEN3) QwenOptions(spec, s) { s = it }

            HorizontalDivider()
            Column {
                Text("Wiedergabe-Tempo: ${String.format(Locale.GERMANY, "%.2f", s.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = s.playbackSpeed, onValueChange = { s = s.copy(playbackSpeed = (it * 20).roundToInt() / 20f) },
                    valueRange = 0.5f..2.0f, steps = 29,
                )
                Text(
                    "Wird auf dem Handy angewendet, ohne das Audio neu zu erzeugen. Für eine natürlichere Sprechgeschwindigkeit den Regler „Tempo“ nutzen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Advanced(s) { s = it }
            if (!s.isTontaube) {
                Text(
                    "${spec.name}${spec.license.takeIf { it.isNotEmpty() }?.let { " · Lizenz $it" }.orEmpty()}. " +
                        "Läuft auf dem Mac über das Modell-Gateway der Desktop-App. Das erste Vorlesen lädt das Modell und kann einige Minuten dauern.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (changesAudio && note != null) {
                Text(
                    "Geänderte Einstellungen gelten ab dem nächsten Abschnitt. Bereits erzeugtes Audio dieses Projekts wird verworfen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                )
            }
            sample.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    if (sample.playing || sample.loading) container.engine.stopSample()
                    else container.engine.playSample(sampleText(s.language), s)
                }) {
                    Icon(if (sample.playing || sample.loading) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (sample.loading) "Wird erzeugt …" else if (sample.playing) "Stopp" else "Hörprobe")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { container.engine.stopSample(); onDismiss() }) { Text("Abbrechen") }
                Button(onClick = { container.engine.stopSample(); onSave(s) }) { Text("Speichern") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@UnstableApi
@Composable
private fun VoicePicker(ref: String, language: String, custom: List<VoiceEntity>, onPick: (String) -> Unit) {
    val container = LocalContainer.current
    var open by remember { mutableStateOf(false) }
    val bundled = container.voices.bundled
    val label = when {
        ref == VoiceRef.DEFAULT -> "Standardstimme des Servers"
        VoiceRef.customId(ref) != null -> custom.firstOrNull { it.id == VoiceRef.customId(ref) }?.let { "${it.name} (eigene Stimme)" } ?: "Gelöschte Stimme"
        else -> bundled.firstOrNull { it.path == VoiceRef.serverPath(ref) }?.let { "${it.name} (${Languages.label(it.language)})" } ?: ref
    }
    OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            overlineContent = { Text("Stimme") },
            leadingContent = { Icon(Icons.Rounded.RecordVoiceOver, null) },
            trailingContent = { Icon(Icons.Rounded.ExpandMore, null) },
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Stimme wählen") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item {
                        VoiceOption("Standardstimme des Servers", null, ref == VoiceRef.DEFAULT) { onPick(VoiceRef.DEFAULT); open = false }
                    }
                    if (custom.isNotEmpty()) {
                        item { SectionLabel("Eigene Stimmen") }
                        items(custom, key = { "c${it.id}" }) { v ->
                            VoiceOption(v.name, Languages.label(v.language), ref == VoiceRef.custom(v.id), Icons.Rounded.Person) {
                                onPick(VoiceRef.custom(v.id)); open = false
                            }
                        }
                    }
                    item { SectionLabel("Mitgelieferte Stimmen") }
                    val sorted = bundled.sortedWith(compareBy({ it.language != language }, { it.language }, { it.name }))
                    items(sorted, key = { "b${it.path}" }) { v ->
                        val sub = listOfNotNull(Languages.label(v.language), Styles.label(v.style).takeIf { v.style.isNotEmpty() }).joinToString(" · ")
                        VoiceOption(v.name, sub, ref == VoiceRef.server(v.path)) { onPick(VoiceRef.server(v.path)); open = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Schließen") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun VoiceOption(title: String, subtitle: String?, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.second == value }?.first ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, code) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(code); expanded = false })
            }
        }
    }
}

@Composable
private fun OptionalSlider(
    label: String,
    hint: String,
    value: Float?,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float?) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (value != null) "$label: ${format(value)}" else "$label: Server-Standard", style = MaterialTheme.typography.labelLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = value != null, onCheckedChange = { onChange(if (it) default else null) })
        }
        AnimatedVisibility(value != null) {
            Slider(
                value = value ?: default,
                onValueChange = { onChange((it * 10).roundToInt() / 10f) },
                valueRange = range, steps = steps,
            )
        }
    }
}

@Composable
private fun ControlRow(spec: ControlSpec, value: Int?, onChange: (Int?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (value != null) "${spec.label}: Stufe $value von ${spec.max}" else "${spec.label}: automatisch",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (spec.low.isNotEmpty()) {
                    Text("0 = ${spec.low} · ${spec.max} = ${spec.high}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = value != null, onCheckedChange = { onChange(if (it) spec.neutral else null) })
        }
        AnimatedVisibility(value != null) {
            Slider(
                value = (value ?: spec.neutral).toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(0, spec.max)) },
                valueRange = 0f..spec.max.toFloat(),
                steps = (spec.max - 1).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun Advanced(s: TtsSettings, onChange: (TtsSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Erweitert", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                var seedText by remember { mutableStateOf(s.seed?.toString().orEmpty()) }
                OutlinedTextField(
                    seedText,
                    { t ->
                        seedText = t.filter { it.isDigit() }.take(9)
                        onChange(s.copy(seed = seedText.toIntOrNull()))
                    },
                    label = { Text("Seed (leer = zufällig)") },
                    supportingText = { Text("Gleicher Seed = gleiche Betonung bei erneutem Erzeugen.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text(
                        "Puffer vor dem ersten Ton: ${String.format(Locale.GERMANY, "%.1f", s.streamingInitialSeconds)} s",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = s.streamingInitialSeconds,
                        onValueChange = { onChange(s.copy(streamingInitialSeconds = TtsSettings.quantizeInitialSeconds(it))) },
                        valueRange = 0.4f..6.0f, steps = 13,
                    )
                    Text(
                        "Größer = späterer Start, aber weniger Aussetzer bei langsamem Server.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Dropdown(
                    "MP3-Bitrate",
                    listOf("Server-Standard" to "", "32 kbit/s" to "32k", "64 kbit/s" to "64k", "96 kbit/s" to "96k", "128 kbit/s" to "128k"),
                    s.bitrate.orEmpty(),
                ) { onChange(s.copy(bitrate = it.ifEmpty { null })) }
                if (s.isTontaube) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("System-Tag verwenden", style = MaterialTheme.typography.labelLarge)
                        Text("Sprache und Stil als Anweisung an das Modell (empfohlen).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.useSystemTag, onCheckedChange = { onChange(s.copy(useSystemTag = it)) })
                }
            }
        }
    }
}

/** Modell wechseln: Sprache an das Modell anpassen, modellfremde Einstellungen bleiben (werden nicht gesendet). */
internal fun withModel(s: TtsSettings, model: ModelSpec?): TtsSettings {
    val id = model?.id ?: Models.TONTAUBE
    val langs = model?.languages.orEmpty()
    val language = when {
        id == Models.CHATTERBOX_TURBO -> "english"
        langs.isNotEmpty() && s.language !in langs -> if ("german" in langs) "german" else langs.first()
        else -> s.language
    }
    return s.copy(model = id.takeIf { it != Models.TONTAUBE }, language = language)
}

@Composable
private fun TagsInfo(tags: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nur Englisch · Tags", style = MaterialTheme.typography.titleSmall)
            Text(
                "Chatterbox Turbo liest englische Texte und versteht Tags direkt im Text, z. B. " +
                    "„That was close [laugh] really close.“ Schreib die Tags in das Dokument, bevor du es importierst. " +
                    "Bei anderen Modellen werden diese Tags automatisch weggelassen.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tags.ifEmpty { de.tontaube.core.Tags.turbo }.joinToString("  "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QwenOptions(spec: ModelSpec, s: TtsSettings, onChange: (TtsSettings) -> Unit) {
    val speakers = listOf("Automatisch" to "") + spec.speakers.map { it.label to it.id }
    Dropdown("Sprecher", speakers, s.speaker.orEmpty()) { onChange(s.copy(speaker = it.ifEmpty { null })) }
    Text(
        "Mit eigener oder mitgelieferter Stimme wird diese geklont (der Sprecher gilt dann nicht). Ohne Stimme und " +
            "ohne Sprecher entsteht die Stimme aus der Beschreibung unten. Keiner der fertigen Sprecher ist deutscher Muttersprachler.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var text by remember { mutableStateOf(s.instruct.orEmpty()) }
    OutlinedTextField(
        text,
        { t -> text = t.take(300); onChange(s.copy(instruct = text.ifBlank { null })) },
        label = { Text(if (s.speaker != null) "Stil-Anweisung" else "Stil-Anweisung bzw. Stimmbeschreibung") },
        placeholder = { Text("z. B. „Ruhig und warm, wie ein Hörbuchsprecher“") },
        supportingText = { Text("Gilt für den ganzen Abschnitt, nicht für einzelne Stellen im Satz.") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}
