package de.tontaube.vorlesen.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import de.tontaube.core.DocumentException
import de.tontaube.core.DocumentParser
import de.tontaube.core.ParsedDocument
import de.tontaube.core.SegmentedText
import de.tontaube.core.Segmenter
import de.tontaube.core.TontaubeJson
import de.tontaube.core.TtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Verwaltet Vorlese-Projekte: Import, gegliederter Text, Einstellungen, Leseposition, Audio-Zwischenspeicher. */
class ProjectRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Preferences,
    private val voices: VoiceRepository,
) {
    private val dao = db.projects()
    private val parser = DocumentParser(PdfBoxExtractor(context))
    private val segmenter = Segmenter()
    private val textCache = LruCache<Long, SegmentedText>(4)

    val projects: Flow<List<ProjectEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<ProjectEntity?> = dao.observe(id)
    suspend fun get(id: Long): ProjectEntity? = dao.get(id)
    fun getBlocking(id: Long): ProjectEntity? = dao.getBlocking(id)

    fun projectDir(id: Long) = File(context.filesDir, "projects/$id")
    private fun textFile(id: Long) = File(projectDir(id), "text.json")
    private fun audioRoot(id: Long) = File(projectDir(id), "audio")

    /** Ordner für das Audio der aktuellen Einstellungen (ändern sich die Einstellungen, gilt ein neuer Ordner). */
    fun audioDir(project: ProjectEntity): File {
        val settings = TtsSettings.decode(project.settingsJson)
        return File(audioRoot(project.id), settings.audioKey(voices.fingerprintBlocking(settings.voice)))
    }

    fun segmentFile(project: ProjectEntity, index: Int) = File(audioDir(project), "$index.mp3")

    fun text(id: Long): SegmentedText {
        synchronized(textCache) {
            textCache.get(id)?.let { return it }
            var st = TontaubeJson.decodeFromString(SegmentedText.serializer(), textFile(id).readText())
            if (st.version < Segmenter.VERSION) st = migrate(id, st)
            textCache.put(id, st)
            return st
        }
    }

    /**
     * Projekt mit älterer Aufteilung neu aufteilen (Abschnitte enden jetzt an Satzenden). Die Leseposition wird
     * auf den Abschnitt umgerechnet, der die alte Stelle enthält; erzeugtes Audio passt nicht mehr und wird gelöscht.
     */
    private fun migrate(id: Long, old: SegmentedText): SegmentedText {
        val (fresh, map) = segmenter.resegment(old)
        if (fresh.segments.isEmpty()) return old
        textFile(id).writeText(TontaubeJson.encodeToString(SegmentedText.serializer(), fresh))
        audioRoot(id).deleteRecursively()
        runBlocking {
            dao.get(id)?.let { p ->
                val index = map.getOrElse(p.segmentIndex) { 0 }.coerceIn(0, fresh.segments.size - 1)
                dao.update(p.copy(segmentIndex = index, positionMs = 0, segmentCount = fresh.segments.size, charCount = fresh.totalChars))
            }
        }
        return fresh
    }

    suspend fun textAsync(id: Long): SegmentedText = withContext(Dispatchers.IO) { text(id) }

    data class ImportResult(val projectId: Long, val doc: ParsedDocument)

    /** Dokument einlesen und als Projekt anlegen. Wirft [DocumentException] mit verständlicher Meldung. */
    suspend fun import(uri: Uri, mimeHint: String? = null): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) null else c.getLong(1)
            }
        }
        if ((size ?: 0) > MAX_IMPORT_BYTES) throw DocumentException("Die Datei ist zu groß (max. 200 MB).")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw DocumentException("Die Datei konnte nicht geöffnet werden.")
        val mime = mimeHint ?: resolver.getType(uri)
        val doc = parser.parse(bytes, name ?: uri.lastPathSegment, mime)
        val text = segmenter.segment(doc)
        if (text.segments.isEmpty()) throw DocumentException("Im Dokument wurde kein vorlesbarer Text gefunden.")
        val defaults = prefs.defaults.first()
        val id = dao.insert(
            ProjectEntity(
                title = doc.title.take(200),
                author = doc.author?.take(200),
                format = doc.format.name,
                sourceName = name,
                createdAt = System.currentTimeMillis(),
                settingsJson = defaults.encode(),
                segmentCount = text.segments.size,
                charCount = text.totalChars,
            ),
        )
        val file = textFile(id)
        file.parentFile?.mkdirs()
        file.writeText(TontaubeJson.encodeToString(SegmentedText.serializer(), text))
        textCache.put(id, text)
        ImportResult(id, doc)
    }

    /** Text direkt einfügen (z. B. aus der Zwischenablage oder „Teilen“). */
    suspend fun importText(title: String, body: String): Long = withContext(Dispatchers.IO) {
        val doc = parser.parse(body.toByteArray(), "$title.txt", "text/plain")
        val text = segmenter.segment(doc)
        if (text.segments.isEmpty()) throw DocumentException("Der Text enthält nichts Vorlesbares.")
        val id = dao.insert(
            ProjectEntity(
                title = title.take(200), author = null, format = doc.format.name, sourceName = null,
                createdAt = System.currentTimeMillis(), settingsJson = prefs.defaults.first().encode(),
                segmentCount = text.segments.size, charCount = text.totalChars,
            ),
        )
        textFile(id).apply { parentFile?.mkdirs() }.writeText(TontaubeJson.encodeToString(SegmentedText.serializer(), text))
        id
    }

    suspend fun updateSettings(id: Long, settings: TtsSettings) = withContext(Dispatchers.IO) {
        dao.updateSettings(id, settings.encode())
        // Audio älterer Einstellungen wird nicht mehr gebraucht
        val keep = dao.get(id)?.let { audioDir(it).name }
        audioRoot(id).listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
    }

    suspend fun updatePosition(id: Long, index: Int, positionMs: Long, finished: Boolean = false) {
        val progress = runCatching { textAsync(id).progressAt(index) }.getOrDefault(0f)
        dao.updatePosition(id, index, positionMs, if (finished) 1f else progress, finished, System.currentTimeMillis())
    }

    /** Nach dem Löschen einer Stimme: betroffene Projekte (und die Standardwerte) auf die Standardstimme setzen. */
    suspend fun resetVoice(ref: String) {
        dao.usingVoice(ref).forEach { p ->
            val s = TtsSettings.decode(p.settingsJson)
            if (s.voice == ref) updateSettings(p.id, s.copy(voice = de.tontaube.core.VoiceRef.DEFAULT))
        }
        val defaults = prefs.defaults.first()
        if (defaults.voice == ref) prefs.setDefaults(defaults.copy(voice = de.tontaube.core.VoiceRef.DEFAULT))
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title.trim().ifEmpty { "Unbenannt" })

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        textCache.remove(id)
        projectDir(id).deleteRecursively()
    }

    suspend fun clearAudio(id: Long) = withContext(Dispatchers.IO) { audioRoot(id).deleteRecursively() }

    suspend fun audioBytes(id: Long): Long = withContext(Dispatchers.IO) { audioRoot(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    suspend fun totalAudioBytes(): Long = withContext(Dispatchers.IO) {
        File(context.filesDir, "projects").walkBottomUp().filter { it.isFile && it.extension == "mp3" }.sumOf { it.length() }
    }

    suspend fun clearAllAudio() = withContext(Dispatchers.IO) {
        dao.ids().forEach { audioRoot(it).deleteRecursively() }
    }

    /** Wie viele Abschnitte sind für die aktuellen Einstellungen schon erzeugt? */
    suspend fun cachedSegments(project: ProjectEntity): Set<Int> = withContext(Dispatchers.IO) {
        audioDir(project).list()?.filter { it.endsWith(".mp3") }?.mapNotNull { it.removeSuffix(".mp3").toIntOrNull() }?.toSet()
            ?: emptySet()
    }

    companion object {
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024
    }
}
