package de.tontaube.vorlesen.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.tontaube.vorlesen.MainActivity
import de.tontaube.vorlesen.TontaubeApp

/**
 * Hintergrund-Wiedergabe mit Benachrichtigung und Sperrbildschirm-Steuerung. Der Player selbst gehört
 * der [PlaybackEngine]; „Weiter“/„Zurück“ springen abschnittsweise.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as TontaubeApp).container.engine
        val player = object : ForwardingPlayer(engine.player) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()

            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun seekToNext() = engine.next()
            override fun seekToNextMediaItem() = engine.next()
            override fun seekToPrevious() = engine.previous()
            override fun seekToPreviousMediaItem() = engine.previous()
            override fun seekBack() = engine.skip(-PlaybackEngine.SKIP_MS)
            override fun seekForward() = engine.skip(PlaybackEngine.SKIP_MS)
            override fun play() = engine.resume()
            override fun seekToDefaultPosition() {
                if (!engine.restartIfEnded()) super.seekToDefaultPosition()
            }
            override fun prepare() {
                if (engine.player.playerError != null) engine.retry() else super.prepare()
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            (application as TontaubeApp).container.engine.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release() // Der Player gehört der Engine und bleibt bestehen.
        session = null
        super.onDestroy()
    }
}
