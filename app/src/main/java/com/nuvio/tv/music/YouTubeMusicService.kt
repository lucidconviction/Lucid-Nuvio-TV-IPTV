package com.nuvio.tv.music

import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube search and playback for the Music page, using the same public innertube endpoint the
 * trailer extractor already understands. Search feeds the catalogue; the extractor turns a video
 * id into a playable video/audio URL.
 */
@Singleton
class YouTubeMusicService @Inject constructor(
    private val extractor: InAppYouTubeExtractor,
    private val http: MusicHttp,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val searchLock = Mutex()
    private val resolveLock = Mutex()
    private val searchCache = LinkedHashMap<String, CachedSearch>()
    private val resolveCache = HashMap<String, CachedStream>()

    suspend fun search(query: String, limit: Int = 20): List<YouTubeMusicVideo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        searchLock.withLock {
            searchCache[trimmed]?.takeIf { now - it.atMs < SEARCH_TTL_MS }?.let { return it.items.take(limit) }
        }
        val queryJson = kotlinx.serialization.json.JsonPrimitive(trimmed).toString()
        val body = """
            {"context":{"client":{"clientName":"WEB","clientVersion":"2.20260907.06.00","hl":"en","gl":"US"}},"query":$queryJson}
        """.trimIndent()
        val response = http.postJson(
            url = "https://www.youtube.com/youtubei/v1/search?key=$API_KEY",
            json = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
            ),
        ) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(response) }.getOrNull() ?: return emptyList()
        val renderers = mutableListOf<JsonObject>()
        root.collectVideoRenderers(renderers)
        val videos = renderers.mapNotNull(::video).distinctBy { it.videoId }
        searchLock.withLock {
            if (searchCache.size >= 30) searchCache.clear()
            searchCache[trimmed] = CachedSearch(now, videos)
        }
        return videos.take(limit)
    }

    suspend fun resolve(videoId: String): Result<MusicStream> {
        val id = videoId.trim()
        if (id.isEmpty()) return Result.failure(IllegalStateException("No YouTube video id"))
        val now = System.currentTimeMillis()
        resolveLock.withLock {
            resolveCache[id]?.takeIf { now - it.atMs < RESOLVE_TTL_MS }?.let { return Result.success(it.stream) }
        }
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val source = runCatching { extractor.extractPlaybackSource(watchUrl) }.getOrNull()
            ?: runCatching { extractor.extractSingleUrl(watchUrl)?.let { com.nuvio.tv.data.trailer.TrailerPlaybackSource(it) } }.getOrNull()
            ?: return Result.failure(IllegalStateException("YouTube couldn't prepare this track. Try another one."))
        if (source.videoUrl.isBlank()) {
            return Result.failure(IllegalStateException("YouTube didn't return a stream for this track."))
        }
        val stream = MusicStream(
            videoUrl = source.videoUrl,
            audioUrl = source.audioUrl,
            title = "",
            thumbnail = null,
        )
        resolveLock.withLock {
            if (resolveCache.size >= 30) resolveCache.clear()
            resolveCache[id] = CachedStream(now, stream)
        }
        return Result.success(stream)
    }

    private fun video(raw: JsonObject): YouTubeMusicVideo? {
        val id = raw["videoId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 11 } ?: return null
        val title = (raw["title"] as? JsonObject)?.let { titleText(it) } ?: return null
        val author = (raw["ownerText"] as? JsonObject)?.let { titleText(it) }
        val thumbnail = ((raw["thumbnail"] as? JsonObject)?.get("thumbnails") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull }
            ?.lastOrNull()
        val duration = (raw["lengthText"] as? JsonObject)?.get("simpleText")?.jsonPrimitive?.contentOrNull
            ?.let(::parseDuration)
            ?: 0
        return YouTubeMusicVideo(
            videoId = id,
            title = title,
            author = author,
            thumbnail = thumbnail,
            durationSeconds = duration,
        )
    }

    private fun titleText(obj: JsonObject): String? {
        val runs = obj["runs"] as? JsonArray
        val fromRuns = runs?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
        return fromRuns ?: obj["simpleText"]?.jsonPrimitive?.contentOrNull
    }

    private fun parseDuration(text: String): Int {
        val parts = text.split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    private fun JsonElement.collectVideoRenderers(out: MutableList<JsonObject>) {
        when (this) {
            is JsonObject -> {
                (this["videoRenderer"] as? JsonObject)?.let(out::add)
                values.forEach { it.collectVideoRenderers(out) }
            }
            is JsonArray -> forEach { it.collectVideoRenderers(out) }
            else -> Unit
        }
    }

    private data class CachedSearch(val atMs: Long, val items: List<YouTubeMusicVideo>)
    private data class CachedStream(val atMs: Long, val stream: MusicStream)

    private companion object {
        const val SEARCH_TTL_MS = 60L * 60L * 1000L
        const val RESOLVE_TTL_MS = 20L * 60L * 1000L
        const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}
