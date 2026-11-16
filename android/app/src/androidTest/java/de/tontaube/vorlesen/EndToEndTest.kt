package de.tontaube.vorlesen

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Ende-zu-Ende-Test gegen den Mock-Server (tools/mock_server.py, vom Emulator aus unter 10.0.2.2:8080):
 * Server eintragen, Text importieren, Projekt öffnen, vorlesen, pausieren, Position und Zwischenspeicher prüfen.
 * Bildschirmfotos landen in files/screens (die CI holt sie ab).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant(),
    ).around(compose)

    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TontaubeApp).container

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun importStreamPauseResume() {
        val c = container
        val text = buildString {
            append("Kapitel 1: Der Anfang\n\n")
            repeat(4) { append("Es war einmal ein kleiner Leuchtturm am Rand der Welt. Jede Nacht schickte er sein Licht über das Meer, damit die Schiffe sicher nach Hause fanden. Satz Nummer $it.\n\n") }
            append("Kapitel 2: Das Ende\n\n")
            repeat(3) { append("Und wenn sie nicht gestorben sind, dann leuchtet er noch heute. Absatz $it.\n\n") }
        }
        val projectId = runBlocking {
            val sid = c.servers.save("Mock-Server", "http://10.0.2.2:8080", "testkey")
            c.servers.setActive(sid)
            c.projects.importText("Der kleine Leuchtturm", text)
        }

        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Der kleine Leuchtturm")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { c.servers.status.value is de.tontaube.vorlesen.net.ServerStatus.Ready }
        screenshot("01-bibliothek")

        compose.onAllNodes(hasText("Der kleine Leuchtturm")).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Vorlesen")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("02-projekt")

        compose.onAllNodes(hasContentDescription("Vorlesen")).onFirst().performClick()
        try {
            compose.waitUntil(60_000) { c.engine.state.value.let { it.isPlaying || it.error != null } }
        } finally {
            screenshot("03-wiedergabe")
            android.util.Log.i("E2E", "Zustand: ${c.engine.state.value} playbackState=${compose.runOnUiThread { c.engine.player.playbackState }}")
        }
        assertTrue("Fehler: ${c.engine.state.value}", c.engine.state.value.isPlaying && c.engine.state.value.error == null)

        // Erster Abschnitt muss vollständig im Zwischenspeicher landen
        compose.waitUntil(60_000) {
            File(c.projects.projectDir(projectId), "audio").walkTopDown().any { it.name == "0.mp3" && it.length() > 0 }
        }

        // Zum nächsten Abschnitt springen, pausieren, Position muss gespeichert sein
        compose.onAllNodes(hasContentDescription("Nächster Abschnitt")).onFirst().performClick()
        compose.waitUntil(30_000) { c.engine.state.value.segmentIndex == 1 && c.engine.state.value.isPlaying }
        compose.onAllNodes(hasContentDescription("Pause")).onFirst().performClick()
        compose.waitUntil(10_000) { !c.engine.state.value.isPlaying }
        compose.waitUntil(10_000) { runBlocking { c.projects.get(projectId)!!.segmentIndex } == 1 }
        screenshot("04-pausiert")

        // Einstellungen öffnen (Stimme, Regler)
        compose.onAllNodes(hasContentDescription("Stimme und Einstellungen")).onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Stil-Regler")).fetchSemanticsNodes().isNotEmpty() }
        screenshot("05-einstellungen")
    }

    /** Tabwechsel: Einstellungen → Bibliothek → Stimmen → Bibliothek muss jeweils den richtigen Bildschirm zeigen. */
    @Test
    fun bottomNavigationSwitchesTabs() {
        fun tab(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()
        fun shows(title: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(title)).fetchSemanticsNodes().size >= 2 }

        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Bibliothek")).fetchSemanticsNodes().isNotEmpty() }
        repeat(2) {
            tab("Einstellungen"); shows("Einstellungen")
            tab("Bibliothek"); compose.waitUntil(5_000) { compose.onAllNodes(hasText("Importieren")).fetchSemanticsNodes().isNotEmpty() }
            // Nur noch die Beschriftung in der Leiste – der Einstellungen-Bildschirm muss weg sein
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Einstellungen")).fetchSemanticsNodes().size == 1 }
            tab("Stimmen"); shows("Stimmen")
            tab("Bibliothek"); compose.waitUntil(5_000) { compose.onAllNodes(hasText("Importieren")).fetchSemanticsNodes().isNotEmpty() }
        }
        screenshot("06-tabs")
    }
}
