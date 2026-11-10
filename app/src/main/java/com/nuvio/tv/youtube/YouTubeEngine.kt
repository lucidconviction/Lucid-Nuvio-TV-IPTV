package com.nuvio.tv.youtube

import android.content.Context
import com.nuvio.tv.music.MusicStream
import com.nuvio.tv.music.YouTubeMusicVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One YouTube engine for the whole app: the same feeds, search, TV sign-in and stream lookup as
 * the YouTube tab. Music and Sports Replays use this instead of a second resolver.
 */
@Singleton
class YouTubeEngine @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val viewModel = YouTubeViewModel(context, autoSelect = false)

    suspend fun search(query: String, limit: Int): List<YouTubeMusicVideo> =
        viewModel.searchVideos(query, limit).map { item ->
            YouTubeMusicVideo(
                videoId = item.videoId.orEmpty(),
                title = item.title,
                author = item.author,
                thumbnail = item.thumbnail,
                durationSeconds = 0,
            )
        }.filter { it.videoId.isNotBlank() }

    suspend fun resolve(videoId: String, title: String = "", poster: String? = null): Result<MusicStream> =
        viewModel.resolveVideo(videoId, title, poster).map { playable ->
            MusicStream(
                videoUrl = playable.url,
                audioUrl = playable.audioUrl,
                title = playable.title,
                thumbnail = playable.poster,
                headers = playable.headers,
            )
        }
}
