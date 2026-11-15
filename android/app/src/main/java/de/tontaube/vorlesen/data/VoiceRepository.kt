package de.tontaube.vorlesen.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import de.tontaube.core.BundledVoice
import de.tontaube.core.BundledVoices
import de.tontaube.core.Languages
import de.tontaube.core.VoiceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class VoiceException(message: String) : Exception(message)

/** Eigene Stimmen (Referenzaudio auf dem Handy) und die mitgelieferten Stimmen des Servers. */
class VoiceRepository(private val context: Context, db: AppDatabase) {
    private val dao = db.voices()
    private val dir = File(context.filesDir, "voices").apply { mkdirs() }
    private val b64Cache = ConcurrentHashMap<Long, String>()

    val custom: Flow<List<VoiceEntity>> = dao.observeAll()

    val bundled: List<BundledVoice> by lazy {
        runCatching { context.assets.open("voices.json").bufferedReader().use { BundledVoices.parse(it.readText()) } }
            .getOrDefault(emptyList())
    }

    fun file(v: VoiceEntity) = File(dir, v.fileName)

    suspend fun get(id: Long) = dao.get(id)

    /** Anzeigename für einen Stimmen-Verweis. */
    suspend fun label(ref: String): String {
        VoiceRef.customId(ref)?.let { id -> return dao.get(id)?.name ?: "Gelöschte Stimme" }
        VoiceRef.serverPath(ref)?.let { path -> return bundled.firstOrNull { it.path == path }?.name ?: path.substringAfterLast('/') }
        return "Standardstimme"
    }

    /** Fingerabdruck für den Zwischenspeicher (ändert sich, wenn das Referenzaudio ein anderes ist). */
    fun fingerprintBlocking(ref: String): String =
        VoiceRef.customId(ref)?.let { dao.getBlocking(it)?.fingerprint ?: "fehlt" } ?: ""

    /** Referenzaudio als Base64 für `voice_audio_b64` (nur eigene Stimmen). */
    fun audioB64Blocking(ref: String): String? {
        val id = VoiceRef.customId(ref) ?: return null
        b64Cache[id]?.let { return it }
        val v = dao.getBlocking(id) ?: throw VoiceException("Die gewählte Stimme wurde gelöscht. Bitte im Projekt eine andere wählen.")
        val bytes = file(v).takeIf { it.exists() }?.readBytes()
            ?: throw VoiceException("Das Referenzaudio der Stimme „${v.name}“ fehlt.")
        return Base64.encodeToString(bytes, Base64.NO_WRAP).also { b64Cache[id] = it }
    }

    fun durationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)

    /** Neue Stimme aus einer Datei (Audio-Auswahl) anlegen. */
    suspend fun addFromUri(name: String, language: String, uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0)
        }
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 } ?: "audio"
        val tmp = File(dir, "import-${System.nanoTime()}.$ext")
        resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw VoiceException("Die Audiodatei konnte nicht geöffnet werden.")
        addFile(name, language, tmp)
    }

    /** Neue Stimme aus einer Datei im App-Speicher (z. B. Aufnahme) anlegen. Die Datei wird übernommen. */
    suspend fun addFile(name: String, language: String, source: File): Long = withContext(Dispatchers.IO) {
        try {
            if (source.length() > MAX_BYTES) throw VoiceException("Die Datei ist zu groß (max. 6 MB). Bitte eine kürzere Aufnahme wählen.")
            val duration = durationMs(source)
            if (duration in 1 until MIN_MS) throw VoiceException("Die Aufnahme ist zu kurz (${duration / 1000} s). Bitte mindestens 5 Sekunden.")
            val bytes = source.readBytes()
            val fp = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
            val fileName = "voice-$fp.${source.extension.ifEmpty { "audio" }}"
            val target = File(dir, fileName)
            if (source.absolutePath != target.absolutePath) {
                source.copyTo(target, overwrite = true)
            }
            dao.insert(
                VoiceEntity(
                    name = name.trim().ifEmpty { "Meine Stimme" },
                    language = language.ifEmpty { Languages.all.first().second },
                    fileName = fileName,
                    durationMs = duration,
                    fingerprint = fp,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            if (source.name.startsWith("import-") || source.name.startsWith("aufnahme-")) {
                source.delete()
            }
        }
    }

    suspend fun rename(v: VoiceEntity, name: String, language: String) =
        dao.update(v.copy(name = name.trim().ifEmpty { v.name }, language = language))

    suspend fun delete(v: VoiceEntity) = withContext(Dispatchers.IO) {
        dao.delete(v)
        b64Cache.remove(v.id)
        file(v).delete()
    }

    fun newRecordingFile(): File = File(context.cacheDir, "aufnahme-${System.currentTimeMillis()}.m4a")

    companion object {
        const val MIN_MS = 5_000L
        const val MAX_MS = 60_000L
        const val MAX_BYTES = 6L * 1024 * 1024
    }
}
