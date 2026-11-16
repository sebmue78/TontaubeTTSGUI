package de.tontaube.vorlesen.net

import de.tontaube.core.ControlSpec
import de.tontaube.core.ModelSpec
import de.tontaube.core.ServerErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Fehler mit verständlicher Meldung für die Oberfläche. [retryable]: Netzwerkproblem, erneut versuchen lohnt sich. */
class TontaubeException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

data class ServerConfig(val url: String, val apiKey: String?)

/** Ergebnis eines Verbindungstests. */
sealed interface ServerStatus {
    data object Unknown : ServerStatus
    data object Checking : ServerStatus
    /** [models]: Modelle laut `GET /models` (leer = reiner Tontaube-Server ohne Modell-Gateway). */
    data class Ready(val controls: List<ControlSpec>, val models: List<ModelSpec> = emptyList()) : ServerStatus
    data object Loading : ServerStatus
    data class Error(val message: String) : ServerStatus
}

/** HTTP-Zugriff auf den Tontaube-Server (v1.1.0). */
class TontaubeApi {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Die Erzeugung eines Abschnitts kann bei ausgelastetem Server dauern.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val quick = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()

    private fun request(server: ServerConfig, path: String) = Request.Builder()
        .url(server.url.trimEnd('/') + path)
        .apply { server.apiKey?.takeIf { it.isNotBlank() }?.let { header("x-api-key", it) } }

    fun streamCall(server: ServerConfig, jsonBody: String): Call = http.newCall(
        request(server, "/stream")
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody(JSON))
            .build(),
    )

    suspend fun check(server: ServerConfig): ServerStatus = withContext(Dispatchers.IO) {
        try {
            quick.newCall(request(server, "/readyz").build()).execute().use { r ->
                if (r.code == 503) return@withContext ServerStatus.Loading
                if (!r.isSuccessful) return@withContext ServerStatus.Error("Unerwartete Antwort (HTTP ${r.code}). Ist das ein Tontaube-Server?")
            }
            // /controls ist geschützt: prüft zugleich den API-Key
            val status = quick.newCall(request(server, "/controls").build()).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> ServerStatus.Error("API-Key fehlt oder ist falsch.")
                    r.code == 503 -> ServerStatus.Loading
                    !r.isSuccessful -> ServerStatus.Error(ServerErrors.describe(r.code, r.body?.string()))
                    else -> ServerStatus.Ready(runCatching { ControlSpec.parse(r.body!!.string()) }.getOrDefault(emptyList()))
                }
            }
            if (status is ServerStatus.Ready) status.copy(models = models(server)) else status
        } catch (e: IOException) {
            ServerStatus.Error(describe(e))
        }
    }

    /** Modelle des Servers. Ohne Modell-Gateway (404) oder bei Fehlern: leere Liste. */
    private fun models(server: ServerConfig): List<ModelSpec> = runCatching {
        quick.newCall(request(server, "/models").build()).execute().use { r ->
            if (r.isSuccessful) ModelSpec.parse(r.body!!.string()) else emptyList()
        }
    }.getOrDefault(emptyList())

    companion object {
        val JSON = "application/json".toMediaType()

        fun describe(e: Throwable): String = when (e) {
            is TontaubeException -> e.message ?: "Fehler"
            is UnknownHostException -> "Adresse nicht gefunden. Ist das Handy im selben WLAN?"
            is ConnectException -> "Keine Verbindung. Läuft der Server und ist er im Netzwerk freigegeben?"
            is SocketTimeoutException -> "Zeitüberschreitung. Der Server antwortet nicht."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
