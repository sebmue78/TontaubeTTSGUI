package de.tontaube.vorlesen.data

import de.tontaube.core.ConnectLink
import de.tontaube.vorlesen.net.ServerConfig
import de.tontaube.vorlesen.net.ServerStatus
import de.tontaube.vorlesen.net.TontaubeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Gespeicherte Server und der aktive Server samt Verbindungsstatus. */
class ServerRepository(
    db: AppDatabase,
    private val prefs: Preferences,
    private val api: TontaubeApi,
    private val scope: CoroutineScope,
) {
    private val dao = db.servers()
    val servers: Flow<List<ServerEntity>> = dao.observeAll()

    val active: StateFlow<ServerEntity?> = combine(prefs.activeServerId, dao.observeAll()) { id, list ->
        list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val status: StateFlow<ServerStatus> = _status

    /** Konfiguration für Anfragen (vom Wiedergabe-Thread aus aufrufbar). */
    fun activeConfigBlocking(): ServerConfig? = active.value?.let { ServerConfig(it.url, it.apiKey) }

    suspend fun save(name: String, url: String, apiKey: String?, id: Long? = null): Long {
        val normalized = ConnectLink.normalizeUrl(url) ?: throw IllegalArgumentException("Ungültige Adresse")
        val entity = ServerEntity(
            id = id ?: 0,
            name = name.trim().ifEmpty { normalized.substringAfter("://").substringBefore(':') },
            url = normalized,
            apiKey = apiKey?.trim()?.ifEmpty { null },
        )
        val savedId = if (id != null && id != 0L) {
            dao.update(entity); id
        } else {
            dao.byUrl(normalized)?.let { existing -> dao.update(entity.copy(id = existing.id)); existing.id } ?: dao.insert(entity)
        }
        if (prefs.activeServerIdNow() == null || id == null) prefs.setActiveServer(savedId)
        return savedId
    }

    suspend fun saveLink(link: ConnectLink): Long = save(link.name ?: "", link.url, link.apiKey)

    suspend fun delete(server: ServerEntity) {
        dao.delete(server)
        if (prefs.activeServerIdNow() == server.id) {
            prefs.setActiveServer(dao.observeAll().first().firstOrNull()?.id)
        }
    }

    suspend fun setActive(id: Long) {
        prefs.setActiveServer(id)
        refresh()
    }

    suspend fun test(url: String, apiKey: String?): ServerStatus {
        val normalized = ConnectLink.normalizeUrl(url) ?: return ServerStatus.Error("Ungültige Adresse")
        return api.check(ServerConfig(normalized, apiKey?.trim()?.ifEmpty { null }))
    }

    /** Status des aktiven Servers prüfen und die Regler des Modells übernehmen. */
    fun refresh() {
        scope.launch {
            val id = prefs.activeServerIdNow()
            val server = id?.let { dao.get(it) }
            if (server == null) {
                _status.value = ServerStatus.Unknown
                return@launch
            }
            _status.value = ServerStatus.Checking
            val result = api.check(ServerConfig(server.url, server.apiKey))
            _status.value = result
            if (result is ServerStatus.Ready) {
                dao.touch(server.id, System.currentTimeMillis())
                if (result.controls.isNotEmpty()) prefs.setControls(result.controls)
            }
        }
    }
}
