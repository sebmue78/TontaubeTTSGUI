package de.tontaube.vorlesen.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import de.tontaube.core.ServerErrors
import de.tontaube.core.SseAudioParser
import de.tontaube.core.StreamEvent
import de.tontaube.vorlesen.net.ServerConfig
import de.tontaube.vorlesen.net.TontaubeApi
import de.tontaube.vorlesen.net.TontaubeException
import okhttp3.Call
import okhttp3.Response
import okio.BufferedSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Was für einen Abschnitt abgespielt werden soll: entweder eine fertige Datei aus dem Zwischenspeicher
 * oder eine Streaming-Anfrage an den Server, deren Audio dabei in [cacheFile] gespeichert wird.
 */
data class SegmentSource(
    val cacheFile: File?,
    val server: ServerConfig?,
    val requestBody: (() -> String)?,
)

/** Ermittelt zu einer Abschnitts-URI die Quelle (läuft auf dem Lade-Thread des Players). */
fun interface SegmentResolver {
    @Throws(IOException::class)
    fun resolve(uri: Uri): SegmentSource
}

/**
 * Media3-Datenquelle für `tontaube://segment/<projekt>/<abschnitt>` und `tontaube://sample/<id>`.
 *
 * Liegt der Abschnitt schon im Zwischenspeicher, wird die Datei gelesen (mit Spulen). Sonst wird er per
 * `POST /stream` erzeugt: Die MP3-Teile aus den Server-Sent Events werden direkt an den Player
 * weitergereicht und parallel in eine Datei geschrieben, die nach vollständigem Empfang übernommen wird.
 */
@UnstableApi
class SegmentDataSource(
    private val api: TontaubeApi,
    private val resolver: SegmentResolver,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: RandomAccessFile? = null
    private var fileRemaining: Long = 0

    private var call: Call? = null
    private var response: Response? = null
    private var source: BufferedSource? = null
    private var parser: SseAudioParser? = null
    private var pending: ByteArray = ByteArray(0)
    private var pendingOffset = 0
    private var streamDone = false
    private var receivedBytes = 0L
    private var tmpFile: File? = null
    private var tmpOut: FileOutputStream? = null
    private var target: File? = null
    private var opened = false

    override fun getUri(): Uri? = uri

    /** Laufende Server-Anfrage sofort abbrechen (Socket-Lesen reagiert nicht auf Thread-Unterbrechung). */
    fun cancel() {
        call?.cancel()
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val src = resolver.resolve(dataSpec.uri)
        val cached = src.cacheFile?.takeIf { it.exists() && it.length() > 0 }
        if (cached != null) {
            val raf = RandomAccessFile(cached, "r")
            raf.seek(dataSpec.position)
            file = raf
            fileRemaining = (raf.length() - dataSpec.position).coerceAtLeast(0)
            opened = true
            transferStarted(dataSpec)
            return fileRemaining
        }
        if (dataSpec.position > 0) {
            // Ein Stream lässt sich nicht spulen; der Player fragt dann ab Anfang.
            throw TontaubeException("Spulen ist erst möglich, wenn der Abschnitt vollständig erzeugt wurde.")
        }
        val server = src.server ?: throw TontaubeException("Kein Server eingerichtet. Bitte unter Einstellungen einen Server hinzufügen.")
        val body = src.requestBody?.invoke() ?: throw TontaubeException("Abschnitt nicht gefunden.")
        val c = api.streamCall(server, body)
        call = c
        val r = try {
            c.execute()
        } catch (e: IOException) {
            if (c.isCanceled()) throw InterruptedIOException("abgebrochen")
            throw TontaubeException(TontaubeApi.describe(e), retryable = true, cause = e)
        }
        response = r
        if (!r.isSuccessful) {
            val text = runCatching { r.body?.string() }.getOrNull()
            r.close()
            throw TontaubeException(ServerErrors.describe(r.code, text), retryable = r.code == 503)
        }
        val contentType = r.header("Content-Type").orEmpty()
        if (!contentType.startsWith("text/event-stream")) {
            r.close()
            throw TontaubeException("Unerwartete Antwort vom Server ($contentType).")
        }
        source = r.body!!.source()
        parser = SseAudioParser()
        src.cacheFile?.let { dest ->
            dest.parentFile?.mkdirs()
            target = dest
            // Eigene Temp-Datei je Quelle: parallele (abgebrochene) Ladevorgänge stören sich nicht
            tmpFile = File.createTempFile("seg-" + dest.nameWithoutExtension + "-", ".part", dest.parentFile).also { tmpOut = FileOutputStream(it) }
        }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        file?.let { raf ->
            if (fileRemaining <= 0) return C.RESULT_END_OF_INPUT
            val n = raf.read(buffer, offset, minOf(length.toLong(), fileRemaining).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            fileRemaining -= n
            bytesTransferred(n)
            return n
        }
        while (pendingOffset >= pending.size) {
            if (streamDone) return C.RESULT_END_OF_INPUT
            if (Thread.interrupted()) throw InterruptedIOException()
            nextChunk()
        }
        val n = minOf(length, pending.size - pendingOffset)
        System.arraycopy(pending, pendingOffset, buffer, offset, n)
        pendingOffset += n
        bytesTransferred(n)
        return n
    }

    /** Liest SSE-Zeilen, bis ein Audio-Teil da ist oder der Stream endet. */
    private fun nextChunk() {
        val src = source ?: throw IOException("Stream geschlossen")
        val p = parser!!
        while (true) {
            val line = try {
                src.readUtf8Line()
            } catch (e: IOException) {
                if (call?.isCanceled() == true) throw InterruptedIOException("abgebrochen")
                throw TontaubeException("Verbindung zum Server unterbrochen.", retryable = true, cause = e)
            }
            val events = if (line == null) p.finish() else p.feed(line)
            for (ev in events) {
                when (ev) {
                    is StreamEvent.Audio -> if (ev.bytes.isNotEmpty()) {
                        pending = ev.bytes
                        pendingOffset = 0
                        receivedBytes += ev.bytes.size
                        tmpOut?.write(ev.bytes)
                    }
                    is StreamEvent.Done -> finishStream()
                    is StreamEvent.Error -> throw TontaubeException("Der Server konnte den Abschnitt nicht erzeugen (${ev.message}).", retryable = true)
                }
            }
            if (line == null && !streamDone) {
                // Verbindung ohne „done“ beendet: unvollständig, nicht speichern
                throw TontaubeException("Der Stream endete unerwartet.", retryable = true)
            }
            if (pendingOffset < pending.size || streamDone) return
        }
    }

    private fun finishStream() {
        if (streamDone) return
        streamDone = true
        if (receivedBytes == 0L) throw TontaubeException("Der Server hat kein Audio geliefert.")
        tmpOut?.let { out ->
            out.flush(); out.fd.sync(); out.close()
            tmpOut = null
            val dest = target
            val tmp = tmpFile
            if (dest != null && tmp != null && (dest.exists() || !tmp.renameTo(dest))) tmp.delete()
            tmpFile = null
        }
    }

    override fun close() {
        uri = null
        file?.let { runCatching { it.close() } }
        file = null
        call?.let { if (!streamDone) it.cancel() }
        call = null
        response?.let { runCatching { it.close() } }
        response = null
        source = null
        parser = null
        pending = ByteArray(0)
        pendingOffset = 0
        tmpOut?.let { runCatching { it.close() } }
        tmpOut = null
        tmpFile?.delete() // unvollständig
        tmpFile = null
        target = null
        streamDone = false
        receivedBytes = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    @UnstableApi
    class Factory(private val api: TontaubeApi, private val resolver: SegmentResolver) : DataSource.Factory {
        private val active = java.util.Collections.newSetFromMap(java.util.WeakHashMap<SegmentDataSource, Boolean>())

        override fun createDataSource(): DataSource = SegmentDataSource(api, resolver).also { synchronized(active) { active += it } }

        /** Alle laufenden Streams abbrechen (z. B. beim Springen an eine andere Stelle). */
        fun cancelAll() {
            val list = synchronized(active) { active.toList() }
            list.forEach { it.cancel() }
        }
    }
}
