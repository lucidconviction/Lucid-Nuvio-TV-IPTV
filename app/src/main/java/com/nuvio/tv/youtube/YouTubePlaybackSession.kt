package com.nuvio.tv.youtube

/**
 * The list a YouTube video was opened from. The player uses it for "More videos" and to play the
 * next one when the current video ends. Survives navigation, so the player route only needs the
 * id of the video that was picked.
 */
object YouTubePlaybackSession {
    data class Entry(
        val videoId: String,
        val title: String,
        val poster: String?,
        val author: String? = null,
    )

    @Volatile private var entries: List<Entry> = emptyList()

    fun set(list: List<Entry>, currentVideoId: String) {
        val clean = list.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        entries = if (clean.any { it.videoId == currentVideoId }) clean else {
            clean + Entry(currentVideoId, "", null)
        }
    }

    fun all(): List<Entry> = entries

    fun current(videoId: String): Entry? = entries.firstOrNull { it.videoId == videoId }

    fun next(videoId: String): Entry? {
        val list = entries
        val index = list.indexOfFirst { it.videoId == videoId }
        return if (index >= 0) list.getOrNull(index + 1) else null
    }
}
