package de.tontaube.vorlesen

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.tontaube.core.ConnectLink
import de.tontaube.vorlesen.playback.PlaybackService
import de.tontaube.vorlesen.ui.AppEvent
import de.tontaube.vorlesen.ui.LocalContainer
import de.tontaube.vorlesen.ui.MainScreen
import de.tontaube.vorlesen.ui.TontaubeTheme
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val events = MutableStateFlow<AppEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TontaubeApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme by container.prefs.theme.collectAsStateWithLifecycle("system")
            CompositionLocalProvider(LocalContainer provides container) {
                TontaubeTheme(theme) {
                    MainScreen(events = events, onEventHandled = { events.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false) -> events.value = AppEvent.OpenPlayer
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "tontaube" -> {
                ConnectLink.parse(intent.data.toString())?.let { events.value = AppEvent.Connect(it) }
            }
            intent.action == Intent.ACTION_VIEW && intent.data != null -> events.value = AppEvent.Import(intent.data!!, intent.type)
            intent.action == Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> events.value = AppEvent.Import(stream, intent.type)
                    !text.isNullOrBlank() -> events.value = AppEvent.ImportText(
                        intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: text.lineSequence().first().take(60),
                        text,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Verbindung zum Wiedergabe-Dienst halten (Benachrichtigung, Hintergrund-Wiedergabe)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
