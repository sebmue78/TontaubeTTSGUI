package de.tontaube.vorlesen.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.tontaube.core.ControlSpec
import de.tontaube.core.TontaubeJson
import de.tontaube.core.TtsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.dataStore by preferencesDataStore("einstellungen")

/** Einstellungen der App (aktiver Server, Standard-Einstellungen für neue Projekte, Regler des Modells). */
class Preferences(private val context: Context) {
    private val activeServerKey = longPreferencesKey("active_server")
    private val defaultsKey = stringPreferencesKey("default_settings")
    private val controlsKey = stringPreferencesKey("controls")
    private val themeKey = stringPreferencesKey("theme")

    val activeServerId: Flow<Long?> = context.dataStore.data.map { it[activeServerKey] }
    val defaults: Flow<TtsSettings> = context.dataStore.data.map { TtsSettings.decode(it[defaultsKey]) }
    val controls: Flow<List<ControlSpec>> = context.dataStore.data.map { prefs ->
        prefs[controlsKey]?.let { runCatching { TontaubeJson.decodeFromString(ListSerializer(ControlSpec.serializer()), it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: ControlSpec.defaults
    }

    /** "system", "light" oder "dark". */
    val theme: Flow<String> = context.dataStore.data.map { it[themeKey] ?: "system" }

    suspend fun activeServerIdNow(): Long? = activeServerId.first()

    suspend fun setActiveServer(id: Long?) {
        context.dataStore.edit { if (id == null) it.remove(activeServerKey) else it[activeServerKey] = id }
    }

    suspend fun setDefaults(s: TtsSettings) {
        context.dataStore.edit { it[defaultsKey] = s.encode() }
    }

    suspend fun setControls(specs: List<ControlSpec>) {
        context.dataStore.edit { it[controlsKey] = TontaubeJson.encodeToString(ListSerializer(ControlSpec.serializer()), specs) }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[themeKey] = theme }
    }
}
