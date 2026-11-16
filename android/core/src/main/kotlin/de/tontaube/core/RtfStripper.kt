package de.tontaube.core

import java.nio.charset.Charset

/** Einfacher RTF-zu-Text-Wandler (Absätze, Sonderzeichen, Unicode; Bilder und Tabellen-Formatierung entfallen). */
object RtfStripper {
    private val skipDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "footnote", "listtable", "listoverridetable", "revtbl", "rsidtbl", "generator",
        "xmlnstbl", "themedata", "colorschememapping", "datastore", "latentstyles", "pgdsctbl", "filetbl", "bkmkstart",
        "bkmkend", "fldinst", "nonshppict", "shppict", "mmathPr", "wgrffmtfilter",
    )

    fun toText(bytes: ByteArray): String {
        val s = String(bytes, Charsets.ISO_8859_1)
        var codepage: Charset = Charset.forName("windows-1252")
        val out = StringBuilder()
        // Stapel: (überspringen?, Anzahl Ersatzzeichen nach \\u)
        val skipStack = ArrayDeque<Boolean>()
        val ucStack = ArrayDeque<Int>()
        var skip = false
        var uc = 1
        var pendingSkipChars = 0
        var i = 0
        val hexBuf = java.io.ByteArrayOutputStream()

        fun flushHex() {
            if (hexBuf.size() > 0) {
                if (!skip) out.append(String(hexBuf.toByteArray(), codepage))
                hexBuf.reset()
            }
        }

        while (i < s.length) {
            val c = s[i]
            when {
                c == '{' -> { flushHex(); skipStack.addLast(skip); ucStack.addLast(uc); i++ }
                c == '}' -> {
                    flushHex()
                    skip = skipStack.removeLastOrNull() ?: false
                    uc = ucStack.removeLastOrNull() ?: 1
                    i++
                }
                c == '\\' -> {
                    if (i + 1 >= s.length) break
                    val n = s[i + 1]
                    when {
                        n == '\'' && i + 3 < s.length -> {
                            val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                            if (pendingSkipChars > 0) pendingSkipChars-- else if (hex != null) hexBuf.write(hex)
                            i += 4
                        }
                        n == '*' -> { skip = true; i += 2 }
                        n == '\\' || n == '{' || n == '}' -> { flushHex(); if (!skip) out.append(n); i += 2 }
                        n == '~' -> { flushHex(); if (!skip) out.append(' '); i += 2 }
                        n == '-' || n == '_' -> { i += 2 }
                        n == '\n' || n == '\r' -> { flushHex(); if (!skip) out.append('\n'); i += 2 }
                        n.isLetter() -> {
                            flushHex()
                            var j = i + 1
                            while (j < s.length && s[j].isLetter()) j++
                            val word = s.substring(i + 1, j)
                            var k = j
                            if (k < s.length && (s[k] == '-' || s[k].isDigit())) {
                                k++
                                while (k < s.length && s[k].isDigit()) k++
                            }
                            val param = s.substring(j, k).toIntOrNull()
                            if (k < s.length && s[k] == ' ') k++
                            i = k
                            when (word) {
                                in skipDestinations -> skip = true
                                "par", "line", "sect", "page" -> if (!skip) out.append("\n\n")
                                "tab", "cell" -> if (!skip) out.append(' ')
                                "row" -> if (!skip) out.append('\n')
                                "emdash" -> if (!skip) out.append('—')
                                "endash" -> if (!skip) out.append('–')
                                "lquote" -> if (!skip) out.append('‘')
                                "rquote" -> if (!skip) out.append('’')
                                "ldblquote" -> if (!skip) out.append('“')
                                "rdblquote" -> if (!skip) out.append('”')
                                "bullet" -> if (!skip) out.append('•')
                                "uc" -> uc = param ?: 1
                                "u" -> {
                                    if (!skip && param != null) out.append((if (param < 0) param + 65536 else param).toChar())
                                    pendingSkipChars = uc
                                }
                                "ansicpg" -> param?.let { cp ->
                                    runCatching { codepage = Charset.forName("windows-$cp") }
                                }
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    flushHex()
                    if (pendingSkipChars > 0) pendingSkipChars-- else if (!skip) out.append(c)
                    i++
                }
            }
        }
        flushHex()
        return out.toString()
    }
}
