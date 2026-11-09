package com.nuvio.tv.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class MusicLibraryPayload(
    val favorites: List<MusicTrack> = emptyList(),
    val favoriteArtists: List<MusicArtist> = emptyList(),
    val favoriteAlbums: List<MusicAlbum> = emptyList(),
    val playlists: List<UserMusicPlaylist> = emptyList(),
    val downloads: List<DownloadedMusicTrack> = emptyList(),
)

/** Liked songs, saved artists/albums, custom playlists and downloaded tracks. */
@Singleton
class MusicLibraryRepository @Inject constructor(
    private val platform: MusicPlatform,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _favorites = MutableStateFlow<List<MusicTrack>>(emptyList())
    val favorites: StateFlow<List<MusicTrack>> = _favorites.asStateFlow()

    private val _favoriteArtists = MutableStateFlow<List<MusicArtist>>(emptyList())
    val favoriteArtists: StateFlow<List<MusicArtist>> = _favoriteArtists.asStateFlow()

    private val _favoriteAlbums = MutableStateFlow<List<MusicAlbum>>(emptyList())
    val favoriteAlbums: StateFlow<List<MusicAlbum>> = _favoriteAlbums.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserMusicPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserMusicPlaylist>> = _playlists.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadedMusicTrack>>(emptyList())
    val downloads: StateFlow<List<DownloadedMusicTrack>> = _downloads.asStateFlow()

    init {
        val payload = runCatching {
            platform.loadLibrary()?.let { json.decodeFromString<MusicLibraryPayload>(it) }
        }.getOrNull() ?: MusicLibraryPayload()
        _favorites.value = payload.favorites
        _favoriteArtists.value = payload.favoriteArtists
        _favoriteAlbums.value = payload.favoriteAlbums
        _playlists.value = payload.playlists
        _downloads.value = payload.downloads
    }

    private fun persist() {
        runCatching {
            platform.saveLibrary(
                json.encodeToString(
                    MusicLibraryPayload(
                        favorites = _favorites.value,
                        favoriteArtists = _favoriteArtists.value,
                        favoriteAlbums = _favoriteAlbums.value,
                        playlists = _playlists.value,
                        downloads = _downloads.value,
                    ),
                ),
            )
        }
    }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun toggleFavorite(track: MusicTrack) {
        _favorites.value = if (isFavorite(track.id)) {
            _favorites.value.filterNot { it.id == track.id }
        } else {
            listOf(track) + _favorites.value
        }
        persist()
    }

    fun isArtistFavorite(id: String): Boolean = _favoriteArtists.value.any { it.id == id }

    fun toggleFavorite(artist: MusicArtist) {
        _favoriteArtists.value = if (isArtistFavorite(artist.id)) {
            _favoriteArtists.value.filterNot { it.id == artist.id }
        } else {
            listOf(artist) + _favoriteArtists.value
        }
        persist()
    }

    fun isAlbumFavorite(id: String): Boolean = _favoriteAlbums.value.any { it.id == id }

    fun toggleFavorite(album: MusicAlbum) {
        _favoriteAlbums.value = if (isAlbumFavorite(album.id)) {
            _favoriteAlbums.value.filterNot { it.id == album.id }
        } else {
            listOf(album) + _favoriteAlbums.value
        }
        persist()
    }

    fun createPlaylist(title: String): UserMusicPlaylist {
        val playlist = UserMusicPlaylist(
            id = "local-${System.currentTimeMillis()}",
            title = title.trim().ifBlank { "My Playlist" },
            createdAtMs = System.currentTimeMillis(),
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persist()
        return playlist
    }

    fun addToPlaylist(playlistId: String, track: MusicTrack) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId || playlist.tracks.any { it.id == track.id }) playlist
            else playlist.copy(tracks = playlist.tracks + track)
        }
        persist()
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) playlist
            else playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId })
        }
        persist()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persist()
    }

    fun downloadFor(trackId: String): DownloadedMusicTrack? =
        _downloads.value.firstOrNull { it.track.id == trackId && platform.fileExists(it.localPath) }

    fun addDownloaded(track: MusicTrack, localPath: String) {
        val entry = DownloadedMusicTrack(track, localPath, System.currentTimeMillis())
        _downloads.value = listOf(entry) + _downloads.value.filterNot { it.track.id == track.id }
        persist()
    }

    fun removeDownloaded(trackId: String) {
        _downloads.value.firstOrNull { it.track.id == trackId }?.let { platform.deleteFile(it.localPath) }
        _downloads.value = _downloads.value.filterNot { it.track.id == trackId }
        persist()
    }

    /** A track with its downloaded file attached when one exists. */
    fun withDownload(track: MusicTrack): MusicTrack =
        downloadFor(track.id)?.let { track.copy(localFile = it.localPath) } ?: track
}
