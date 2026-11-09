package com.nuvio.tv.music

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Music discovery: a public catalogue for metadata (tracks, artists, albums, playlists, artwork,
 * charts and genres) and YouTube for the actual video/audio stream. Lyrics come from LRCLIB.
 */
@Singleton
class MusicRepository @Inject constructor(
    private val deezer: DeezerClient,
    private val lyrics: LyricsRepository,
    private val youtube: YouTubeMusicService,
    private val library: MusicLibraryRepository,
) {
    private val homeLock = Mutex()
    private var homeCache: MusicHomeData? = null
    private var homeCachedAt = 0L

    private data class SearchEntry(val atMs: Long, val data: MusicSearchData)
    private val searchCache = object : LinkedHashMap<String, SearchEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchEntry>?): Boolean = size > 30
    }
    private val searchLock = Mutex()

    /** Catalogue track id -> YouTube video id, so a song only needs one lookup. */
    private val youtubeIds = java.util.Collections.synchronizedMap(HashMap<String, String>())

    suspend fun home(force: Boolean = false): MusicHomeData {
        val now = System.currentTimeMillis()
        homeLock.withLock {
            homeCache?.takeIf { !force && now - homeCachedAt in 0 until CACHE_TTL_MS }?.let { return it }
        }
        val data = coroutineScope {
            val trending = async { deezer.chartTracks(30) }
            val releases = async { deezer.newReleases(20) }
            val artists = async { deezer.chartArtists(20) }
            val playlists = async { deezer.chartPlaylists(20) }
            val genres = async { deezer.genres() }
            val videos = async { youtubeTracks("trending music videos 2026", 12) }
            val fetchedTrending = trending.await()
            MusicHomeData(
                trending = fetchedTrending.ifEmpty { videos.await() },
                newReleases = releases.await(),
                artists = artists.await(),
                playlists = playlists.await(),
                genres = genres.await(),
                youtubeVideos = videos.await(),
            )
        }
        homeLock.withLock {
            homeCache = data
            homeCachedAt = now
        }
        return data
    }

    suspend fun search(query: String): MusicSearchData {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return MusicSearchData()
        val now = System.currentTimeMillis()
        searchLock.withLock {
            searchCache[trimmed]?.takeIf { now - it.atMs in 0 until CACHE_TTL_MS }?.let { return it.data }
        }
        val data = coroutineScope {
            val tracks = async { deezer.searchTracks(trimmed, 40) }
            val artists = async { deezer.searchArtists(trimmed, 20) }
            val albums = async { deezer.searchAlbums(trimmed, 20) }
            val playlists = async { deezer.searchPlaylists(trimmed, 20) }
            val fetchedTracks = tracks.await()
            MusicSearchData(
                tracks = fetchedTracks.ifEmpty { youtubeTracks(trimmed, 40) },
                artists = artists.await(),
                albums = albums.await(),
                playlists = playlists.await(),
            )
        }
        searchLock.withLock { searchCache[trimmed] = SearchEntry(now, data) }
        return data
    }

    suspend fun artist(id: String): MusicArtistDetails? = deezer.artistDetails(id)

    suspend fun album(id: String): MusicAlbumDetails? = deezer.albumDetails(id)

    suspend fun playlist(id: String): MusicPlaylistDetails? = deezer.playlistDetails(id)

    suspend fun genreTracks(genre: MusicGenre): List<MusicTrack> = deezer.genreTracks(genre)

    /**
     * Resolves a song to a YouTube stream. The catalogue gives us the metadata, a YouTube search
     * for the artist and title gives us the music video; the result is cached per song.
     */
    suspend fun resolve(track: MusicTrack): Result<MusicStream> {
        val local = track.localFile?.takeIf { it.isNotBlank() }
            ?: library.downloadFor(track.id)?.localPath
        local?.takeIf { it.isNotBlank() }?.let { path ->
            return Result.success(
                MusicStream(
                    videoUrl = path,
                    audioUrl = path,
                    title = track.title,
                    thumbnail = track.thumbnail,
                ),
            )
        }
        val videoId = track.youtubeVideoId?.takeIf { it.isNotBlank() }
            ?: youtubeIds[track.id]
            ?: findYoutubeVideo(track)?.also { youtubeIds[track.id] = it }
            ?: return Result.failure(IllegalStateException("No matching YouTube video found."))
        return youtube.resolve(videoId).map { stream ->
            stream.copy(title = track.title, thumbnail = track.thumbnail)
        }
    }

    suspend fun lyrics(track: MusicTrack): LyricsData = lyrics.fetch(track)

    private suspend fun findYoutubeVideo(track: MusicTrack): String? {
        val queries = listOf(
            "${track.artist} ${track.title} official music video",
            "${track.artist} ${track.title} audio",
        )
        for (query in queries) {
            val result = runCatching { youtube.search(query, 6) }.getOrDefault(emptyList())
            val match = result.firstOrNull { video ->
                val haystack = (video.title + " " + (video.author ?: "")).lowercase()
                track.artist.isNotBlank() &&
                    haystack.contains(track.artist.lowercase().substringBefore(" "), ignoreCase = true)
            } ?: result.firstOrNull()
            if (match?.videoId?.isNotBlank() == true) return match.videoId
        }
        return null
    }

    private suspend fun youtubeTracks(query: String, limit: Int): List<MusicTrack> =
        runCatching { youtube.search(query, limit) }.getOrDefault(emptyList()).map { video ->
            MusicTrack(
                id = video.videoId,
                title = video.title,
                artist = video.author?.substringBefore(" • ")?.trim()?.ifBlank { null } ?: "YouTube",
                thumbnail = video.thumbnail,
                durationSeconds = video.durationSeconds,
                youtubeVideoId = video.videoId,
            )
        }

    private companion object {
        const val CACHE_TTL_MS = 60L * 60L * 1000L
    }
}
