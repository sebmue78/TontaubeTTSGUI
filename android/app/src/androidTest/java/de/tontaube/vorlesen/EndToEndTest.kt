package de.tontaube.vorlesen

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ComposeTimeoutException
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

    /** Modellauswahl: Der Server (Modell-Gateway im Testmodus) bietet Qwen3 an, ein Projekt mit Qwen3 wird gestreamt. */
    @Test
    fun otherModelFromGateway() {
        val c = container
        val projectId = runBlocking {
            val sid = c.servers.save("Mock-Server", "http://10.0.2.2:8080", "testkey")
            c.servers.setActive(sid)
            val id = c.projects.importText("Qwen-Probe", "Ein kurzer Satz [laugh] zum Testen. Und noch einer, damit es reicht.")
            c.projects.updateSettings(id, de.tontaube.core.TtsSettings(model = de.tontaube.core.Models.QWEN3, instruct = "ruhig"))
            id
        }
        waitFor("Server bereit", 15_000) { c.servers.status.value is de.tontaube.vorlesen.net.ServerStatus.Ready }
        val models = (c.servers.status.value as de.tontaube.vorlesen.net.ServerStatus.Ready).models.map { it.id }
        assertTrue("Modelle: $models", "qwen3" in models && "chatterbox" in models)

        waitFor("Projekt in der Bibliothek", 15_000) { count("Qwen-Probe") > 0 }
        compose.onAllNodes(hasText("Qwen-Probe")).onFirst().performClick()
        waitFor("Projekt offen", 10_000) { compose.onAllNodes(hasContentDescription("Vorlesen")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription("Vorlesen")).onFirst().performClick()
        waitFor("Wiedergabe mit Qwen3", 60_000) { c.engine.state.value.let { it.isPlaying || it.error != null } }
        assertTrue("Fehler: ${c.engine.state.value}", c.engine.state.value.error == null)
        compose.onAllNodes(hasContentDescription("Pause")).onFirst().performClick()

        compose.onAllNodes(hasContentDescription("Stimme und Einstellungen")).onFirst().performClick()
        waitFor("Modellauswahl sichtbar", 10_000) { count("Modell") > 0 && count("Sprecher") > 0 }
        screenshot("07-modell-qwen3")
        // Aufräumen: Auf dem kleinen Emulator-Bildschirm würde ein zweites Projekt in der Liste unter dem
        // Import-Knopf landen und den anderen Test stören.
        runBlocking { c.projects.delete(projectId) }
    }

    /** Wartet auf [cond]; bei Zeitüberschreitung schlägt der Test mit [what] und dem aktuellen Semantikbaum fehl. */
    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { cond() }
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { compose.onRoot().printToString(maxDepth = Int.MAX_VALUE) }.getOrDefault("?")
            throw AssertionError("Zeitüberschreitung: $what\n$tree", e)
        }
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /** Tabwechsel (Bildschirm erkennbar am Titel; der Tab-Name steht zusätzlich in der Leiste): Einstellungen → Bibliothek → Stimmen → Bibliothek muss jeweils den richtigen Bildschirm zeigen. */
    @Test
    fun bottomNavigationSwitchesTabs() {
        fun tab(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()

        waitFor("Start", 10_000) { count("Bibliothek") >= 2 }
        repeat(2) { round ->
            tab("Einstellungen"); waitFor("Einstellungen offen ($round)") { count("Einstellungen") >= 2 && count("Bibliothek") == 1 }
            tab("Bibliothek"); waitFor("zurück in der Bibliothek ($round)") { count("Bibliothek") >= 2 && count("Einstellungen") == 1 }
            tab("Stimmen"); waitFor("Stimmen offen ($round)") { count("Stimmen") >= 2 && count("Bibliothek") == 1 }
            tab("Bibliothek"); waitFor("wieder in der Bibliothek ($round)") { count("Bibliothek") >= 2 && count("Stimmen") == 1 }
        }
        screenshot("06-tabs")
    }
}
