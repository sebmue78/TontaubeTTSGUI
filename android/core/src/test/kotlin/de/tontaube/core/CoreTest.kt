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

    private val endsSentence = Regex("[.!?…][\"»«“”'’)\\]]*$")

    @Test
    fun paragraphBrokenMidSentenceDoesNotEndSegment() {
        // Wie aus einem PDF: Seitenumbruch mitten im Satz erzeugt zwei „Absätze“
        val a = (1..3).joinToString(" ") { "Das ist der vollständige Satz Nummer $it." } + " Und dieser Satz wird vom Seitenumbruch"
        val b = "mitten im Wort getrennt und geht hier weiter. Danach kommt noch ein Satz."
        val doc = ParsedDocument("T", null, listOf(Chapter(null, listOf(a, b))), DocumentFormat.PDF)
        val out = seg.segment(doc).segments.map { it.text }
        assertTrue(out.toString(), out.all { endsSentence.containsMatchIn(it) })
        assertTrue(out.toString(), out.any { "Seitenumbruch mitten im Wort" in it })
    }

    @Test
    fun everySegmentEndsAtSentenceEnd() {
        val paragraphs = listOf(
            "Überschrift ohne Punkt",
            (1..9).joinToString(" ") { "Ein mittellanger Satz mit der Nummer $it, damit es etwas Text gibt." },
            "Eine Zeile, die umbrochen wurde",
            "und erst hier endet.",
            "„Kurz!“",
            "Noch ein Absatz mit einem Satz. Und einem zweiten Satz.",
        )
        val out = seg.segment(ParsedDocument("T", null, listOf(Chapter(null, paragraphs)), DocumentFormat.TXT)).segments.map { it.text }
        assertTrue(out.toString(), out.all { endsSentence.containsMatchIn(it) })
        assertTrue(out.toString(), out.all { it.length <= 160 })
        assertTrue(out.toString(), out.any { "umbrochen wurde und erst hier endet." in it })
        assertTrue(out.first().startsWith("Überschrift ohne Punkt\n"))
        val joined = out.joinToString(" ").replace(Regex("\\s+"), " ")
        assertEquals(paragraphs.joinToString(" ").replace(Regex("\\s+"), " "), joined)
    }

    @Test
    fun overlongSentenceInDocumentIsCutAtPunctuation() {
        val sentence = (1..30).joinToString(", ") { "Teil $it des Satzes" } + "."
        val out = seg.segment(ParsedDocument("T", null, listOf(Chapter(null, listOf(sentence))), DocumentFormat.TXT)).segments.map { it.text }
        assertTrue(out.size > 1 && out.all { it.length <= 160 })
        assertTrue(out.toString(), out.dropLast(1).all { it.endsWith(",") })
        assertTrue(out.last().endsWith("."))
    }

    @Test
    fun resegmentsOldProjectsAndMapsPosition() {
        // Alte Aufteilung: Abschnitt endet mitten im Satz
        val old = SegmentedText(
            listOf(
                Segment(0, "Erster Satz. Zweiter Satz, der hier"),
                Segment(0, "weitergeht und endet. Dritter Satz steht hier."),
                Segment(1, "Kapitel zwei beginnt. Es hat genau zwei Sätze."),
            ),
            listOf(ChapterInfo("Eins", 0), ChapterInfo("Zwei", 2)),
        )
        val (fresh, map) = seg.resegment(old)
        assertEquals(Segmenter.VERSION, fresh.version)
        assertTrue(fresh.segments.toString(), fresh.segments.all { endsSentence.containsMatchIn(it.text) })
        assertTrue(fresh.segments.any { "der hier weitergeht" in it.text })
        assertEquals(listOf("Eins", "Zwei"), fresh.chapters.map { it.title })
        assertEquals(3, map.size)
        assertEquals(fresh.chapters[1].firstSegment, map[2])
        assertTrue(map[0] <= map[1] && map[1] < map[2])
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
    fun newModelFieldsKeepOldAudioKeys() {
        // Projekte von vor der Modellauswahl dürfen ihren Audio-Zwischenspeicher nicht verlieren.
        val a = TtsSettings()
        assertEquals(false, a.encode().contains("model"))
        assertNotEquals(a.audioKey(), a.copy(model = Models.QWEN3).audioKey())
        assertEquals(a, TtsSettings.decode("""{"language":"german","tag":"audiobook"}"""))
    }

    @Test
    fun gatewayRequestForQwenAndChatterbox() {
        val q = TtsSettings(model = Models.QWEN3, speaker = "Ryan", instruct = " ruhig ", temperature = 0.8f,
            controls = mapOf("rate" to 3), voice = VoiceRef.server("samples/Juri.mp3"))
        val body = TontaubeJson.parseToJsonElement(TtsRequest.build("Hallo [laugh] Welt.", q)).jsonObject
        assertEquals("qwen3", body["model"]!!.jsonPrimitive.content)
        assertEquals("Hallo Welt.", body["text"]!!.jsonPrimitive.content)
        assertEquals("ruhig", body["instruct"]!!.jsonPrimitive.content)
        assertEquals("samples/Juri.mp3", body["voice_path"]!!.jsonPrimitive.content)
        assertNull(body["controls"]); assertNull(body["tag"]); assertNull(body["exaggeration"])

        val t = TtsSettings(model = Models.CHATTERBOX_TURBO, exaggeration = 0.7f, speaker = "Ryan")
        val tb = TontaubeJson.parseToJsonElement(TtsRequest.build("Oh [laugh] no.", t, "QUJD")).jsonObject
        assertEquals("Oh [laugh] no.", tb["text"]!!.jsonPrimitive.content)
        assertEquals("english", tb["language"]!!.jsonPrimitive.content)
        assertEquals("QUJD", tb["voice_audio_b64"]!!.jsonPrimitive.content)
        assertNull(tb["speaker"])

        val plain = TontaubeJson.parseToJsonElement(TtsRequest.build("Hallo", TtsSettings())).jsonObject
        assertNull(plain["model"])
    }

    @Test
    fun modelsFromServer() {
        val json = """{"models":[{"id":"tontaube","name":"Tontaube V1.1","controls":true},
            {"id":"qwen3","name":"Qwen3-TTS","languages":["german","english"],"instruct":true,
             "speakers":[{"id":"Ryan","label":"Ryan (m)"}],"params":[{"name":"temperature","label":"Temperatur",
             "min":0.05,"max":2.0,"step":0.05,"default":0.9,"description":"x"}],"loaded":false,"extra":1},
            {"name":"kaputt"}],"gateway":1}"""
        val models = ModelSpec.parse(json)
        assertEquals(listOf("tontaube", "qwen3"), models.map { it.id })
        assertEquals("Ryan", models[1].speakers.single().id)
        assertEquals(0.9f, models[1].params.single().default)
        assertTrue(ModelSpec.parse("not json").isEmpty())
        assertEquals("a b", Tags.strip("a [Sigh]  b"))
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

class BufferPolicyTest {
    @Test
    fun manualModeIsFixed() {
        assertEquals(120, BufferPolicy.targetSeconds(120, 0.5, start = true))
        assertEquals(120, BufferPolicy.targetSeconds(120, null, start = false))
    }

    @Test
    fun autoScalesWithServerSpeed() {
        // Unbekannt: schnell starten, nach Stocken etwas mehr puffern
        assertEquals(5, BufferPolicy.targetSeconds(BufferPolicy.AUTO, null, start = true))
        assertEquals(30, BufferPolicy.targetSeconds(BufferPolicy.AUTO, null, start = false))
        // Deutlich schneller als Echtzeit: kaum Puffer nötig
        assertEquals(3, BufferPolicy.targetSeconds(BufferPolicy.AUTO, 2.0, start = true))
        // 0,8× Echtzeit: 300 s × 0,2 = 60 s
        assertEquals(60, BufferPolicy.targetSeconds(BufferPolicy.AUTO, 0.8, start = true))
        // Knapp um Echtzeit: Mindestpuffer
        assertEquals(BufferPolicy.MIN_AUTO_S, BufferPolicy.targetSeconds(BufferPolicy.AUTO, 1.05, start = false))
        // Sehr langsam: gedeckelt
        assertEquals(BufferPolicy.MAX_S, BufferPolicy.targetSeconds(BufferPolicy.AUTO, 0.1, start = true).coerceAtLeast(BufferPolicy.MAX_S))
    }

    @Test
    fun windowCoversTarget() {
        // 450 Zeichen ≈ 32 s Audio; 120 s Puffer + Reserve brauchen 7 Abschnitte
        assertEquals(7, BufferPolicy.windowSegments(120, 450))
        assertEquals(3, BufferPolicy.windowSegments(3, 450))
        assertTrue(BufferPolicy.windowSegments(600, 450) in 20..30)
    }

    @Test
    fun rateEstimatorAverages() {
        val r = RateEstimator(minObservedSeconds = 10.0)
        r.add(8.0, 5.0)
        assertNull(r.rate)
        r.add(8.0, 10.0)
        val v = r.rate!!
        assertTrue("$v", v in 1.0..1.2)
        val s = RateEstimator()
        s.seed(0.8)
        assertEquals(0.8, s.rate!!, 0.001)
    }
}
