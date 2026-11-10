package com.abm.avplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_files")
data class MediaFileEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val mimeType: String?,
    val addedAt: Long,
    val lastPlayedAt: Long = 0L
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaUri"]
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val mediaUri: String,
    val position: Int
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_files ORDER BY title COLLATE NOCASE")
    fun observeLibrary(): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE uri = :uri LIMIT 1")
    suspend fun findMedia(uri: String): MediaFileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMedia(items: List<MediaFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(item: MediaFileEntity)

    @Query("DELETE FROM media_files WHERE uri = :uri")
    suspend fun deleteMedia(uri: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPlaylistItem(item: PlaylistItemEntity)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getPlaylistItems(playlistId: Long): List<PlaylistItemEntity>

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)
}

@Database(
    entities = [MediaFileEntity::class, PlaylistEntity::class, PlaylistItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile private var INSTANCE: MediaDatabase? = null

        fun get(context: Context): MediaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "abm_media_library.db"
                ).build().also { INSTANCE = it }
            }
    }
}
