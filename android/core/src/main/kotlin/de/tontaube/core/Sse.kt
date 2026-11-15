package de.tontaube.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** Ereignisse aus dem SSE-Stream von `POST /stream`. */
sealed interface StreamEvent {
    class Audio(val bytes: ByteArray) : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
}

/**
 * Zeilenweiser Parser für Server-Sent Events. Der Tontaube-Server schickt
 * `data: {"index": n, "audio_b64": "…", "done": false}` und zum Schluss `data: {"done": true}`.
 */
class SseAudioParser {
    private val data = StringBuilder()
    private var event = "message"

    /** Eine Zeile verarbeiten (ohne Zeilenende). Gibt bei Ereignisende ggf. Events zurück. */
    fun feed(line: String): List<StreamEvent> {
        val l = line.trimEnd('\r')
        if (l.isEmpty()) return dispatch()
        if (l.startsWith(":")) return emptyList()
        val name = l.substringBefore(':')
        var value = if (':' in l) l.substringAfter(':') else ""
        if (value.startsWith(' ')) value = value.substring(1)
        when (name) {
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            "event" -> event = value.trim()
        }
        return emptyList()
    }

    /** Am Ende des Streams aufrufen (falls die letzte Leerzeile fehlt). */
    fun finish(): List<StreamEvent> = dispatch()

    private fun dispatch(): List<StreamEvent> {
        if (data.isEmpty()) { event = "message"; return emptyList() }
        val raw = data.toString().trim()
        val type = event
        data.clear(); event = "message"
        if (type in setOf("done", "end", "complete") || raw == "[DONE]") return listOf(StreamEvent.Done)
        val obj = runCatching { TontaubeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (obj == null) {
            if (type == "error") return listOf(StreamEvent.Error(raw))
            return runCatching { listOf<StreamEvent>(StreamEvent.Audio(decode(raw))) }.getOrDefault(emptyList())
        }
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        if (type == "error" || error != null) return listOf(StreamEvent.Error(error ?: raw))
        val out = mutableListOf<StreamEvent>()
        val audio = listOf("audio_b64", "audio", "chunk").firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull }
        if (!audio.isNullOrEmpty()) out += StreamEvent.Audio(decode(audio))
        if ((obj["done"] as? JsonPrimitive)?.booleanOrNull == true) out += StreamEvent.Done
        return out
    }

    private fun decode(s: String): ByteArray = Base64.getMimeDecoder().decode(s.substringAfter("base64,"))
}
