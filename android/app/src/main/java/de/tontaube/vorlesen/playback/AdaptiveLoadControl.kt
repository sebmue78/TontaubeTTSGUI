package de.tontaube.vorlesen.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Lädt ohne Pause voraus (bis [MAX_BUFFER_MS]), damit der Server immer arbeitet, und startet bzw. setzt die
 * Wiedergabe erst fort, wenn [startBufferUs] bzw. nach einem Stocken [rebufferUs] Audio bereitliegen.
 * Die Schwellen setzt die [PlaybackEngine] je nach Einstellung und gemessener Servergeschwindigkeit.
 *
 * Bewusst eine Unterklasse statt Kotlin-Delegation: `by` leitet die Java-Default-Methoden von [LoadControl]
 * nicht weiter, deren Grundfassung wirft dann „not implemented“.
 */
@UnstableApi
class AdaptiveLoadControl : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    MAX_BUFFER_MS,
    MAX_BUFFER_MS,
    1_000,
    2_000,
    TARGET_BUFFER_BYTES,
    true,
    DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {

    @Volatile var startBufferUs: Long = 5_000_000L
    @Volatile var rebufferUs: Long = 30_000_000L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val needed = if (parameters.rebuffering) rebufferUs else startBufferUs
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return needed <= 0 || buffered >= needed
    }

    companion object {
        /** So weit wird höchstens vorausgeladen (15 Minuten Audio, als MP3 nur wenige MB). */
        const val MAX_BUFFER_MS = 15 * 60 * 1000
        const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024
    }
}
