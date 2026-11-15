package de.tontaube.core

import kotlinx.serialization.Serializable

/** Ein eingelesenes Dokument, bereits in Kapitel und Absätze gegliedert. */
data class ParsedDocument(
    val title: String,
    val author: String? = null,
    val chapters: List<Chapter>,
    val format: DocumentFormat,
) {
    val paragraphCount: Int get() = chapters.sumOf { it.paragraphs.size }
    val charCount: Int get() = chapters.sumOf { c -> c.paragraphs.sumOf { it.length } }
    val isEmpty: Boolean get() = chapters.all { it.paragraphs.isEmpty() }
}

data class Chapter(val title: String?, val paragraphs: List<String>)

enum class DocumentFormat(val label: String) {
    TXT("Text"),
    MARKDOWN("Markdown"),
    HTML("HTML"),
    EPUB("EPUB"),
    PDF("PDF"),
    DOCX("Word"),
    ODT("OpenDocument"),
    RTF("RTF"),
    FB2("FictionBook");

    companion object {
        /** Erkennt das Format anhand von Dateiname, MIME-Typ und den ersten Bytes. */
        fun detect(fileName: String?, mimeType: String?, head: ByteArray): DocumentFormat? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            byExtension[ext]?.let { return it }
            mimeType?.lowercase()?.let { mime -> byMime[mime]?.let { return it } }
            return sniff(head)
        }

        private val byExtension = mapOf(
            "txt" to TXT, "text" to TXT, "log" to TXT, "csv" to TXT,
            "md" to MARKDOWN, "markdown" to MARKDOWN,
            "html" to HTML, "htm" to HTML, "xhtml" to HTML,
            "epub" to EPUB,
            "pdf" to PDF,
            "docx" to DOCX,
            "odt" to ODT,
            "rtf" to RTF,
            "fb2" to FB2,
        )

        private val byMime = mapOf(
            "text/plain" to TXT,
            "text/markdown" to MARKDOWN,
            "text/x-markdown" to MARKDOWN,
            "text/html" to HTML,
            "application/xhtml+xml" to HTML,
            "application/epub+zip" to EPUB,
            "application/pdf" to PDF,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCX,
            "application/vnd.oasis.opendocument.text" to ODT,
            "application/rtf" to RTF,
            "text/rtf" to RTF,
            "application/x-fictionbook+xml" to FB2,
        )

        /** MIME-Typen für den Dateiauswahl-Dialog. */
        val pickerMimeTypes = arrayOf(
            "text/*",
            "application/pdf",
            "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "application/xhtml+xml",
            "application/x-fictionbook+xml",
            "application/octet-stream",
        )

        private fun sniff(head: ByteArray): DocumentFormat? {
            fun startsWith(s: String) = head.size >= s.length && String(head, 0, s.length, Charsets.ISO_8859_1) == s
            if (startsWith("%PDF")) return PDF
            if (startsWith("{\\rtf")) return RTF
            if (startsWith("PK")) {
                val text = String(head, Charsets.ISO_8859_1)
                return when {
                    "application/epub+zip" in text -> EPUB
                    "application/vnd.oasis.opendocument.text" in text -> ODT
                    "word/" in text || "[Content_Types].xml" in text -> DOCX
                    else -> null
                }
            }
            val text = String(head, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
            if (text.startsWith("<!doctype html") || text.startsWith("<html")) return HTML
            if (text.startsWith("<?xml") && "fictionbook" in text) return FB2
            return if (head.isNotEmpty() && head.count { it == 0.toByte() } == 0) TXT else null
        }
    }
}

class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Abschnitt zum Vorlesen. [chapter] verweist auf [ParsedDocument.chapters]. */
@Serializable
data class Segment(val chapter: Int, val text: String)

@Serializable
data class ChapterInfo(val title: String, val firstSegment: Int)

@Serializable
data class SegmentedText(val segments: List<Segment>, val chapters: List<ChapterInfo>) {
    val totalChars: Int get() = segments.sumOf { it.text.length }

    /** Anteil des Textes vor Abschnitt [index] (für die Fortschrittsanzeige). */
    fun progressAt(index: Int): Float {
        if (segments.isEmpty()) return 0f
        val before = segments.take(index.coerceIn(0, segments.size)).sumOf { it.text.length }
        return before.toFloat() / totalChars.coerceAtLeast(1)
    }

    fun chapterOf(index: Int): ChapterInfo? = chapters.lastOrNull { it.firstSegment <= index }
}
