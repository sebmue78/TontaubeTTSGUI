package de.tontaube.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Liefert den Text eines PDFs seitenweise (auf Android über PdfBox, siehe App-Modul). */
fun interface PdfTextExtractor {
    fun pages(bytes: ByteArray): List<String>
}

/** Wandelt Dokumente (TXT, Markdown, HTML, EPUB, PDF, DOCX, ODT, RTF, FB2) in [ParsedDocument] um. */
class DocumentParser(private val pdf: PdfTextExtractor? = null) {

    fun parse(bytes: ByteArray, fileName: String?, mimeType: String? = null): ParsedDocument {
        val format = DocumentFormat.detect(fileName, mimeType, bytes.copyOf(minOf(bytes.size, 4096)))
            ?: throw DocumentException("Dieses Dateiformat wird nicht unterstützt.")
        val fallbackTitle = fileName?.substringBeforeLast('.')?.replace('_', ' ')?.trim().orEmpty().ifEmpty { "Unbenannt" }
        val doc = try {
            when (format) {
                DocumentFormat.TXT -> parsePlain(decodeText(bytes), fallbackTitle)
                DocumentFormat.MARKDOWN -> parseMarkdown(decodeText(bytes), fallbackTitle)
                DocumentFormat.HTML -> parseHtml(bytes, fallbackTitle)
                DocumentFormat.EPUB -> parseEpub(bytes, fallbackTitle)
                DocumentFormat.PDF -> parsePdf(bytes, fallbackTitle)
                DocumentFormat.DOCX -> parseDocx(bytes, fallbackTitle)
                DocumentFormat.ODT -> parseOdt(bytes, fallbackTitle)
                DocumentFormat.RTF -> parsePlain(RtfStripper.toText(bytes), fallbackTitle, format = DocumentFormat.RTF)
                DocumentFormat.FB2 -> parseFb2(bytes, fallbackTitle)
            }
        } catch (e: DocumentException) {
            throw e
        } catch (e: Exception) {
            throw DocumentException("Die Datei konnte nicht gelesen werden (${e.javaClass.simpleName}: ${e.message}).", e)
        }
        if (doc.isEmpty) {
            throw DocumentException(
                if (format == DocumentFormat.PDF) {
                    "Im PDF wurde kein Text gefunden. Eingescannte PDFs ohne Textebene werden nicht unterstützt."
                } else {
                    "Im Dokument wurde kein vorlesbarer Text gefunden."
                },
            )
        }
        return doc
    }

    // -- Text -----------------------------------------------------------------------------------

    private val headingLine = Regex(
        "^(kapitel|chapter|teil|part|buch|book|prolog|epilog|prologue|epilogue|vorwort|nachwort|einleitung|" +
            "introduction|anhang|appendix)\\b.{0,60}$",
        RegexOption.IGNORE_CASE,
    )

    private fun isHeadingParagraph(p: String) = p.length <= 70 && headingLine.matches(p) && !p.endsWith(",")

    /** Kapitel anhand typischer Überschriften („Kapitel 3“, „Prolog“ …) bilden. */
    private fun chaptersFromParagraphs(paragraphs: List<String>): List<Chapter> {
        val headingCount = paragraphs.count { isHeadingParagraph(it) }
        if (headingCount < 2) return listOf(Chapter(null, paragraphs))
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var current = mutableListOf<String>()
        for (p in paragraphs) {
            if (isHeadingParagraph(p)) {
                if (current.isNotEmpty() || title != null) chapters += Chapter(title, current)
                title = p
                current = mutableListOf(p)
            } else {
                current += p
            }
        }
        chapters += Chapter(title, current)
        return chapters.filter { it.paragraphs.isNotEmpty() }
    }

    private fun parsePlain(text: String, title: String, format: DocumentFormat = DocumentFormat.TXT): ParsedDocument {
        val paragraphs = TextCleanup.paragraphsFromPlainText(text)
        return ParsedDocument(title, null, chaptersFromParagraphs(paragraphs), format)
    }

    private val mdImage = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val mdLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val mdEmph = Regex("(\\*\\*|__|\\*|_|~~|`+)(?=\\S)(.+?)(?<=\\S)\\1")
    private val mdListMarker = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
    private val mdQuote = Regex("^\\s*>+\\s?")
    private val mdHeading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val htmlTag = Regex("<[^>]+>")

    private fun parseMarkdown(text: String, fallbackTitle: String): ParsedDocument {
        val chapters = mutableListOf<Chapter>()
        var chapterTitle: String? = null
        var paragraphs = mutableListOf<String>()
        val block = StringBuilder()
        var inCode = false
        var docTitle: String? = null

        fun inline(s: String): String {
            var t = s.replace(mdImage, "").replace(mdLink, "$1").replace(htmlTag, "")
            repeat(2) { t = t.replace(mdEmph, "$2") }
            return TextCleanup.normalizeLine(t)
        }
        fun flushBlock() {
            val p = TextCleanup.normalizeLine(block.toString())
            if (TextCleanup.isSpeakable(p)) paragraphs += p
            block.clear()
        }
        for (rawLine in text.replace("\r\n", "\n").lines()) {
            if (rawLine.trimStart().startsWith("```") || rawLine.trimStart().startsWith("~~~")) {
                flushBlock(); inCode = !inCode; continue
            }
            if (inCode) continue
            val heading = mdHeading.matchEntire(rawLine)
            when {
                heading != null -> {
                    flushBlock()
                    val h = inline(heading.groupValues[2])
                    val level = heading.groupValues[1].length
                    if (level <= 2) {
                        if (paragraphs.isNotEmpty()) chapters += Chapter(chapterTitle, paragraphs)
                        paragraphs = mutableListOf()
                        chapterTitle = h
                        if (docTitle == null && level == 1) docTitle = h
                    }
                    if (TextCleanup.isSpeakable(h)) paragraphs += h
                }
                rawLine.isBlank() -> flushBlock()
                rawLine.trim().matches(Regex("^([-*_]\\s*){3,}$")) -> flushBlock()
                rawLine.trimStart().startsWith("|") -> {
                    val cells = rawLine.trim().trim('|').split('|').map { inline(it) }.filter { TextCleanup.isSpeakable(it) }
                    if (cells.isNotEmpty()) { flushBlock(); block.append(cells.joinToString(", ")); flushBlock() }
                }
                mdListMarker.containsMatchIn(rawLine) -> {
                    flushBlock(); block.append(inline(rawLine.replace(mdListMarker, ""))); flushBlock()
                }
                else -> {
                    if (block.isNotEmpty()) block.append(' ')
                    block.append(inline(rawLine.replace(mdQuote, "")))
                }
            }
        }
        flushBlock()
        if (paragraphs.isNotEmpty()) chapters += Chapter(chapterTitle, paragraphs)
        return ParsedDocument(docTitle ?: fallbackTitle, null, chapters, DocumentFormat.MARKDOWN)
    }

    // -- HTML / XHTML ---------------------------------------------------------------------------

    private class Block(val text: String, val headingLevel: Int)

    private val blockTags = setOf(
        "p", "div", "section", "article", "header", "footer", "aside", "main", "nav", "blockquote", "pre",
        "li", "dt", "dd", "tr", "table", "ul", "ol", "dl", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6",
        "br", "hr", "body", "caption",
    )
    private val skipTags = setOf("script", "style", "head", "title", "noscript", "svg", "math", "rt", "rp", "template", "button")

    /** Zerlegt HTML in Textblöcke an Blockgrenzen (Absätze, Überschriften, Listenpunkte …). */
    private fun htmlBlocks(root: Element): List<Block> {
        val out = mutableListOf<Block>()
        val sb = StringBuilder()
        var heading = 0
        var skipDepth = 0
        fun flush() {
            val t = TextCleanup.normalizeLine(sb.toString())
            if (TextCleanup.isSpeakable(t)) out += Block(t, heading)
            sb.clear()
            heading = 0
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in skipTags || node.attr("epub:type") == "pagebreak" || node.hasAttr("hidden")) {
                        skipDepth++
                        return
                    }
                    if (skipDepth > 0) return
                    if (tag in blockTags) {
                        flush()
                        if (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()) heading = tag[1].digitToInt()
                    }
                    if (tag == "td" || tag == "th") sb.append(' ')
                } else if (node is TextNode && skipDepth == 0) {
                    sb.append(node.wholeText)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in skipTags || node.attr("epub:type") == "pagebreak" || node.hasAttr("hidden")) {
                        skipDepth--
                        return
                    }
                    if (skipDepth == 0 && tag in blockTags) flush()
                }
            }
        }, root)
        flush()
        return out
    }

    private fun parseHtml(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val blocks = htmlBlocks(doc.body())
        val title = doc.title().trim().ifEmpty { blocks.firstOrNull { it.headingLevel == 1 }?.text ?: fallbackTitle }
        val author = doc.select("meta[name=author]").attr("content").ifBlank { null }
        return ParsedDocument(title, author, chaptersFromBlocks(blocks), DocumentFormat.HTML)
    }

    private fun chaptersFromBlocks(blocks: List<Block>): List<Chapter> {
        val topLevel = blocks.filter { it.headingLevel > 0 }.minOfOrNull { it.headingLevel } ?: return listOf(
            Chapter(null, blocks.map { it.text }),
        )
        val chapterLevel = if (blocks.count { it.headingLevel == topLevel } == 1) {
            blocks.filter { it.headingLevel > topLevel }.minOfOrNull { it.headingLevel } ?: topLevel
        } else {
            topLevel
        }
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var current = mutableListOf<String>()
        for (b in blocks) {
            if (b.headingLevel in 1..chapterLevel) {
                if (current.isNotEmpty()) chapters += Chapter(title, current)
                title = b.text
                current = mutableListOf()
            }
            current += b.text
        }
        if (current.isNotEmpty()) chapters += Chapter(title, current)
        return chapters
    }

    // -- EPUB -----------------------------------------------------------------------------------

    private fun unzip(bytes: ByteArray, maxTotal: Long = 512L * 1024 * 1024): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zip.readBytes()
                total += data.size
                if (total > maxTotal) throw DocumentException("Das Archiv ist zu groß.")
                files[entry.name.trimStart('/')] = data
            }
        }
        return files
    }

    private fun xml(bytes: ByteArray): Document = Jsoup.parse(ByteArrayInputStream(bytes), "UTF-8", "", Parser.xmlParser())

    private fun resolvePath(baseDir: String, href: String): String {
        val decoded = try { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") } catch (_: Exception) { href }
        val parts = (if (decoded.startsWith("/")) decoded.trimStart('/') else baseDir + decoded).split('/')
        val stack = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun parseEpub(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val files = unzip(bytes)
        files.entries.firstOrNull { it.key.equals("META-INF/encryption.xml", ignoreCase = true) }?.let { (_, enc) ->
            // Schriftarten-Verschleierung ist erlaubt, verschlüsselter Text (DRM) nicht.
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            val algorithms = xml(enc).select("EncryptionMethod, enc|EncryptionMethod").map { it.attr("Algorithm") }
            if (algorithms.any { it !in fontObfuscation }) {
                throw DocumentException("Dieses EPUB ist kopiergeschützt (DRM) und kann nicht gelesen werden.")
            }
        }
        val container = files["META-INF/container.xml"] ?: throw DocumentException("Ungültiges EPUB (container.xml fehlt).")
        val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path")?.trimStart('/')
            ?: throw DocumentException("Ungültiges EPUB (kein Rootfile).")
        val opf = xml(files[opfPath] ?: throw DocumentException("Ungültiges EPUB ($opfPath fehlt)."))
        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val title = opf.select("metadata > *").firstOrNull { it.normalName().endsWith("title") }?.text()?.trim()
        val author = opf.select("metadata > *").filter { it.normalName().endsWith("creator") }
            .joinToString(", ") { it.text().trim() }.ifBlank { null }

        data class Item(val href: String, val type: String, val props: String)
        val manifest = opf.select("manifest > item").associate {
            it.attr("id") to Item(resolvePath(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val spine = opf.select("spine > itemref")
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.type.contains("html") || it.href.endsWith(".xhtml") || it.href.endsWith(".html") || it.href.endsWith(".htm") }
            .filterNot { it.props.contains("nav") }

        // Inhaltsverzeichnis: EPUB 3 (nav) oder EPUB 2 (NCX)
        val tocTitles = HashMap<String, String>()
        manifest.values.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav ->
            files[nav.href]?.let { data ->
                val navDir = nav.href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val navDoc = Jsoup.parse(ByteArrayInputStream(data), "UTF-8", "")
                val tocNav = navDoc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: navDoc.selectFirst("nav")
                tocNav?.select("a[href]")?.forEach { a ->
                    tocTitles.putIfAbsent(resolvePath(navDir, a.attr("href")), TextCleanup.normalizeLine(a.text()))
                }
            }
        }
        if (tocTitles.isEmpty()) {
            val ncxId = opf.selectFirst("spine")?.attr("toc").orEmpty()
            val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
            ncx?.let { item ->
                files[item.href]?.let { data ->
                    val ncxDir = item.href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    xml(data).select("navPoint").forEach { np ->
                        val label = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                        val src = np.selectFirst("content")?.attr("src").orEmpty()
                        if (label.isNotEmpty() && src.isNotEmpty()) tocTitles.putIfAbsent(resolvePath(ncxDir, src), label)
                    }
                }
            }
        }

        val chapters = mutableListOf<Chapter>()
        for (item in spine) {
            val data = files[item.href] ?: continue
            val doc = Jsoup.parse(ByteArrayInputStream(data), "UTF-8", "")
            val blocks = htmlBlocks(doc.body())
            if (blocks.isEmpty()) continue
            val chapterTitle = tocTitles[item.href] ?: blocks.firstOrNull { it.headingLevel in 1..3 }?.text
            val paragraphs = blocks.map { it.text }
            // Sehr kurze Dokumente ohne eigenen Titel (z. B. Titelei) an das vorige Kapitel anhängen
            if (chapterTitle == null && chapters.isNotEmpty() && paragraphs.sumOf { it.length } < 200) {
                val last = chapters.removeAt(chapters.lastIndex)
                chapters += Chapter(last.title, last.paragraphs + paragraphs)
            } else {
                chapters += Chapter(chapterTitle, paragraphs)
            }
        }
        return ParsedDocument(title?.ifBlank { null } ?: fallbackTitle, author, chapters, DocumentFormat.EPUB)
    }

    // -- PDF ------------------------------------------------------------------------------------

    private val digits = Regex("\\d+")

    private fun parsePdf(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val extractor = pdf ?: throw DocumentException("PDF wird auf diesem Gerät nicht unterstützt.")
        val pages = extractor.pages(bytes).map { it.replace("\r\n", "\n").replace('\r', '\n') }
        // Kopf- und Fußzeilen, die sich auf vielen Seiten wiederholen, entfernen
        val repeating = HashSet<String>()
        if (pages.size >= 4) {
            val counts = HashMap<String, Int>()
            for (page in pages) {
                val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }
                (lines.take(2) + lines.takeLast(2)).toSet().forEach { l ->
                    val key = l.replace(digits, "#")
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            counts.filter { it.value >= pages.size / 2 && it.key.length < 120 }.keys.forEach { repeating += it }
        }
        val paragraphs = mutableListOf<String>()
        for (page in pages) {
            val lines = page.lines().filterNot { it.trim().replace(digits, "#") in repeating }
            val text = lines.joinToString("\n")
            val hasBlankLines = Regex("\\n\\s*\\n").containsMatchIn(text)
            var pageParagraphs = if (hasBlankLines) {
                TextCleanup.paragraphsFromPlainText(text, dropPageNumbers = true)
            } else {
                TextCleanup.paragraphsFromLines(lines)
            }
            if (pageParagraphs.isNotEmpty() && hasBlankLines && pageParagraphs.maxOf { it.length } > 2500) {
                pageParagraphs = TextCleanup.paragraphsFromLines(lines)
            }
            if (pageParagraphs.isEmpty()) continue
            // Absatz über den Seitenumbruch fortsetzen, wenn der vorige nicht mit einem Satzende aufhört
            val last = paragraphs.lastOrNull()
            val first = pageParagraphs.first()
            if (last != null && last.last() !in ".!?:…\"»«“”" && first.first().isLowerCase()) {
                paragraphs[paragraphs.lastIndex] = if (last.endsWith("-") && last.length > 1 && last[last.length - 2].isLowerCase()) {
                    last.dropLast(1) + first
                } else {
                    "$last $first"
                }
                paragraphs += pageParagraphs.drop(1)
            } else {
                paragraphs += pageParagraphs
            }
        }
        val title = paragraphs.firstOrNull()?.takeIf { it.length in 3..90 } ?: fallbackTitle
        return ParsedDocument(fallbackTitle.takeIf { it != "Unbenannt" } ?: title, null, chaptersFromParagraphs(paragraphs), DocumentFormat.PDF)
    }

    // -- DOCX / ODT / FB2 -----------------------------------------------------------------------

    private fun parseDocx(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val files = unzip(bytes)
        val raw = files["word/document.xml"] ?: throw DocumentException("Ungültige DOCX-Datei (document.xml fehlt).")
        val xmlText = String(raw, Charsets.UTF_8)
            .replace(Regex("<w:tab\\s*/>"), "<w:t> </w:t>")
            .replace(Regex("<w:(br|cr)\\b[^>]*/>"), "<w:t> </w:t>")
        val doc = Jsoup.parse(xmlText, "", Parser.xmlParser())
        val blocks = doc.select("w|body w|p").mapNotNull { p ->
            // Nur direkte Absätze (keine Absätze in Textfeldern doppelt zählen)
            if (p.parents().any { it.normalName() == "w:txbxcontent" }) return@mapNotNull null
            val text = TextCleanup.normalizeLine(p.select("w|t").joinToString("") { it.wholeText() })
            if (!TextCleanup.isSpeakable(text)) return@mapNotNull null
            val style = p.selectFirst("w|pPr > w|pStyle")?.attr("w:val").orEmpty().lowercase()
            val level = Regex("(heading|berschrift|titre|titolo)\\s*(\\d)").find(style)?.groupValues?.get(2)?.toIntOrNull()
                ?: if (style == "title" || style == "titel") 1 else 0
            Block(text, level)
        }
        val props = files["docProps/core.xml"]?.let { xml(it) }
        val title = props?.selectFirst("dc|title")?.text()?.ifBlank { null }
        val author = props?.selectFirst("dc|creator")?.text()?.ifBlank { null }
        return ParsedDocument(title ?: fallbackTitle, author, chaptersFromBlocks(blocks), DocumentFormat.DOCX)
    }

    private fun parseOdt(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val files = unzip(bytes)
        val raw = files["content.xml"] ?: throw DocumentException("Ungültige ODT-Datei (content.xml fehlt).")
        val xmlText = String(raw, Charsets.UTF_8)
            .replace(Regex("<text:(s|tab|line-break)\\b[^>]*/>"), " ")
        val doc = Jsoup.parse(xmlText, "", Parser.xmlParser())
        doc.select("text|note, text|tracked-changes").remove()
        val blocks = doc.select("office|text").select("text|h, text|p").mapNotNull { el ->
            val text = TextCleanup.normalizeLine(el.text())
            if (!TextCleanup.isSpeakable(text)) return@mapNotNull null
            val level = if (el.normalName() == "text:h") el.attr("text:outline-level").toIntOrNull() ?: 1 else 0
            Block(text, level)
        }
        val meta = files["meta.xml"]?.let { xml(it) }
        val title = meta?.selectFirst("dc|title")?.text()?.ifBlank { null }
        val author = meta?.selectFirst("meta|initial-creator, dc|creator")?.text()?.ifBlank { null }
        return ParsedDocument(title ?: fallbackTitle, author, chaptersFromBlocks(blocks), DocumentFormat.ODT)
    }

    private fun parseFb2(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val doc = xml(bytes)
        val info = doc.selectFirst("description > title-info")
        val title = info?.selectFirst("book-title")?.text()?.ifBlank { null }
        val author = info?.select("author")?.joinToString(", ") { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.selectFirst(it)?.text() }.joinToString(" ")
        }?.ifBlank { null }
        val chapters = mutableListOf<Chapter>()
        val bodies = doc.select("FictionBook > body, body").filter { it.attr("name") != "notes" }.distinct()
        for (body in bodies) {
            val sections = body.children().filter { it.normalName() == "section" }
            if (sections.isEmpty()) {
                val ps = body.select("p").map { TextCleanup.normalizeLine(it.text()) }.filter { TextCleanup.isSpeakable(it) }
                if (ps.isNotEmpty()) chapters += Chapter(null, ps)
            }
            for (section in sections) {
                val sectionTitle = section.selectFirst("> title")?.text()?.let { TextCleanup.normalizeLine(it) }
                val ps = section.select("p, subtitle, v").map { TextCleanup.normalizeLine(it.text()) }
                    .filter { TextCleanup.isSpeakable(it) }
                if (ps.isNotEmpty()) chapters += Chapter(sectionTitle, ps)
            }
        }
        return ParsedDocument(title ?: fallbackTitle, author, chapters, DocumentFormat.FB2)
    }

    companion object {
        /** UTF-8 (mit/ohne BOM), UTF-16 mit BOM, sonst Windows-1252. */
        fun decodeText(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }
}
