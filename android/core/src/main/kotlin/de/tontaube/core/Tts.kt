package de.tontaube.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

val TontaubeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Sprachen laut Modellkarte (Wert = Feld `language` der API). */
object Languages {
    val all: List<Pair<String, String>> = listOf(
        "Deutsch" to "german",
        "Englisch" to "english",
        "Spanisch" to "spanish",
        "Französisch" to "french",
        "Italienisch" to "italian",
        "Niederländisch" to "dutch",
        "Portugiesisch" to "portuguese",
    )
    private val byIso = mapOf(
        "de" to "german", "en" to "english", "es" to "spanish", "fr" to "french", "it" to "italian", "nl" to "dutch",
        "pt" to "portuguese",
    )

    fun label(code: String): String = all.firstOrNull { it.second == code }?.first ?: code
    fun fromIso(iso: String?): String? = iso?.lowercase()?.take(2)?.let { byIso[it] }
}

/** Sprechstile (Feld `tag`). */
object Styles {
    val all: List<Pair<String, String>> = listOf(
        "Hörbuch" to "audiobook",
        "Konversation" to "conversational",
        "Agentisch (Assistent)" to "agentic",
    )

    fun label(code: String): String = all.firstOrNull { it.second == code }?.first ?: code
}

/**
 * Verweis auf eine Stimme:
 * * `default` – Standardstimme des Servers
 * * `server:samples/Juri.mp3` – mitgelieferte Stimme (voice_path)
 * * `custom:<id>` – eigene Stimme mit Referenzaudio auf dem Handy
 */
object VoiceRef {
    const val DEFAULT = "default"
    fun server(path: String) = "server:$path"
    fun custom(id: Long) = "custom:$id"
    fun serverPath(ref: String): String? = ref.removePrefix("server:").takeIf { ref.startsWith("server:") }
    fun customId(ref: String): Long? = ref.removePrefix("custom:").toLongOrNull().takeIf { ref.startsWith("custom:") }
}

/** Alle Tontaube-Einstellungen eines Vorlese-Projekts. `null` = Server-Standard. */
@Serializable
data class TtsSettings(
    val language: String = "german",
    val tag: String = "audiobook",
    val voice: String = VoiceRef.DEFAULT,
    val temperature: Float? = null,
    val controls: Map<String, Int> = emptyMap(),
    val seed: Int? = null,
    val useSystemTag: Boolean = true,
    val streamingInitialSeconds: Float = 2.8f,
    val bitrate: String? = null,
    val maxNewTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val acousticTemperature: Float? = null,
    val promptMaxTokens: Int? = null,
    /** Nur Wiedergabe (auf dem Handy), beeinflusst die erzeugte Audiodatei nicht. */
    val playbackSpeed: Float = 1.0f,
    /** Modell hinter dem Modell-Gateway (`chatterbox`, `chatterbox-turbo`, `qwen3`); `null` = Tontaube. */
    val model: String? = null,
    /** Chatterbox: Emotion (0,25–2, neutral 0,5). */
    val exaggeration: Float? = null,
    /** Chatterbox: Führung/CFG (0–1). */
    val cfgWeight: Float? = null,
    /** Qwen3-TTS: fertiger Sprecher, z. B. `Ryan`. */
    val speaker: String? = null,
    /** Qwen3-TTS: Stil-Anweisung bzw. Stimmbeschreibung in Worten. */
    val instruct: String? = null,
) {
    /** Gewähltes Modell, `tontaube` wenn keins gesetzt ist. */
    val modelId: String get() = model ?: Models.TONTAUBE
    val isTontaube: Boolean get() = modelId == Models.TONTAUBE

    /** Kennung aller Einstellungen, die das erzeugte Audio verändern (für den Zwischenspeicher). */
    fun audioKey(voiceFingerprint: String = ""): String {
        val relevant = copy(playbackSpeed = 1.0f, streamingInitialSeconds = 0f)
        val raw = TontaubeJson.encodeToString(serializer(), relevant) + "|" + voiceFingerprint
        return sha1(raw).take(12)
    }

    fun encode(): String = TontaubeJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String?): TtsSettings =
            json?.let { runCatching { TontaubeJson.decodeFromString(serializer(), it) }.getOrNull() } ?: TtsSettings()

        /** Erlaubte Werte für die Länge des ersten Streaming-Chunks (Vielfache von 0,4 s). */
        fun quantizeInitialSeconds(v: Float): Float = (Math.round(v / 0.4f).coerceIn(1, 15) * 0.4f).let { Math.round(it * 10) / 10f }
    }
}

fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Body für `POST /stream` bzw. `POST /predict`. */
object TtsRequest {
    fun build(
        text: String,
        settings: TtsSettings,
        voiceAudioB64: String? = null,
        format: String = "mp3",
        streaming: Boolean = true,
    ): String {
        if (!settings.isTontaube) return buildGateway(text, settings, voiceAudioB64, format)
        val body = buildJsonObject {
            put("text", text)
            put("language", settings.language)
            put("tag", settings.tag)
            put("format", format)
            put("use_system_tag", settings.useSystemTag)
            settings.temperature?.let { put("temperature", it.round2()) }
            if (settings.controls.isNotEmpty()) {
                putJsonObject("controls") { settings.controls.toSortedMap().forEach { (k, v) -> put(k, v) } }
            }
            settings.seed?.let { put("seed", it) }
            settings.topP?.let { put("top_p", it.round2()) }
            settings.topK?.let { put("top_k", it) }
            settings.frequencyPenalty?.let { put("frequency_penalty", it.round2()) }
            settings.acousticTemperature?.let { put("acoustic_temperature", it.round2()) }
            settings.promptMaxTokens?.let { put("prompt_max_tokens", it) }
            settings.maxNewTokens?.let { put("max_new_tokens", it) }
            settings.bitrate?.let { put("bitrate", it) }
            if (streaming) {
                put("streaming_initial_seconds", TtsSettings.quantizeInitialSeconds(settings.streamingInitialSeconds))
            } else {
                put("mossformer2_postprocess", false)
            }
            when {
                voiceAudioB64 != null -> put("voice_audio_b64", voiceAudioB64)
                VoiceRef.serverPath(settings.voice) != null -> put("voice_path", VoiceRef.serverPath(settings.voice))
            }
        }
        return body.toString()
    }

    /** Body für Chatterbox/Qwen3 über das Modell-Gateway (nur Felder, die diese Modelle kennen). */
    private fun buildGateway(text: String, settings: TtsSettings, voiceAudioB64: String?, format: String): String {
        val model = settings.modelId
        val body = buildJsonObject {
            put("model", model)
            put("text", if (model == Models.CHATTERBOX_TURBO) text else Tags.strip(text))
            put("language", if (model == Models.CHATTERBOX_TURBO) "english" else settings.language)
            put("format", format)
            settings.temperature?.let { put("temperature", it.round2()) }
            settings.seed?.let { put("seed", it) }
            settings.bitrate?.let { put("bitrate", it) }
            if (model.startsWith("chatterbox")) {
                settings.exaggeration?.let { put("exaggeration", it.round2()) }
                settings.cfgWeight?.let { put("cfg_weight", it.round2()) }
            }
            if (model == Models.QWEN3) {
                settings.speaker?.takeIf { it.isNotBlank() }?.let { put("speaker", it) }
                settings.instruct?.trim()?.takeIf { it.isNotEmpty() }?.let { put("instruct", it) }
            }
            when {
                voiceAudioB64 != null -> put("voice_audio_b64", voiceAudioB64)
                VoiceRef.serverPath(settings.voice) != null -> put("voice_path", VoiceRef.serverPath(settings.voice))
            }
        }
        return body.toString()
    }

    private fun Float.round2(): Double = Math.round(this * 100.0) / 100.0
}

/** Bekannte Modelle. Was ein Server wirklich anbietet, steht in `GET /models` (siehe [ModelSpec]). */
object Models {
    const val TONTAUBE = "tontaube"
    const val CHATTERBOX = "chatterbox"
    const val CHATTERBOX_TURBO = "chatterbox-turbo"
    const val QWEN3 = "qwen3"
}

/** Paralinguistische Tags von Chatterbox Turbo, z. B. `[laugh]`. */
object Tags {
    val turbo: List<String> = listOf(
        "[laugh]", "[chuckle]", "[sigh]", "[gasp]", "[cough]", "[clear throat]", "[sniff]", "[groan]", "[shush]",
    )
    private val regex = Regex(
        "\\[(?:" + turbo.joinToString("|") { Regex.escape(it.removeSurrounding("[", "]")) } + ")\\]",
        RegexOption.IGNORE_CASE,
    )

    /** Entfernt die Tags, damit Modelle ohne Tag-Unterstützung sie nicht vorlesen. */
    fun strip(text: String): String = regex.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    fun contains(text: String): Boolean = regex.containsMatchIn(text)
}

/** Ein Regler eines Gateway-Modells (aus `GET /models`). */
@Serializable
data class ModelParam(
    val name: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float = 0.05f,
    val default: Float,
    val description: String = "",
)

/** Ein Sprecher von Qwen3-TTS. */
@Serializable
data class ModelSpeaker(val id: String, val label: String = id)

/** Ein Modell, das der Server anbietet (`GET /models`, nur mit Modell-Gateway). */
@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val description: String = "",
    val languages: List<String> = emptyList(),
    @SerialName("voice_cloning") val voiceCloning: Boolean = true,
    val speakers: List<ModelSpeaker> = emptyList(),
    val instruct: Boolean = false,
    val tags: List<String> = emptyList(),
    val params: List<ModelParam> = emptyList(),
    val controls: Boolean = false,
    val license: String = "",
) {
    val isTontaube: Boolean get() = id == Models.TONTAUBE

    companion object {
        val tontaube = ModelSpec(
            Models.TONTAUBE, "Tontaube V1.1", controls = true,
            languages = Languages.all.map { it.second },
        )

        /** Liest `GET /models`. Unbekannte Felder werden ignoriert, kaputte Einträge übersprungen. */
        fun parse(json: String): List<ModelSpec> {
            val root = runCatching { TontaubeJson.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
            val list = ((root as? JsonObject)?.get("models") ?: root) as? JsonArray ?: return emptyList()
            return list.mapNotNull { el -> runCatching { TontaubeJson.decodeFromJsonElement(serializer(), el) }.getOrNull() }
        }
    }
}

/** Ein Stil-Regler des Modells (aus `GET /controls`). Stufen 0 … bins-1. */
@Serializable
data class ControlSpec(
    val name: String,
    val bins: Int,
    val label: String,
    val description: String = "",
    val low: String = "",
    val high: String = "",
    val default: Int? = null,
) {
    val max: Int get() = bins - 1
    val neutral: Int get() = default ?: (max / 2)

    companion object {
        private val germanLabels = mapOf(
            "rate" to Triple("Tempo", "deutlich langsamer", "deutlich schneller"),
            "pause" to Triple("Pausen", "weniger, kürzer", "mehr, länger"),
            "pitch_range" to Triple("Ausdruck", "monoton", "sehr lebendig"),
            "pitch_level" to Triple("Stimmlage", "tief", "hoch"),
            "legato" to Triple("Artikulation", "knackig", "normal"),
        )

        /** Werte laut Modellkarte, falls der Server nicht erreichbar ist. */
        val defaults: List<ControlSpec> = listOf(
            ControlSpec("rate", 5, "Tempo", low = "deutlich langsamer", high = "deutlich schneller"),
            ControlSpec("pause", 5, "Pausen", low = "weniger, kürzer", high = "mehr, länger"),
            ControlSpec("pitch_range", 6, "Ausdruck", low = "monoton", high = "sehr lebendig"),
            ControlSpec("pitch_level", 5, "Stimmlage", low = "tief", high = "hoch"),
            ControlSpec("legato", 3, "Artikulation", low = "knackig", high = "normal", default = 2),
        )

        fun parse(json: String): List<ControlSpec> {
            val root = TontaubeJson.parseToJsonElement(json)
            val list: JsonElement = (root as? JsonObject)?.get("controls") ?: root
            val items: List<Pair<String, JsonObject>> = when (list) {
                is JsonArray -> list.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    name to o
                }
                is JsonObject -> list.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                else -> emptyList()
            }
            return items.mapNotNull { (name, o) ->
                fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull
                fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val bins = int("bins") ?: int("max")?.plus(1) ?: return@mapNotNull null
                if (bins < 2) return@mapNotNull null
                val de = germanLabels[name]
                ControlSpec(
                    name = name,
                    bins = bins,
                    label = de?.first ?: str("description").ifEmpty { name },
                    description = str("description"),
                    low = de?.second ?: str("low"),
                    high = de?.third ?: str("high"),
                    default = int("default")?.takeIf { it in 0 until bins },
                )
            }
        }
    }
}

/** Mitgelieferte Stimme des Servers (aus `voices/manifest.json` von Tontaube v1.1.0). */
@Serializable
data class BundledVoice(val path: String, val name: String, val language: String, val style: String)

object BundledVoices {
    fun parse(json: String): List<BundledVoice> {
        val voices = TontaubeJson.parseToJsonElement(json).jsonObject["voices"]?.jsonObject ?: return emptyList()
        return voices.entries.map { (file, meta) ->
            val m = meta as? JsonObject
            val iso = (m?.get("language") as? JsonPrimitive)?.contentOrNull
            BundledVoice(
                path = "samples/$file",
                name = file.substringBeforeLast('.'),
                language = Languages.fromIso(iso) ?: iso.orEmpty(),
                style = (m?.get("style") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }.sortedWith(compareBy({ it.language != "german" }, { it.language }, { it.name }))
    }
}

/** Fehlerantwort des Servers lesbar machen. */
object ServerErrors {
    fun describe(code: Int, body: String?): String {
        val detail = body?.let {
            runCatching {
                val o = TontaubeJson.parseToJsonElement(it).jsonObject
                (o["error"] ?: o["detail"])?.let { d ->
                    if (d is JsonPrimitive) d.content else d.toString()
                }
            }.getOrNull() ?: it.take(300)
        }.orEmpty()
        val hint = when (code) {
            401, 403 -> "API-Key fehlt oder ist falsch."
            404 -> "Endpunkt nicht gefunden (Streaming deaktiviert oder falsche Adresse?)."
            413 -> "Anfrage zu groß (Referenzaudio zu lang?)."
            422 -> "Der Server hat die Anfrage abgelehnt."
            503 -> "Der Server ist noch nicht bereit (Modelle werden geladen)."
            else -> "Serverfehler."
        }
        return if (detail.isNotBlank() && detail != "unauthorized") "$hint ($detail)" else hint
    }
}
