package de.tontaube.core

/**
 * Teilt ein Dokument in Vorlese-Abschnitte. Ein Abschnitt ist die Einheit für Streaming, Zwischenspeicher
 * und Fortsetzen. Kurze Absätze (Überschriften, Dialogzeilen) werden mit dem folgenden zusammengelegt,
 * lange an Satzgrenzen geteilt.
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
        return SegmentedText(segments, chapters)
    }

    private fun chapterPieces(paragraphs: List<String>): List<String> {
        val out = mutableListOf<String>()
        val pending = StringBuilder()
        fun flushPending() {
            if (pending.isNotEmpty()) { out += pending.toString(); pending.clear() }
        }
        for (p in paragraphs) {
            val parts = if (p.length > maxChars) splitLong(p) else listOf(p)
            for (part in parts) {
                when {
                    pending.isEmpty() -> pending.append(part)
                    pending.length < minChars && pending.length + part.length + 1 <= maxChars -> pending.append('\n').append(part)
                    part.length < minChars && pending.length + part.length + 1 <= targetChars -> pending.append('\n').append(part)
                    else -> { flushPending(); pending.append(part) }
                }
            }
        }
        flushPending()
        // Sehr kurzes Kapitelende an den vorigen Abschnitt hängen
        if (out.size >= 2 && out.last().length < minChars / 2 && out[out.size - 2].length + out.last().length < maxChars) {
            val last = out.removeAt(out.lastIndex)
            out[out.lastIndex] = out.last() + "\n" + last
        }
        return out
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
