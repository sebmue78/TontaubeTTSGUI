package de.tontaube.vorlesen.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.tontaube.core.ConnectLink
import de.tontaube.core.DocumentException
import de.tontaube.vorlesen.AppContainer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@UnstableApi
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("Kein AppContainer") }

/** Ereignisse von außen (Intents). */
sealed interface AppEvent {
    data object OpenPlayer : AppEvent
    data class Connect(val link: ConnectLink) : AppEvent
    data class Import(val uri: Uri, val mime: String?) : AppEvent
    data class ImportText(val title: String, val text: String) : AppEvent
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Bibliothek", Icons.AutoMirrored.Rounded.LibraryBooks),
    Tab("voices", "Stimmen", Icons.Rounded.RecordVoiceOver),
    Tab("settings", "Einstellungen", Icons.Rounded.Settings),
)

/** Zustand eines laufenden Imports (für den Fortschrittsdialog). */
class ImportState {
    var busy by mutableStateOf<String?>(null)
}

@UnstableApi
@Composable
fun MainScreen(events: StateFlow<AppEvent?>, onEventHandled: () -> Unit) {
    val container = LocalContainer.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importState = remember { ImportState() }
    var connectLink by remember { mutableStateOf<ConnectLink?>(null) }
    val event by events.collectAsStateWithLifecycle()
    val playback by container.engine.state.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun importUri(uri: Uri, mime: String?) {
        scope.launch {
            importState.busy = "Dokument wird gelesen …"
            try {
                val result = container.projects.import(uri, mime)
                nav.navigate("project/${result.projectId}")
                snackbar.showSnackbar("„${result.doc.title}“ importiert: ${result.doc.chapters.size} Kapitel")
            } catch (e: DocumentException) {
                snackbar.showSnackbar(e.message ?: "Import fehlgeschlagen")
            } catch (e: Exception) {
                snackbar.showSnackbar("Import fehlgeschlagen: ${e.message}")
            } finally {
                importState.busy = null
            }
        }
    }

    LaunchedEffect(event) {
        when (val e = event) {
            null -> return@LaunchedEffect
            AppEvent.OpenPlayer -> playback.projectId?.let { nav.navigate("project/$it") { launchSingleTop = true } }
            is AppEvent.Connect -> connectLink = e.link
            is AppEvent.Import -> importUri(e.uri, e.mime)
            is AppEvent.ImportText -> {
                try {
                    val id = container.projects.importText(e.title, e.text)
                    nav.navigate("project/$id")
                } catch (ex: DocumentException) {
                    snackbar.showSnackbar(ex.message ?: "Import fehlgeschlagen")
                }
            }
        }
        onEventHandled()
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTab = tabs.any { it.route == route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (isTab) {
                Column {
                    AnimatedVisibility(
                        visible = playback.hasProject,
                        enter = slideInVertically { it }, exit = slideOutVertically { it },
                    ) {
                        MiniPlayer(onOpen = { playback.projectId?.let { nav.navigate("project/$it") } })
                    }
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo("library") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = if (isTab) padding.calculateBottomPadding() else 0.dp)) {
            AppNavHost(nav, snackbar, onImport = ::importUri)
        }
    }

    importState.busy?.let { BusyDialog(it) }
    connectLink?.let { link ->
        ServerDialog(
            initialName = link.name.orEmpty(),
            initialUrl = link.url,
            initialKey = link.apiKey.orEmpty(),
            onDismiss = { connectLink = null },
            onSaved = {
                connectLink = null
                scope.launch { snackbar.showSnackbar("Server „${it}“ gespeichert") }
            },
        )
    }
}

@UnstableApi
@Composable
private fun AppNavHost(nav: NavHostController, snackbar: SnackbarHostState, onImport: (Uri, String?) -> Unit) {
    NavHost(nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                snackbar = snackbar,
                onOpenProject = { nav.navigate("project/$it") },
                onImport = onImport,
                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
            )
        }
        composable("voices") { VoicesScreen(snackbar) }
        composable("settings") { SettingsScreen(snackbar) }
        composable("project/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ProjectScreen(
                projectId = entry.arguments!!.getLong("id"),
                snackbar = snackbar,
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
            )
        }
    }
}

@UnstableApi
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val engine = LocalContainer.current.engine
    val s by engine.state.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            val progress = if (s.segmentCount > 0) (s.segmentIndex + 1f) / s.segmentCount else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        s.error ?: listOfNotNull(s.chapterTitle, "Abschnitt ${s.segmentIndex + 1}/${s.segmentCount}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = { engine.togglePlay() }) {
                        Icon(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (s.isPlaying) "Pause" else "Abspielen")
                    }
                    if (s.isBuffering) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
                }
                IconButton(onClick = { engine.next() }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Nächster Abschnitt") }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
