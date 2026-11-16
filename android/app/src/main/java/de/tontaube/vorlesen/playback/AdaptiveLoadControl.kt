package de.tontaube.vorlesen.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Lädt ohne Pause voraus (bis [MAX_BUFFER_MS]), damit der Server immer arbeitet, und startet bzw. setzt die
 * Wiedergabe erst fort, wenn [startBufferUs] bzw. nach einem Stocken [rebufferUs] Audio bereitliegen.
 * Die Schwellen setzt die [PlaybackEngine] je nach Einstellung und gemessener Servergeschwindigkeit.
 */
@UnstableApi
class AdaptiveLoadControl(
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MAX_BUFFER_MS, MAX_BUFFER_MS, 1_000, 2_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .build(),
) : LoadControl by delegate {

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
