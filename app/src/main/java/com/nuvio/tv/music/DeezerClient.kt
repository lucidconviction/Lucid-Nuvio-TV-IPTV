package com.nuvio.tv.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deezer's public catalogue API supplies the music metadata: tracks, artists, albums, playlists,
 * artwork, charts and genres. Playback is resolved separately through YouTube.
 */
@Singleton
class DeezerClient @Inject constructor(
    private val http: MusicHttp,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject? {
        val url = ("https://api.deezer.com$path").toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            ?.build()
            ?.toString()
            ?: return null
        val text = http.getText(url) ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun dataObjects(root: JsonObject?): List<JsonObject> =
        (root?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun chartTracks(limit: Int = 30): List<MusicTrack> =
        dataObjects(get("/chart/0/tracks", mapOf("limit" to limit.toString()))).mapNotNull(::track)

    suspend fun chartAlbums(limit: Int = 20): List<MusicAlbum> =
        dataObjects(get("/chart/0/albums", mapOf("limit" to limit.toString()))).mapNotNull(::album)

    suspend fun chartArtists(limit: Int = 20): List<MusicArtist> =
        dataObjects(get("/chart/0/artists", mapOf("limit" to limit.toString()))).mapNotNull(::artist)

    suspend fun chartPlaylists(limit: Int = 20): List<MusicPlaylist> =
        dataObjects(get("/chart/0/playlists", mapOf("limit" to limit.toString()))).mapNotNull(::playlist)

    suspend fun newReleases(limit: Int = 20): List<MusicAlbum> =
        dataObjects(get("/editorial/0/releases", mapOf("limit" to limit.toString()))).mapNotNull(::album)

    suspend fun genres(): List<MusicGenre> =
        dataObjects(get("/genre")).mapNotNull(::genre).filter { it.id != "0" }

    suspend fun searchTracks(query: String, limit: Int = 40): List<MusicTrack> =
        dataObjects(get("/search/track", mapOf("q" to query, "limit" to limit.toString()))).mapNotNull(::track)

    suspend fun searchArtists(query: String, limit: Int = 20): List<MusicArtist> =
        dataObjects(get("/search/artist", mapOf("q" to query, "limit" to limit.toString()))).mapNotNull(::artist)

    suspend fun searchAlbums(query: String, limit: Int = 20): List<MusicAlbum> =
        dataObjects(get("/search/album", mapOf("q" to query, "limit" to limit.toString()))).mapNotNull(::album)

    suspend fun searchPlaylists(query: String, limit: Int = 20): List<MusicPlaylist> =
        dataObjects(get("/search/playlist", mapOf("q" to query, "limit" to limit.toString()))).mapNotNull(::playlist)

    suspend fun artistDetails(artistId: String): MusicArtistDetails? {
        val info = get("/artist/$artistId")?.takeIf { it["id"] != null } ?: return null
        val artist = artist(info) ?: return null
        return MusicArtistDetails(
            artist = artist,
            topTracks = dataObjects(get("/artist/$artistId/top", mapOf("limit" to "50"))).mapNotNull(::track),
            albums = dataObjects(get("/artist/$artistId/albums", mapOf("limit" to "50"))).mapNotNull(::album),
            relatedArtists = dataObjects(get("/artist/$artistId/related", mapOf("limit" to "20"))).mapNotNull(::artist),
        )
    }

    suspend fun albumDetails(albumId: String): MusicAlbumDetails? {
        val info = get("/album/$albumId")?.takeIf { it["id"] != null } ?: return null
        val parsed = album(info) ?: return null
        val embedded = info["tracks"] as? JsonObject
        val tracks = dataObjects(embedded).ifEmpty {
            dataObjects(get("/album/$albumId/tracks", mapOf("limit" to "100")))
        }.mapNotNull { raw ->
            val withAlbum = if (raw["album"] == null) {
                JsonObject(
                    raw + (
                        "album" to JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(parsed.id),
                                "title" to JsonPrimitive(parsed.title),
                                "cover_big" to JsonPrimitive(parsed.coverUrl.orEmpty()),
                            ),
                        )
                    ),
                )
            } else {
                raw
            }
            track(withAlbum)
        }
        return MusicAlbumDetails(parsed, tracks)
    }

    suspend fun playlistDetails(playlistId: String): MusicPlaylistDetails? {
        val info = get("/playlist/$playlistId")?.takeIf { it["id"] != null } ?: return null
        val parsed = playlist(info) ?: return null
        val embedded = info["tracks"] as? JsonObject
        val tracks = dataObjects(embedded).ifEmpty {
            dataObjects(get("/playlist/$playlistId/tracks", mapOf("limit" to "100")))
        }.mapNotNull(::track)
        return MusicPlaylistDetails(parsed, tracks)
    }

    suspend fun genreTracks(genre: MusicGenre, limit: Int = 40): List<MusicTrack> {
        val chart = dataObjects(get("/chart/${genre.id}/tracks", mapOf("limit" to limit.toString()))).mapNotNull(::track)
        if (chart.isNotEmpty()) return chart
        return searchTracks("${genre.name} hits", limit)
    }

    private fun track(o: JsonObject): MusicTrack? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val artistObject = o["artist"] as? JsonObject
        val albumObject = o["album"] as? JsonObject
        val title = o["title"]?.jsonPrimitive?.contentOrNull
            ?: o["title_short"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val artist = artistObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?: o["artist"]?.jsonPrimitive?.contentOrNull
            ?: "Unknown Artist"
        val album = albumObject?.get("title")?.jsonPrimitive?.contentOrNull
            ?: o["album"]?.jsonPrimitive?.contentOrNull
            ?: "Single"
        return MusicTrack(
            id = id,
            title = title,
            artist = artist,
            artistId = artistObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = album,
            albumId = albumObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            thumbnail = cover(albumObject ?: o),
            durationSeconds = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            badge = if (o["explicit_lyrics"]?.jsonPrimitive?.contentOrNull == "true") "E" else null,
        )
    }

    private fun album(o: JsonObject): MusicAlbum? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val artistObject = o["artist"] as? JsonObject
        return MusicAlbum(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "Album",
            artistName = artistObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?: o["artist"]?.jsonPrimitive?.contentOrNull
                ?: "Unknown Artist",
            artistId = artistObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = cover(o),
            releaseDate = o["release_date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            trackCount = o["nb_tracks"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun artist(o: JsonObject): MusicArtist? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return MusicArtist(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Artist",
            pictureUrl = cover(o),
            fanCount = o["nb_fan"]?.jsonPrimitive?.intOrNull ?: 0,
            albumCount = o["nb_album"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun playlist(o: JsonObject): MusicPlaylist? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val creator = o["creator"] as? JsonObject ?: o["user"] as? JsonObject
        return MusicPlaylist(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "Playlist",
            description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = cover(o),
            trackCount = o["nb_tracks"]?.jsonPrimitive?.intOrNull ?: 0,
            creatorName = creator?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun genre(o: JsonObject): MusicGenre? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return MusicGenre(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Genre",
            pictureUrl = cover(o),
        )
    }

    private fun cover(o: JsonObject): String? {
        val raw = listOf(
            "cover_xl", "cover_big", "cover_medium", "cover_small",
            "picture_xl", "picture_big", "picture_medium", "picture_small",
            "picture", "cover",
        ).firstNotNullOfOrNull { key -> o[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        return raw?.replaceFirst("http://", "https://")
    }
}
