package com.nuvio.tv.music

import com.nuvio.tv.youtube.YouTubeEngine
import com.nuvio.tv.youtube.YtCommentPage
import com.nuvio.tv.youtube.YtVideoInfo
import javax.inject.Inject
import javax.inject.Singleton

/** A YouTube search result, before it becomes a [MusicTrack]. */
data class YouTubeMusicVideo(
    val videoId: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    val durationSeconds: Int = 0,
)

/**
 * Music and Sports Replays share the app's YouTube engine: the same feed/search/sign-in path as
 * the YouTube tab, so a signed-in account, history and region apply everywhere.
 */
@Singleton
class YouTubeMusicService @Inject constructor(
    private val engine: YouTubeEngine,
) {
    suspend fun search(query: String, limit: Int = 20): List<YouTubeMusicVideo> =
        engine.search(query, limit)

    suspend fun resolve(videoId: String): Result<MusicStream> =
        engine.resolve(videoId)

    suspend fun videoInfo(videoId: String): Result<YtVideoInfo> =
        engine.videoInfo(videoId)

    suspend fun comments(videoId: String): Result<YtCommentPage> =
        engine.comments(videoId)

    suspend fun replies(key: String): Result<YtCommentPage> =
        engine.replies(key)
}
