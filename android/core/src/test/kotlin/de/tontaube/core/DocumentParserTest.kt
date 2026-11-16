package de.tontaube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {
    private val parser = DocumentParser { bytes -> String(bytes).split("\u000C") }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun plainTextParagraphsAndHyphenation() {
        val text = "Erste Zeile des Ab-\nsatzes geht weiter.\n\n\nZweiter Absatz.\r\n\r\n   \n"
        val doc = parser.parse(text.toByteArray(), "notiz.txt")
        assertEquals("notiz", doc.title)
        assertEquals(listOf("Erste Zeile des Absatzes geht weiter.", "Zweiter Absatz."), doc.chapters.single().paragraphs)
    }

    @Test
    fun plainTextLatin1Fallback() {
        val bytes = "Grüße aus Köln".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Grüße aus Köln", parser.parse(bytes, "a.txt").chapters[0].paragraphs[0])
    }

    @Test
    fun plainTextChapters() {
        val text = "Vorspann.\n\nKapitel 1\n\nEs war einmal.\n\nKapitel 2: Der Wald\n\nIm Wald."
        val doc = parser.parse(text.toByteArray(), "buch.txt")
        assertEquals(listOf(null, "Kapitel 1", "Kapitel 2: Der Wald"), doc.chapters.map { it.title })
    }

    @Test
    fun markdown() {
        val md = "# Titel\n\nEin **fetter** und [verlinkter](http://x) Text.\n\n```\ncode()\n```\n\n## Teil 2\n\n- Punkt eins\n- Punkt zwei\n"
        val doc = parser.parse(md.toByteArray(), "x.md")
        assertEquals("Titel", doc.title)
        assertEquals(listOf("Titel", "Teil 2"), doc.chapters.map { it.title })
        assertEquals(listOf("Titel", "Ein fetter und verlinkter Text."), doc.chapters[0].paragraphs)
        assertEquals(listOf("Teil 2", "Punkt eins", "Punkt zwei"), doc.chapters[1].paragraphs)
    }

    @Test
    fun html() {
        val html = """<html><head><title>Seite</title><style>p{}</style></head><body>
            <h1>Anfang</h1><p>Hallo <b>Welt</b>,<br>wie geht's?</p><script>alert(1)</script>
            <ul><li>Eins</li><li>Zwei</li></ul><h1>Ende</h1><div>Tschüss</div></body></html>"""
        val doc = parser.parse(html.toByteArray(), "seite.html")
        assertEquals("Seite", doc.title)
        assertEquals(listOf("Anfang", "Ende"), doc.chapters.map { it.title })
        assertEquals(listOf("Anfang", "Hallo Welt,", "wie geht's?", "Eins", "Zwei"), doc.chapters[0].paragraphs)
    }

    @Test
    fun epub() {
        val bytes = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns:dc="http://purl.org/dc/elements/1.1/">
                <metadata><dc:title>Das Buch</dc:title><dc:creator>Anna Autorin</dc:creator></metadata>
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="c1" href="text/kap%201.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/kap2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<html><body><nav epub:type="toc"><ol><li><a href="text/kap%201.xhtml">Erstes Kapitel</a></li>
                <li><a href="text/kap2.xhtml#start">Zweites Kapitel</a></li></ol></nav></body></html>""",
            "OEBPS/text/kap 1.xhtml" to "<html><body><h2>Eins</h2><p>Am Anfang war das Wort.</p><span epub:type=\"pagebreak\">12</span></body></html>",
            "OEBPS/text/kap2.xhtml" to "<html><body><p>Und dann kam der Rest.</p></body></html>",
        )
        val doc = parser.parse(bytes, "buch.epub")
        assertEquals("Das Buch", doc.title)
        assertEquals("Anna Autorin", doc.author)
        assertEquals(listOf("Erstes Kapitel", "Zweites Kapitel"), doc.chapters.map { it.title })
        assertEquals(listOf("Eins", "Am Anfang war das Wort."), doc.chapters[0].paragraphs)
    }

    @Test
    fun epubWithDrmIsRejected() {
        val bytes = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""",
            "META-INF/encryption.xml" to """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""",
            "c.opf" to "<package/>",
        )
        try {
            parser.parse(bytes, "drm.epub"); fail()
        } catch (e: DocumentException) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test
    fun docx() {
        val bytes = zip(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Kapitel A</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">Hallo </w:t></w:r><w:r><w:t>Welt</w:t><w:tab/><w:t>Tab</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="berschrift1"/></w:pPr><w:r><w:t>Kapitel B</w:t></w:r></w:p>
                <w:p><w:r><w:t>Text B</w:t></w:r></w:p></w:body></w:document>""",
            "docProps/core.xml" to """<cp:coreProperties xmlns:dc="dc"><dc:title>Bericht</dc:title></cp:coreProperties>""",
        )
        val doc = parser.parse(bytes, "bericht.docx")
        assertEquals("Bericht", doc.title)
        assertEquals(listOf("Kapitel A", "Kapitel B"), doc.chapters.map { it.title })
        assertEquals(listOf("Kapitel A", "Hallo Welt Tab"), doc.chapters[0].paragraphs)
    }

    @Test
    fun odt() {
        val bytes = zip(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to """<office:document-content xmlns:office="o" xmlns:text="t"><office:body><office:text>
                <text:h text:outline-level="1">Überschrift</text:h><text:p>Wort<text:s/>Wort</text:p>
                <text:p>Mit Fußnote<text:note><text:note-body><text:p>Notiz</text:p></text:note-body></text:note>.</text:p>
                </office:text></office:body></office:document-content>""",
        )
        val doc = parser.parse(bytes, "a.odt")
        assertEquals(listOf("Überschrift", "Wort Wort", "Mit Fußnote."), doc.chapters.single().paragraphs)
    }

    @Test
    fun rtf() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Arial;}}{\*\generator Test;}\f0 Gr\'fc\'dfe aus K\u246?ln.\par Zweiter \b Absatz\b0.\par}"""
        val doc = parser.parse(rtf.toByteArray(), "a.rtf")
        assertEquals(listOf("Grüße aus Köln.", "Zweiter Absatz."), doc.chapters.single().paragraphs)
    }

    @Test
    fun pdfPagesWithHeadersAndPageBreaks() {
        val words = listOf("Erstens", "Zweitens", "Drittens", "Viertens")
        val pages = (1..4).joinToString("\u000C") { n ->
            "Mein Buch – Kopfzeile\n${words[n - 1]} geht der Satz auf dieser Seite\nüber zwei Zeilen weiter.\n\nAbsatz am Seitenende ${words[n - 1].lowercase()}\n$n"
        }
        val doc = parser.parse(pages.toByteArray(), "scan.pdf")
        val ps = doc.chapters.single().paragraphs
        assertTrue(ps.none { "Kopfzeile" in it })
        assertEquals("Erstens geht der Satz auf dieser Seite über zwei Zeilen weiter.", ps[0])
        assertEquals("Absatz am Seitenende erstens", ps[1])
    }

    @Test
    fun emptyPdfExplainsScans() {
        try {
            DocumentParser { listOf("", " \n ") }.parse("%PDF-1.4".toByteArray(), "scan.pdf"); fail()
        } catch (e: DocumentException) {
            assertTrue(e.message!!.contains("eingescannte", ignoreCase = true))
        }
    }

    @Test
    fun fb2() {
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?><FictionBook><description><title-info><book-title>Märchen</book-title>
            <author><first-name>Jakob</first-name><last-name>Grimm</last-name></author></title-info></description>
            <body><section><title><p>Erstes</p></title><p>Es war einmal.</p></section></body></FictionBook>"""
        val doc = parser.parse(fb2.toByteArray(), "m.fb2")
        assertEquals("Märchen", doc.title)
        assertEquals("Jakob Grimm", doc.author)
        assertEquals("Erstes", doc.chapters[0].title)
    }

    @Test
    fun formatDetection() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect(null, null, "%PDF-1.7".toByteArray()))
        assertEquals(DocumentFormat.EPUB, DocumentFormat.detect("x.EPUB", null, ByteArray(0)))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.detect("download", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ByteArray(0)))
    }
}
