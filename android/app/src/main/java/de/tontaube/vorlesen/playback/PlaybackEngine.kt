package de.tontaube.vorlesen.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import de.tontaube.core.BufferPolicy
import de.tontaube.core.RateEstimator
import de.tontaube.core.SegmentedText
import de.tontaube.core.TtsRequest
import de.tontaube.core.TtsSettings
import de.tontaube.vorlesen.data.Preferences
import de.tontaube.vorlesen.data.ProjectEntity
import de.tontaube.vorlesen.data.ProjectRepository
import de.tontaube.vorlesen.data.ServerRepository
import de.tontaube.vorlesen.data.VoiceException
import de.tontaube.vorlesen.data.VoiceRepository
import de.tontaube.vorlesen.net.TontaubeApi
import de.tontaube.vorlesen.net.TontaubeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Zustand der Wiedergabe für die Oberfläche. */
data class PlaybackState(
    val projectId: Long? = null,
    val title: String = "",
    val chapterTitle: String? = null,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = C.TIME_UNSET,
    val seekable: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    val finished: Boolean = false,
    /** Bereits erzeugtes Audio vor der Abspielposition. */
    val bufferedMs: Long = 0,
    /** So viel Audio wird abgewartet, bevor es (weiter) losgeht. */
    val bufferTargetMs: Long = 0,
    /** Gemessene Servergeschwindigkeit bezogen auf das Tempo (1,0 = Echtzeit), null = noch unbekannt. */
    val realtimeFactor: Double? = null,
) {
    val hasProject: Boolean get() = projectId != null
}

data class SampleState(val loading: Boolean = false, val playing: Boolean = false, val error: String? = null)

/**
 * Spielt Vorlese-Projekte abschnittsweise ab. Im Player liegt ein Fenster an Abschnitten (aktueller + die
 * nächsten [ahead]); beim Weiterschalten wird es nachgefüllt. Der Player lädt ohne Pause voraus, sodass der
 * Server immer die nächsten Abschnitte erzeugt. Wie viel Audio vor dem Start bzw. nach einem Stocken
 * abgewartet wird, richtet sich nach der Vorpuffer-Einstellung und der gemessenen Servergeschwindigkeit.
 */
@UnstableApi
class PlaybackEngine(
    context: Context,
    private val projects: ProjectRepository,
    private val voices: VoiceRepository,
    private val servers: ServerRepository,
    private val prefs: Preferences,
    api: TontaubeApi,
    private val scope: CoroutineScope,
) {
    private val samples = ConcurrentHashMap<Long, String>()
    private val sampleIds = AtomicLong()

    private val resolver = SegmentResolver { uri -> resolve(uri) }
    private val loadStats = SegmentDataSource.LoadStats()
    private val dataSourceFactory = SegmentDataSource.Factory(api, resolver, loadStats)
    private val loadControl = AdaptiveLoadControl()

    private val extractors = ExtractorsFactory {
        arrayOf<Extractor>(Mp3Extractor(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING))
    }

    private val errorPolicy = object : DefaultLoadErrorHandlingPolicy(2) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is TontaubeException && !e.retryable) return C.TIME_UNSET
            if (e.findCause<VoiceException>() != null) return C.TIME_UNSET
            return (info.errorCount * 2000L).coerceAtMost(6000L)
        }
    }

    private val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
        .setLoadErrorHandlingPolicy(errorPolicy)
        .setContinueLoadingCheckIntervalBytes(16 * 1024)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SKIP_MS)
        .setSeekForwardIncrementMs(SKIP_MS)
        .build()

    val samplePlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            ProgressiveMediaSource.Factory(SegmentDataSource.Factory(api, resolver), extractors).setLoadErrorHandlingPolicy(errorPolicy),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private val _sample = MutableStateFlow(SampleState())
    val sample: StateFlow<SampleState> = _sample

    private var project: ProjectEntity? = null
    private var text: SegmentedText? = null
    private var ticker: Job? = null
    private var lastSaved = 0L

    // -- Vorpuffer ------------------------------------------------------------------------------
    /** Vorpuffer-Einstellung ([BufferPolicy.AUTO] oder Sekunden). */
    private var bufferMode = BufferPolicy.AUTO
    /** Erzeugungsgeschwindigkeit des aktiven Servers (Audiosekunden je Sekunde, unabhängig vom Tempo). */
    private var rate = RateEstimator()
    private var serverId: Long? = null
    private var lastPersistedRate = 0L
    /** Anzahl Abschnitte, die nach dem aktuellen im Player liegen. */
    private var ahead = 3
    /** Wurde im aktuellen Fenster schon gespielt? Dann gilt beim nächsten Warten die Schwelle „nach Stocken“. */
    private var playedInWindow = false
    /** Wird bei jedem Fensterwechsel erhöht; Messungen über einen Wechsel hinweg werden verworfen. */
    private var windowGeneration = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = mediaItem?.segmentIndex() ?: return
                onSegmentChanged(index)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playedInWindow = true
                publish()
                if (isPlaying) startTicker() else savePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onFinished()
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = describe(error), isPlaying = false, isBuffering = false) }
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = publish()
        })
        samplePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _sample.update {
                    it.copy(
                        loading = playbackState == Player.STATE_BUFFERING,
                        playing = playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _sample.value = SampleState(error = describe(error))
            }
        })
    }

    init {
        scope.launch {
            prefs.bufferMode.collect { mode ->
                bufferMode = mode
                applyBufferTargets()
            }
        }
        scope.launch {
            servers.active.collect { s ->
                if (s?.id == serverId) return@collect
                serverId = s?.id
                rate = RateEstimator()
                s?.let { srv -> prefs.generationRate(srv.id)?.let { rate.seed(it.toDouble()) } }
                applyBufferTargets()
            }
        }
        startMonitor()
    }

    // -- Steuerung ------------------------------------------------------------------------------

    /** Projekt öffnen (ohne Angabe an der gespeicherten Stelle) und optional abspielen. */
    fun open(projectId: Long, segmentIndex: Int? = null, positionMs: Long? = null, play: Boolean = true) {
        scope.launch {
            if (project?.id != projectId) savePosition()
            // Text zuerst laden: ältere Projekte werden dabei neu aufgeteilt und ihre Position umgerechnet
            val t = runCatching { projects.textAsync(projectId) }.getOrElse {
                _state.update { s -> s.copy(error = "Der Text des Projekts konnte nicht geladen werden.") }
                return@launch
            }
            val p = projects.get(projectId) ?: return@launch
            if (t.segments.isEmpty()) {
                _state.update { s -> s.copy(error = "Das Projekt enthält keinen vorlesbaren Text.") }
                return@launch
            }
            project = p
            text = t
            val index = (segmentIndex ?: p.segmentIndex).coerceIn(0, t.segments.size - 1)
            val pos = if (segmentIndex == null) positionMs ?: p.positionMs else positionMs ?: 0L
            val restart = p.finished && segmentIndex == null
            stopSample()
            loadWindow(if (restart) 0 else index, if (restart) 0 else pos, play)
            servers.refresh()
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            resume()
        }
    }

    fun resume() {
        stopSample()
        if (_state.value.error != null || player.playbackState == Player.STATE_IDLE) {
            retry()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            loadWindow(0, 0, true)
            return
        }
        player.play()
    }

    fun pause() = player.pause()

    /** Nach einem Fehler am aktuellen Abschnitt neu ansetzen. */
    fun retry() {
        val p = project ?: return
        _state.update { it.copy(error = null) }
        val index = currentIndex() ?: p.segmentIndex
        loadWindow(index, player.currentPosition.takeIf { player.isCurrentMediaItemSeekable } ?: 0, true)
    }

    fun seekToSegment(index: Int, play: Boolean = true) {
        val t = text ?: return
        val target = index.coerceIn(0, t.segments.size - 1)
        val windowPos = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).segmentIndex() == target }
        _state.update { it.copy(error = null, finished = false) }
        if (windowPos != null && player.playerError == null) {
            player.seekTo(windowPos, 0)
            if (play) player.play()
        } else {
            loadWindow(target, 0, play || player.playWhenReady)
        }
    }

    fun next() {
        val i = currentIndex() ?: return
        if (i + 1 < (text?.segments?.size ?: 0)) seekToSegment(i + 1, player.playWhenReady)
    }

    fun previous() {
        val i = currentIndex() ?: return
        if (player.currentPosition > 4000 && player.isCurrentMediaItemSeekable) {
            player.seekTo(0)
        } else {
            seekToSegment((i - 1).coerceAtLeast(0), player.playWhenReady)
        }
    }

    /** Im Abschnitt spulen (nur wenn er vollständig vorliegt), sonst zum vorigen/nächsten Abschnitt. */
    fun skip(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) {
            if (deltaMs < 0) previous() else next()
            return
        }
        val pos = player.currentPosition + deltaMs
        val dur = player.duration
        when {
            pos < 0 -> if (player.currentPosition < 1500) previous() else player.seekTo(0)
            dur != C.TIME_UNSET && pos >= dur -> next()
            else -> player.seekTo(pos)
        }
    }

    /** Wiedergabe-Tempo eines Projekts ändern (gilt sofort, wenn es gerade geöffnet ist). */
    fun setSpeed(projectId: Long, speed: Float) {
        val isCurrent = project?.id == projectId
        if (isCurrent) {
            player.setPlaybackSpeed(speed)
            applyBufferTargets() // der Echtzeitfaktor hängt vom Tempo ab
        }
        scope.launch {
            val s = TtsSettings.decode(projects.get(projectId)?.settingsJson).copy(playbackSpeed = speed)
            projects.updateSettings(projectId, s)
            if (project?.id == projectId) project = projects.get(projectId)
        }
    }

    /** Für die Benachrichtigung: nach dem Ende von vorn beginnen, nach einem Fehler neu ansetzen. */
    fun restartIfEnded(): Boolean {
        if (player.playbackState == Player.STATE_ENDED && project != null) {
            loadWindow(0, 0, true); return true
        }
        return false
    }

    /** Einstellungen eines Projekts wurden geändert: falls es gerade läuft, mit den neuen Einstellungen fortfahren. */
    fun onSettingsChanged(projectId: Long) {
        if (project?.id != projectId) return
        scope.launch {
            val old = project
            val fresh = projects.get(projectId) ?: return@launch
            project = fresh
            val oldSettings = TtsSettings.decode(old?.settingsJson)
            val newSettings = TtsSettings.decode(fresh.settingsJson)
            player.setPlaybackSpeed(newSettings.playbackSpeed)
            if (oldSettings.copy(playbackSpeed = 1f) != newSettings.copy(playbackSpeed = 1f)) {
                val index = currentIndex() ?: fresh.segmentIndex
                loadWindow(index, 0, player.playWhenReady)
            }
        }
    }

    /** Projekt wurde gelöscht. */
    fun onProjectDeleted(projectId: Long) {
        if (project?.id != projectId) return
        windowJob?.cancel()
        dataSourceFactory.cancelAll()
        player.stop()
        player.clearMediaItems()
        project = null
        text = null
        _state.value = PlaybackState()
    }

    fun stop() {
        savePosition()
        player.pause()
    }

    // -- Hörprobe -------------------------------------------------------------------------------

    /** Kurze Hörprobe mit den angegebenen Einstellungen (z. B. beim Einstellen einer Stimme). */
    private var sampleJob: Job? = null

    fun playSample(sampleText: String, settings: TtsSettings) {
        player.pause()
        sampleJob?.cancel()
        val id = sampleIds.incrementAndGet()
        _sample.value = SampleState(loading = true)
        sampleJob = scope.launch {
            val body = try {
                withContext(Dispatchers.IO) { TtsRequest.build(sampleText, settings, voices.audioB64Blocking(settings.voice)) }
            } catch (e: Exception) {
                _sample.value = SampleState(error = e.message)
                return@launch
            }
            if (sampleIds.get() != id) return@launch
            samples.clear()
            samples[id] = body
            samplePlayer.setMediaItem(
                MediaItem.Builder().setUri(Uri.parse("tontaube://sample/$id")).setMimeType(MimeTypes.AUDIO_MPEG).build(),
            )
            samplePlayer.prepare()
            samplePlayer.play()
        }
    }

    fun stopSample() {
        sampleJob?.cancel()
        sampleJob = null
        if (samplePlayer.playbackState != Player.STATE_IDLE) samplePlayer.stop()
        _sample.value = SampleState()
    }

    // -- intern ---------------------------------------------------------------------------------

    private var windowJob: Job? = null

    private fun loadWindow(index: Int, positionMs: Long, play: Boolean) {
        val p = project ?: return
        val t = text ?: return
        if (t.segments.isEmpty()) return
        windowJob?.cancel()
        windowJob = scope.launch {
            // Datenbankzugriff (Fingerabdruck eigener Stimmen) nicht auf dem Hauptthread
            val cached = withContext(Dispatchers.IO) { projects.segmentFile(p, index).exists() }
            if (project?.id != p.id) return@launch
            applyWindow(p, t, index, if (cached) positionMs else 0, play)
        }
    }

    private fun applyWindow(p: ProjectEntity, t: SegmentedText, index: Int, positionMs: Long, play: Boolean) {
        val settings = TtsSettings.decode(p.settingsJson)
        val items = (index..minOf(index + ahead, t.segments.size - 1)).map { mediaItem(p, t, it) }
        // Laufende Erzeugungen des alten Fensters abbrechen, damit der Server frei wird
        dataSourceFactory.cancelAll()
        playedInWindow = false
        windowGeneration++
        ahead = windowSize(t, settings.playbackSpeed)
        _state.update { it.copy(error = null, finished = false) }
        player.setPlaybackSpeed(settings.playbackSpeed)
        player.setMediaItems(items, 0, positionMs)
        player.prepare()
        player.playWhenReady = play
        onSegmentChanged(index)
    }

    private fun onSegmentChanged(index: Int) {
        val p = project ?: return
        val t = text ?: return
        // Fenster nachfüllen und alte Einträge entfernen
        val current = player.currentMediaItemIndex
        if (current > 0) player.removeMediaItems(0, current)
        val lastInWindow = (player.mediaItemCount - 1).takeIf { it >= 0 }?.let { player.getMediaItemAt(it).segmentIndex() } ?: index
        var next = (lastInWindow ?: index) + 1
        while (next <= minOf(index + ahead, t.segments.size - 1)) {
            player.addMediaItem(mediaItem(p, t, next))
            next++
        }
        publish()
        savePosition(force = true)
    }

    private fun onFinished() {
        val p = project ?: return
        val t = text ?: return
        _state.update { it.copy(finished = true, isPlaying = false) }
        scope.launch { projects.updatePosition(p.id, t.segments.size - 1, 0, finished = true) }
    }

    private fun mediaItem(p: ProjectEntity, t: SegmentedText, index: Int): MediaItem {
        val chapter = t.chapterOf(index)?.title
        return MediaItem.Builder()
            .setMediaId("${p.id}/$index")
            .setUri(Uri.parse("tontaube://segment/${p.id}/$index"))
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(p.title)
                    .setArtist(chapter ?: p.author)
                    .setAlbumTitle("Abschnitt ${index + 1} von ${t.segments.size}")
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private fun currentIndex(): Int? = player.currentMediaItem?.segmentIndex()

    private fun MediaItem.segmentIndex(): Int? = mediaId.substringAfter('/', "").toIntOrNull()

    private fun publish() {
        val p = project
        val t = text
        val index = currentIndex() ?: p?.segmentIndex ?: 0
        _state.update {
            it.copy(
                projectId = p?.id,
                title = p?.title.orEmpty(),
                chapterTitle = t?.chapterOf(index)?.title,
                segmentIndex = index,
                segmentCount = t?.segments?.size ?: 0,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration,
                seekable = player.isCurrentMediaItemSeekable,
                speed = player.playbackParameters.speed,
                bufferedMs = player.totalBufferedDuration.coerceAtLeast(0),
                bufferTargetMs = (if (playedInWindow) loadControl.rebufferUs else loadControl.startBufferUs) / 1000,
                realtimeFactor = realtimeFactor(),
            )
        }
    }

    // -- Vorpuffer ------------------------------------------------------------------------------

    /** Servergeschwindigkeit bezogen auf das eingestellte Tempo (1,0 = Echtzeit). */
    private fun realtimeFactor(): Double? = rate.rate?.let { it / player.playbackParameters.speed.coerceAtLeast(0.25f) }

    private fun windowSize(t: SegmentedText, speed: Float): Int {
        val target = maxOf(
            BufferPolicy.targetSeconds(bufferMode, realtimeFactor(), start = true),
            BufferPolicy.targetSeconds(bufferMode, realtimeFactor(), start = false),
        )
        val avg = if (t.segments.isEmpty()) 450 else t.totalChars / t.segments.size
        return BufferPolicy.windowSegments(target, avg, speed)
    }

    /** Schwellen und Fenstergröße an Einstellung und Messung anpassen. */
    private fun applyBufferTargets() {
        val r = realtimeFactor()
        loadControl.startBufferUs = BufferPolicy.targetSeconds(bufferMode, r, start = true) * 1_000_000L
        loadControl.rebufferUs = BufferPolicy.targetSeconds(bufferMode, r, start = false) * 1_000_000L
        val t = text
        if (t != null && project != null && player.mediaItemCount > 0) {
            val wanted = windowSize(t, player.playbackParameters.speed)
            if (wanted > ahead) {
                ahead = wanted
                currentIndex()?.let { onSegmentChanged(it) } // Fenster nachfüllen
            } else {
                ahead = wanted
            }
        }
        publish()
    }

    /**
     * Misst jede Sekunde, wie viel Audio der Server erzeugt hat: Zuwachs des Puffers plus abgespielte Zeit.
     * Gezählt wird nur, solange tatsächlich vom Server gestreamt (nicht aus dem Speicher gelesen) wird.
     */
    private fun startMonitor() {
        scope.launch {
            var lastTime = 0L
            var lastBuffered = 0L
            var lastCacheOpens = -1L
            var lastGeneration = -1
            var lastPlaying = false
            var lastFactor: Double? = null
            while (isActive) {
                delay(1000)
                if (project == null) { lastCacheOpens = -1; continue }
                val now = android.os.SystemClock.elapsedRealtime()
                val buffered = player.totalBufferedDuration
                val cacheOpens = loadStats.cacheOpens.get()
                val playing = player.isPlaying
                val streaming = loadStats.networkStreams.get() > 0 && player.isLoading
                val valid = streaming && lastCacheOpens == cacheOpens && lastGeneration == windowGeneration &&
                    lastPlaying == playing && buffered < AdaptiveLoadControl.MAX_BUFFER_MS - 5_000
                if (valid) {
                    val dtMs = now - lastTime
                    val playedMs = if (playing) dtMs * player.playbackParameters.speed else 0f
                    val producedMs = (buffered - lastBuffered) + playedMs
                    if (producedMs >= 0) rate.add(producedMs / 1000.0, dtMs / 1000.0)
                }
                lastTime = now; lastBuffered = buffered; lastCacheOpens = cacheOpens
                lastGeneration = windowGeneration; lastPlaying = playing

                val factor = realtimeFactor()
                if (factor != null && (lastFactor == null || kotlin.math.abs(factor - lastFactor) > 0.05)) {
                    lastFactor = factor
                    applyBufferTargets()
                }
                rate.rate?.let { g ->
                    val id = serverId
                    if (id != null && now - lastPersistedRate > 30_000) {
                        lastPersistedRate = now
                        launch { prefs.setGenerationRate(id, g.toFloat()) }
                    }
                }
                if (player.playbackState == Player.STATE_BUFFERING) publish()
            }
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && player.isPlaying) {
                publish()
                if (System.currentTimeMillis() - lastSaved > 5000) savePosition()
                delay(400)
            }
            publish()
        }
    }

    private fun savePosition(force: Boolean = false) {
        val p = project ?: return
        val index = currentIndex() ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val pos = if (player.isCurrentMediaItemSeekable) player.currentPosition.coerceAtLeast(0) else 0
        if (!force && System.currentTimeMillis() - lastSaved < 500) return
        lastSaved = System.currentTimeMillis()
        scope.launch { projects.updatePosition(p.id, index, pos) }
    }

    /** Läuft auf dem Lade-Thread des Players. */
    private fun resolve(uri: Uri): SegmentSource {
        val server = servers.activeConfigBlocking()
        val parts = uri.pathSegments
        when (uri.host) {
            "sample" -> {
                val body = samples[parts.firstOrNull()?.toLongOrNull()] ?: throw TontaubeException("Hörprobe abgelaufen.")
                return SegmentSource(null, server, { body })
            }
            "segment" -> {
                val projectId = parts.getOrNull(0)?.toLongOrNull() ?: throw IOException("Ungültige Adresse $uri")
                val index = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Ungültige Adresse $uri")
                val p = projects.getBlocking(projectId) ?: throw TontaubeException("Projekt nicht gefunden.")
                val t = projects.text(projectId)
                val segment = t.segments.getOrNull(index) ?: throw TontaubeException("Abschnitt nicht gefunden.")
                val settings = TtsSettings.decode(p.settingsJson)
                val file = projects.segmentFile(p, index)
                return SegmentSource(file, server) {
                    TtsRequest.build(segment.text, settings, voices.audioB64Blocking(settings.voice))
                }
            }
            else -> throw IOException("Unbekannte Adresse $uri")
        }
    }

    private fun describe(error: PlaybackException): String {
        error.findCause<TontaubeException>()?.let { return it.message ?: "Fehler" }
        error.findCause<VoiceException>()?.let { return it.message ?: "Fehler" }
        error.findCause<IOException>()?.let { return TontaubeApi.describe(it) }
        return "Wiedergabefehler (${error.errorCodeName})."
    }

    companion object {
        const val SKIP_MS = 15_000L
    }
}

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var e: Throwable? = this
    while (e != null) {
        if (e is T) return e
        e = e.cause
    }
    return null
}
