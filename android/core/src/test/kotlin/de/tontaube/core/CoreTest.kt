package de.tontaube.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SegmenterTest {
    private val seg = Segmenter(targetChars = 100, maxChars = 160, minChars = 30)

    @Test
    fun mergesShortAndSplitsLong() {
        val long = (1..12).joinToString(" ") { "Das ist Satz Nummer $it." }
        val doc = ParsedDocument("T", null, listOf(Chapter("Kap", listOf("Kap", long, "Ende."))), DocumentFormat.TXT)
        val out = seg.segment(doc)
        assertTrue(out.segments.all { it.text.length <= 160 })
        assertTrue(out.segments.first().text.startsWith("Kap\nDas ist Satz Nummer 1."))
        assertTrue(out.segments.last().text.endsWith("Ende."))
        assertEquals(listOf(ChapterInfo("Kap", 0)), out.chapters)
        // Kein Text geht verloren
        val joined = out.segments.joinToString(" ") { it.text.replace('\n', ' ') }
        assertEquals(("Kap $long Ende.").replace(Regex("\\s+"), " "), joined.replace(Regex("\\s+"), " "))
    }

    @Test
    fun sentencesRespectAbbreviations() {
        val s = Segmenter().sentences("Das ist z.B. ein Test. Dr. Müller kam am 3. Mai. Dann ging er!")
        assertEquals(listOf("Das ist z.B. ein Test.", "Dr. Müller kam am 3. Mai.", "Dann ging er!"), s)
    }

    @Test
    fun overlongSentenceIsCut() {
        val words = (1..80).joinToString(" ") { "wort$it" }
        val parts = seg.splitLong(words)
        assertTrue(parts.size > 1 && parts.all { it.length <= 160 })
    }

    @Test
    fun progress() {
        val st = SegmentedText(listOf(Segment(0, "aaaa"), Segment(0, "bbbb")), listOf(ChapterInfo("x", 0)))
        assertEquals(0.5f, st.progressAt(1), 0.001f)
    }
}

class TtsTest {
    @Test
    fun requestBody() {
        val s = TtsSettings(
            language = "german", tag = "audiobook", voice = VoiceRef.server("samples/Juri.mp3"), temperature = 0.65f,
            controls = mapOf("rate" to 3), seed = 7, streamingInitialSeconds = 2.9f,
        )
        val o = TontaubeJson.parseToJsonElement(TtsRequest.build("Hallo", s)).jsonObject
        assertEquals("Hallo", o["text"]!!.jsonPrimitive.content)
        assertEquals("samples/Juri.mp3", o["voice_path"]!!.jsonPrimitive.content)
        assertEquals(3, o["controls"]!!.jsonObject["rate"]!!.jsonPrimitive.int)
        assertEquals("2.8", o["streaming_initial_seconds"]!!.jsonPrimitive.content)
        assertEquals("0.65", o["temperature"]!!.jsonPrimitive.content)
        assertNull(o["top_p"])
        val custom = TontaubeJson.parseToJsonElement(TtsRequest.build("x", s.copy(voice = "custom:3"), "QUJD")).jsonObject
        assertEquals("QUJD", custom["voice_audio_b64"]!!.jsonPrimitive.content)
        assertNull(custom["voice_path"])
    }

    @Test
    fun audioKeyIgnoresPlaybackSpeed() {
        val a = TtsSettings()
        assertEquals(a.audioKey(), a.copy(playbackSpeed = 1.5f).audioKey())
        assertNotEquals(a.audioKey(), a.copy(temperature = 0.5f).audioKey())
        assertNotEquals(a.audioKey("v1"), a.audioKey("v2"))
        assertEquals(a, TtsSettings.decode(a.encode()))
    }

    @Test
    fun controlsFromServer() {
        val json = """{"controls":[{"name":"rate","bins":5,"description":"speaking rate","low":"slow","high":"fast"},
            {"name":"legato","bins":3,"default":2},{"name":"breath","bins":4,"description":"Atmen","low":"keins","high":"hörbar"}]}"""
        val specs = ControlSpec.parse(json).associateBy { it.name }
        assertEquals("Tempo", specs["rate"]!!.label)
        assertEquals(4, specs["rate"]!!.max)
        assertEquals(2, specs["legato"]!!.neutral)
        assertEquals("Atmen", specs["breath"]!!.label)
    }

    @Test
    fun bundledVoices() {
        val json = """{"voices":{"Juri.mp3":{"language":"de","style":"audiobook"},"Clara.wav":{"language":"en"}}}"""
        val v = BundledVoices.parse(json)
        assertEquals("Juri", v.first().name)
        assertEquals("german", v.first().language)
        assertEquals("samples/Clara.wav", v[1].path)
    }

    @Test
    fun serverErrors() {
        assertEquals("API-Key fehlt oder ist falsch.", ServerErrors.describe(401, """{"error":"unauthorized"}"""))
        assertTrue(ServerErrors.describe(422, """{"error":"text must not be empty"}""").contains("text must not be empty"))
    }
}

class SseTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun parsesTontaubeStream() {
        val p = SseAudioParser()
        val lines = listOf(
            "data: {\"index\": 0, \"audio_b64\": \"${b64("AB")}\", \"done\": false}", "",
            ": kommentar",
            "data: {\"index\": 1, \"audio_b64\": \"${b64("CD")}\", \"done\": false}", "",
            "data: {\"done\": true}", "",
        )
        val events = lines.flatMap { p.feed(it) } + p.finish()
        val audio = events.filterIsInstance<StreamEvent.Audio>().joinToString("") { String(it.bytes) }
        assertEquals("ABCD", audio)
        assertEquals(StreamEvent.Done, events.last())
    }

    @Test
    fun parsesError() {
        val p = SseAudioParser()
        val ev = p.feed("data: {\"error\": \"streaming generation failed\", \"done\": true}") + p.feed("")
        assertEquals(StreamEvent.Error("streaming generation failed"), ev.first())
    }
}

class ConnectLinkTest {
    @Test
    fun parsesDesktopLink() {
        val link = ConnectLink.parse("tontaube://connect?url=http%3A%2F%2F192.168.1.20%3A8080&key=ab-c_d&name=Mein%20Mac")!!
        assertEquals(ConnectLink("http://192.168.1.20:8080", "ab-c_d", "Mein Mac"), link)
    }

    @Test
    fun normalizesUrls() {
        assertEquals("http://192.168.1.5:8080", ConnectLink.normalizeUrl("192.168.1.5"))
        assertEquals("http://mac.local:9000", ConnectLink.normalizeUrl("http://mac.local:9000/"))
        assertEquals("https://tts.example.org", ConnectLink.normalizeUrl("https://tts.example.org"))
        assertNull(ConnectLink.normalizeUrl("ftp://x"))
        assertNull(ConnectLink.parse("hallo welt"))
    }
}
