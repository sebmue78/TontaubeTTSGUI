package de.tontaube.vorlesen.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Ein Vorlese-Projekt. Der gegliederte Text liegt als JSON-Datei im Projektordner. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val format: String,
    val sourceName: String?,
    val createdAt: Long,
    val lastPlayedAt: Long? = null,
    val settingsJson: String,
    val segmentIndex: Int = 0,
    val positionMs: Long = 0,
    val segmentCount: Int,
    val charCount: Int,
    val progress: Float = 0f,
    val finished: Boolean = false,
)

/** Eigene Stimme: Referenzaudio (5–60 s) auf dem Handy. */
@Entity(tableName = "voices")
data class VoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val language: String,
    val fileName: String,
    val durationMs: Long,
    val fingerprint: String,
    val createdAt: Long,
    val note: String? = null,
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val apiKey: String?,
    val lastSeenAt: Long? = null,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY COALESCE(lastPlayedAt, createdAt) DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getBlocking(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Query("UPDATE projects SET segmentIndex = :index, positionMs = :positionMs, progress = :progress, finished = :finished, lastPlayedAt = :now WHERE id = :id")
    suspend fun updatePosition(id: Long, index: Int, positionMs: Long, progress: Float, finished: Boolean, now: Long)

    @Query("UPDATE projects SET settingsJson = :json WHERE id = :id")
    suspend fun updateSettings(id: Long, json: String)

    @Query("UPDATE projects SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM projects")
    suspend fun ids(): List<Long>

    @Query("SELECT * FROM projects WHERE settingsJson LIKE '%' || :voiceRef || '\"%'")
    suspend fun usingVoice(voiceRef: String): List<ProjectEntity>
}

@Dao
interface VoiceDao {
    @Query("SELECT * FROM voices ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VoiceEntity>>

    @Query("SELECT * FROM voices WHERE id = :id")
    suspend fun get(id: Long): VoiceEntity?

    @Query("SELECT * FROM voices WHERE id = :id")
    fun getBlocking(id: Long): VoiceEntity?

    @Insert
    suspend fun insert(v: VoiceEntity): Long

    @Update
    suspend fun update(v: VoiceEntity)

    @Delete
    suspend fun delete(v: VoiceEntity)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE id = :id")
    fun getBlocking(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ServerEntity?

    @Insert
    suspend fun insert(s: ServerEntity): Long

    @Update
    suspend fun update(s: ServerEntity)

    @Query("UPDATE servers SET lastSeenAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Delete
    suspend fun delete(s: ServerEntity)
}

@Database(entities = [ProjectEntity::class, VoiceEntity::class, ServerEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun voices(): VoiceDao
    abstract fun servers(): ServerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tontaube.db").build()
    }
}
