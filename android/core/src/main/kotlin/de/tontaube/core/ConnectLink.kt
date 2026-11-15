package de.tontaube.core

import java.net.URI
import java.net.URLDecoder

/** Verbindungsdaten aus dem QR-Code der Desktop-App: `tontaube://connect?url=…&key=…&name=…`. */
data class ConnectLink(val url: String, val apiKey: String?, val name: String?) {
    companion object {
        fun parse(raw: String): ConnectLink? {
            val text = raw.trim()
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
                return normalizeUrl(text)?.let { ConnectLink(it, null, null) }
            }
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (!uri.scheme.equals("tontaube", true)) return null
            val query = uri.rawQuery ?: return null
            val params = query.split('&').mapNotNull { part ->
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                if (k.isEmpty()) null else decode(k) to decode(v)
            }.toMap()
            val url = params["url"]?.let { normalizeUrl(it) } ?: return null
            return ConnectLink(url, params["key"]?.ifBlank { null }, params["name"]?.ifBlank { null })
        }

        private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")

        /** Ergänzt `http://` und den Standardport 8080, entfernt Schrägstriche am Ende. */
        fun normalizeUrl(input: String): String? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            if (uri.scheme !in setOf("http", "https")) return null
            val port = if (uri.port == -1 && uri.scheme == "http") 8080 else uri.port
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val path = uri.rawPath?.trimEnd('/').orEmpty()
            return buildString {
                append(uri.scheme).append("://").append(hostPart)
                if (port != -1) append(':').append(port)
                append(path)
            }
        }
    }
}
