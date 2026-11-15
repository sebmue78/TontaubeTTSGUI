package de.tontaube.core

/** Hilfsfunktionen, um Rohtext in saubere, vorlesbare Absätze zu verwandeln. */
object TextCleanup {
    private val softHyphen = Regex("­")
    private val zeroWidth = Regex("[​‌‍⁠﻿]")
    private val spaces = Regex("[ \t  -   　]+")
    private val hyphenBreak = Regex("(\\p{Ll})[-‐]\\n(\\p{Ll})")
    private val blankLines = Regex("\\n\\s*\\n+")
    private val pageNumberLine = Regex("^\\s*(-\\s*)?\\d{1,4}(\\s*-)?\\s*$")

    fun normalizeLine(s: String): String =
        s.replace(softHyphen, "").replace(zeroWidth, "").replace(spaces, " ").trim()

    /** Hat der Absatz etwas, das sich vorlesen lässt (Buchstaben oder Ziffern)? */
    fun isSpeakable(s: String): Boolean = s.any { it.isLetterOrDigit() }

    /**
     * Teilt Fließtext an Leerzeilen in Absätze. Einzelne Zeilenumbrüche innerhalb eines Absatzes
     * werden zu Leerzeichen, Silbentrennungen am Zeilenende werden aufgelöst.
     */
    fun paragraphsFromPlainText(raw: String, dropPageNumbers: Boolean = false): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace(softHyphen, "")
            .replace(hyphenBreak, "$1$2")
        return text.split(blankLines)
            .map { block ->
                block.lines()
                    .filterNot { dropPageNumbers && pageNumberLine.matches(it) }
                    .joinToString(" ") { normalizeLine(it) }
                    .let { normalizeLine(it) }
            }
            .filter { isSpeakable(it) }
    }

    /**
     * Für Text ohne Leerzeilen (z. B. PDF-Zeilen): neue Absätze beginnen nach Satzende-Zeichen, wenn die
     * Zeile deutlich kürzer als üblich ist.
     */
    fun paragraphsFromLines(lines: List<String>): List<String> {
        val clean = lines.map { normalizeLine(it.replace(softHyphen, "")) }
        val typical = clean.filter { it.length > 20 }.map { it.length }.sorted().let { if (it.isEmpty()) 60 else it[it.size / 2] }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val p = normalizeLine(current.toString())
            if (isSpeakable(p)) out += p
            current.clear()
        }
        for (line in clean) {
            if (line.isEmpty()) { flush(); continue }
            if (pageNumberLine.matches(line)) continue
            if (current.isNotEmpty()) {
                val last = current.last()
                if ((last == '-' || last == '‐') && current.length > 1 && current[current.length - 2].isLowerCase() &&
                    line.first().isLowerCase()
                ) {
                    current.setLength(current.length - 1)
                } else {
                    current.append(' ')
                }
            }
            current.append(line)
            val endsSentence = line.last() in ".!?:…\"»«“”'"
            if (endsSentence && line.length < typical * 0.75) flush()
        }
        flush()
        return out
    }
}
