package com.nuvio.tv.music

import kotlinx.serialization.Serializable

/** One song or music video. [id] is its Deezer id when it came from Deezer, or its YouTube id. */
@Serializable
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val albumId: String = "",
    val thumbnail: String? = null,
    val durationSeconds: Int = 0,
    val badge: String? = null,
    /** Set when this track already points at a YouTube video. */
    val youtubeVideoId: String? = null,
    /** A downloaded local file, when one exists. */
    val localFile: String? = null,
) {
    val displayDuration: String
        get() {
            if (durationSeconds <= 0) return ""
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            return "$mins:${secs.toString().padStart(2, '0')}"
        }
}

@Serializable
data class MusicArtist(
    val id: String,
    val name: String,
    val pictureUrl: String? = null,
    val fanCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
data class MusicAlbum(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String = "",
    val coverUrl: String? = null,
    val releaseDate: String = "",
    val trackCount: Int = 0,
)

@Serializable
data class MusicPlaylist(
    val id: String,
    val title: String,
    val description: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val creatorName: String = "",
)

@Serializable
data class MusicGenre(
    val id: String,
    val name: String,
    val pictureUrl: String? = null,
)

data class MusicHomeData(
    val trending: List<MusicTrack> = emptyList(),
    val newReleases: List<MusicAlbum> = emptyList(),
    val artists: List<MusicArtist> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val genres: List<MusicGenre> = emptyList(),
    val youtubeVideos: List<MusicTrack> = emptyList(),
)

data class MusicSearchData(
    val tracks: List<MusicTrack> = emptyList(),
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

data class MusicArtistDetails(
    val artist: MusicArtist,
    val topTracks: List<MusicTrack> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val relatedArtists: List<MusicArtist> = emptyList(),
)

data class MusicAlbumDetails(val album: MusicAlbum, val tracks: List<MusicTrack> = emptyList())

data class MusicPlaylistDetails(val playlist: MusicPlaylist, val tracks: List<MusicTrack> = emptyList())

data class SyncedLyricLine(val timestampMs: Long, val text: String)

data class LyricsData(
    val trackId: String,
    val isSynced: Boolean,
    val plainLyrics: String,
    val syncedLines: List<SyncedLyricLine> = emptyList(),
) {
    val isEmpty: Boolean get() = plainLyrics.isBlank() && syncedLines.isEmpty()

    fun activeLineIndex(positionMs: Long): Int {
        if (!isSynced || syncedLines.isEmpty()) return -1
        var active = -1
        for ((index, line) in syncedLines.withIndex()) {
            if (positionMs >= line.timestampMs) active = index else break
        }
        return active
    }
}

@Serializable
data class UserMusicPlaylist(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val tracks: List<MusicTrack> = emptyList(),
)

@Serializable
data class DownloadedMusicTrack(
    val track: MusicTrack,
    val localPath: String,
    val downloadedAtMs: Long,
)

/** A resolved YouTube stream: a playable video URL plus the audio-only URL when one exists. */
data class MusicStream(
    val videoUrl: String,
    val audioUrl: String? = null,
    val title: String,
    val thumbnail: String? = null,
)

/** A YouTube search result, before it becomes a [MusicTrack]. */
data class YouTubeMusicVideo(
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    val durationSeconds: Int = 0,
)
