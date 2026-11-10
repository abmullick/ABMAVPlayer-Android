package com.abm.avplayer.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.Flow

class MediaLibraryRepository(
    private val dao: MediaDao,
    private val resolver: ContentResolver
) {
    val library: Flow<List<MediaFileEntity>> = dao.observeLibrary()
    val playlists: Flow<List<PlaylistEntity>> = dao.observePlaylists()

    suspend fun importUris(uris: List<Uri>) {
        val now = System.currentTimeMillis()
        val entries = uris.distinctBy { it.toString() }.map { uri ->
            val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "Media file"
            val mime = resolver.getType(uri)
            MediaFileEntity(
                uri = uri.toString(),
                title = name,
                mimeType = mime,
                addedAt = now
            )
        }
        dao.insertMedia(entries)
    }

    suspend fun remove(uri: String) = dao.deleteMedia(uri)

    suspend fun recordPlayed(uri: String) {
        val current = dao.findMedia(uri) ?: return
        dao.upsertMedia(current.copy(lastPlayedAt = System.currentTimeMillis()))
    }

    suspend fun createPlaylist(name: String): Long =
        dao.createPlaylist(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun addToPlaylist(playlistId: Long, uri: String) {
        val position = dao.getPlaylistItems(playlistId).size
        dao.addPlaylistItem(PlaylistItemEntity(playlistId, uri, position))
    }

    suspend fun playlistUris(playlistId: Long): List<String> =
        dao.getPlaylistItems(playlistId).map { it.mediaUri }

    suspend fun deletePlaylist(playlistId: Long) = dao.deletePlaylist(playlistId)

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
