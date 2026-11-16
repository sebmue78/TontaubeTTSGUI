package de.tontaube.vorlesen

import android.app.Application
import androidx.media3.common.util.UnstableApi
import de.tontaube.vorlesen.data.AppDatabase
import de.tontaube.vorlesen.data.Preferences
import de.tontaube.vorlesen.data.ProjectRepository
import de.tontaube.vorlesen.data.ServerRepository
import de.tontaube.vorlesen.data.VoiceRepository
import de.tontaube.vorlesen.net.TontaubeApi
import de.tontaube.vorlesen.playback.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Einfache Abhängigkeitsverwaltung ohne Framework. */
@UnstableApi
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = AppDatabase.create(app)
    val prefs = Preferences(app)
    val api = TontaubeApi()
    val voices = VoiceRepository(app, db)
    val projects = ProjectRepository(app, db, prefs, voices)
    val servers = ServerRepository(db, prefs, api, scope)
    val engine by lazy { PlaybackEngine(app, projects, voices, servers, prefs, api, scope) }
}

@UnstableApi
class TontaubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.servers.refresh()
    }
}
