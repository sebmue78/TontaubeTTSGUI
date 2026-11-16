package de.tontaube.core

/**
 * Teilt ein Dokument in Vorlese-Abschnitte. Ein Abschnitt ist die Einheit für Streaming, Zwischenspeicher
 * und Fortsetzen. Abschnitte enden an Satzenden (bevorzugt am Absatzende); kurze Absätze (Überschriften,
 * Dialogzeilen) werden mit dem folgenden zusammengelegt, lange Absätze an Satzgrenzen geteilt.
 */
class Segmenter(
    private val targetChars: Int = 450,
    private val maxChars: Int = 700,
    private val minChars: Int = 80,
) {
    // Satzende: . ! ? … gefolgt von Leerzeichen und Großbuchstabe/Zahl/Anführungszeichen.
    private val sentenceEnd = Regex("(?<=[.!?…])[\"»«“”'’)]*\\s+(?=[\\p{Lu}\\d\"»«“„‚'(\\[-])")
    private val abbreviations = setOf(
        "z.b", "d.h", "u.a", "bzw", "ca", "dr", "prof", "nr", "st", "vgl", "usw", "etc", "evtl", "ggf", "inkl", "bspw",
        "mr", "mrs", "ms", "jr", "sr", "vs", "e.g", "i.e", "no", "abs", "s", "hl", "jh", "mio", "mrd", "str",
    )

    fun segment(doc: ParsedDocument): SegmentedText {
        val segments = mutableListOf<Segment>()
        val chapters = mutableListOf<ChapterInfo>()
        doc.chapters.forEachIndexed { ci, chapter ->
            val pieces = chapterPieces(chapter.paragraphs)
            if (pieces.isEmpty()) return@forEachIndexed
            chapters += ChapterInfo(chapter.title ?: if (doc.chapters.size > 1) "Teil ${chapters.size + 1}" else doc.title, segments.size)
            pieces.forEach { segments += Segment(ci, it) }
        }
        return SegmentedText(segments, chapters, VERSION)
    }

    /**
     * Text eines Projekts mit älterer Aufteilung neu aufteilen. Liefert den neuen Text und zu jedem alten
     * Abschnitt den Index des neuen Abschnitts, der dessen Anfang enthält (zum Umrechnen der Leseposition).
     */
    fun resegment(old: SegmentedText): Pair<SegmentedText, IntArray> {
        // Alte Abschnitte kapitelweise (in Reihenfolge) gruppieren
        val groups = mutableListOf<MutableList<Int>>()
        old.segments.forEachIndexed { i, seg ->
            if (i == 0 || old.segments[i - 1].chapter != seg.chapter) groups += mutableListOf<Int>()
            groups.last() += i
        }
        val doc = ParsedDocument(
            title = old.chapters.firstOrNull()?.title ?: "",
            author = null,
            chapters = groups.mapIndexed { g, idx ->
                val title = old.chapters.getOrNull(g)?.title?.takeIf { old.chapters.size == groups.size }
                Chapter(title, idx.flatMap { old.segments[it].text.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() })
            },
            format = DocumentFormat.TXT,
        )
        val fresh = segment(doc)
        val mapping = IntArray(old.segments.size)
        var newBase = 0
        groups.forEachIndexed { g, idx ->
            val newIdx = fresh.segments.indices.filter { fresh.segments[it].chapter == g }
            val newStarts = newIdx.runningFold(0) { acc, i -> acc + fresh.segments[i].text.length + 1 }
            var oldStart = 0
            for (i in idx) {
                val k = newStarts.indexOfLast { it <= oldStart }.coerceIn(0, (newIdx.size - 1).coerceAtLeast(0))
                mapping[i] = newIdx.getOrElse(k) { newBase }
                oldStart += old.segments[i].text.length + 1
            }
            if (newIdx.isNotEmpty()) newBase = newIdx.last() + 1
        }
        return fresh to mapping
    }

    companion object {
        /** 2: Abschnitte enden an Satzenden, auch über Absatzgrenzen hinweg. */
        const val VERSION = 2
    }

    /** Wie gut sich die Stelle nach einem Textstück als Abschnittsende eignet. */
    private enum class Boundary { NONE, SOFT, SENTENCE }

    /** Ein Satz (oder Satzteil) mit der Information, ob danach ein Absatz endet. */
    private class Unit(val text: String, val paragraphEnd: Boolean, val boundary: Boundary)

    private val sentenceTerminal = Regex("[.!?…][\"»«“”'’)\\]]*$")

    private fun endsSentence(t: String) = sentenceTerminal.containsMatchIn(t.trimEnd())

    /**
     * Abschnitte enden immer an einem Satzende – auch über Absatzgrenzen hinweg. Ein Absatzende mitten im Satz
     * (z. B. Seiten- oder Spaltenumbruch im PDF, Überschrift) beendet keinen Abschnitt. Nur ein einzelner Satz,
     * der länger als [maxChars] ist, wird an Satzzeichen (Semikolon, Doppelpunkt, Komma, Gedankenstrich) geteilt.
     */
    private fun chapterPieces(paragraphs: List<String>): List<String> {
        val units = mutableListOf<Unit>()
        for (p in paragraphs) {
            val pieces = sentences(p).flatMap { sentence ->
                if (sentence.length <= maxChars) listOf(sentence to Boundary.SENTENCE)
                else splitSentence(sentence).let { parts -> parts.mapIndexed { i, part -> part to if (i == parts.lastIndex) Boundary.SENTENCE else Boundary.SOFT } }
            }
            pieces.forEachIndexed { i, (text, b) ->
                val boundary = if (b == Boundary.SENTENCE && !endsSentence(text)) Boundary.NONE else b
                units += Unit(text, i == pieces.lastIndex, boundary)
            }
        }

        val hardMax = maxChars * 2
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var last: Unit? = null
        fun flush() {
            if (current.isNotEmpty()) out += current.toString()
            current.clear(); last = null
        }
        for (u in units) {
            val prev = last
            if (prev != null) {
                val wouldBe = current.length + 1 + u.text.length
                val overflow = wouldBe > maxChars && prev.boundary != Boundary.NONE
                if (overflow || wouldBe > hardMax) flush()
            }
            val before = last
            if (before != null) current.append(joiner(before, u))
            current.append(u.text)
            last = u
            val len = current.length
            when {
                u.boundary == Boundary.SENTENCE && u.paragraphEnd && len >= minChars -> flush()
                u.boundary == Boundary.SENTENCE && len >= targetChars -> flush()
            }
        }
        flush()
        // Sehr kurzes Kapitelende an den vorigen Abschnitt hängen
        if (out.size >= 2 && out.last().length < minChars / 2 && out[out.size - 2].length + out.last().length < maxChars) {
            val tail = out.removeAt(out.lastIndex)
            out[out.lastIndex] = out.last() + "\n" + tail
        }
        return out
    }

    /** Trenner zwischen zwei Stücken: Leerzeichen im Satz, Zeilenumbruch zwischen echten Absätzen. */
    private fun joiner(prev: Unit, next: Unit): String {
        if (!prev.paragraphEnd) return " "
        // Absatzende mitten im Satz (umbrochener Satz): kleingeschriebene Fortsetzung → als Fließtext verbinden
        val continues = prev.boundary == Boundary.NONE && next.text.firstOrNull()?.isLowerCase() == true
        return if (continues) " " else "\n"
    }

    fun sentences(text: String): List<String> {
        val raw = text.split(sentenceEnd)
        val merged = mutableListOf<String>()
        for (s in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && endsWithAbbreviation(prev, s)) {
                merged[merged.lastIndex] = "$prev $s"
            } else {
                merged += s
            }
        }
        return merged.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val ordinalFollowers = setOf(
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november",
        "dezember", "jahrhundert", "jahrtausend", "kapitel", "auflage", "stock", "klasse", "platz", "liga",
    )

    private fun endsWithAbbreviation(s: String, next: String): Boolean {
        if (!s.trimEnd().endsWith(".")) return false
        val lastWord = s.trimEnd().substringAfterLast(' ').trimEnd('.').lowercase().trimStart('(', '"', '„')
        if (lastWord in abbreviations || (lastWord.length == 1 && lastWord[0].isLetter())) return true
        // Ordnungszahl wie „3. Mai“ oder „19. Jahrhundert“
        val nextWord = next.trimStart().substringBefore(' ').lowercase().trimEnd(',', '.', ';')
        return lastWord.isNotEmpty() && lastWord.length <= 2 && lastWord.all { it.isDigit() } && nextWord in ordinalFollowers
    }

    /** Langen Absatz an Satzgrenzen in Stücke um [targetChars] teilen; überlange Sätze an Kommas/Leerzeichen. */
    fun splitLong(p: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences(p).flatMap { if (it.length > maxChars) splitSentence(it) else listOf(it) }) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > targetChars) {
                out += current.toString(); current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun splitSentence(s: String): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = listOf("; ", ": ", ", ", " – ", " - ").map { window.lastIndexOf(it) }.maxOrNull()
                ?.takeIf { it > targetChars / 3 }
                ?.let { it + 1 }
                ?: window.lastIndexOf(' ').takeIf { it > targetChars / 3 }
                ?: maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
