package com.nuvio.tv.ui.screens.youtube

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
data class YouTubeHistoryEntry(
    val videoId: String,
    val title: String,
    val channel: String = "YouTube",
    val thumbnail: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Locally kept YouTube history, so a video can be picked up again from Home. */
@Singleton
class YouTubeHistoryRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _entries = MutableStateFlow<List<YouTubeHistoryEntry>>(emptyList())
    val entries: StateFlow<List<YouTubeHistoryEntry>> = _entries.asStateFlow()

    init {
        _entries.value = runCatching {
            prefs.getString(KEY, null)?.let { json.decodeFromString<List<YouTubeHistoryEntry>>(it) }
        }.getOrNull().orEmpty().map { it.copy(thumbnail = sixteenByNineThumbnail(it.thumbnail)) }
    }

    /**
     * YouTube's hqdefault.jpg is 4:3 with black bars; the Home row and the YouTube cards are
     * 16:9, so use the 16:9 frame everywhere. Old saved entries are normalized too.
     */
    private fun sixteenByNineThumbnail(url: String?): String? = url
        ?.replace("/hqdefault.jpg", "/mqdefault.jpg")
        ?.replace("/default.jpg", "/mqdefault.jpg")
        ?.takeIf { it.isNotBlank() }

    fun record(videoId: String, title: String, positionMs: Long, durationMs: Long) {
        if (videoId.isBlank()) return
        if (!youtubeShouldKeepHistory(positionMs, durationMs)) {
            if (durationMs > 0L && positionMs >= 3_000L) remove(videoId)
            return
        }
        val entry = YouTubeHistoryEntry(
            videoId = videoId,
            title = title.ifBlank { "YouTube video" },
            channel = "YouTube",
            thumbnail = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAtMs = System.currentTimeMillis(),
        )
        _entries.value = (listOf(entry) + _entries.value.filterNot { it.videoId == videoId })
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_ENTRIES)
        persist()
    }

    fun remove(videoId: String) {
        _entries.value = _entries.value.filterNot { it.videoId == videoId }
        persist()
    }

    /** Where to start [videoId] again, or 0 when it was never part-watched. */
    fun resumePositionFor(videoId: String): Long {
        val entry = _entries.value.firstOrNull { it.videoId == videoId } ?: return 0L
        return youtubeResumePosition(entry.positionMs, entry.durationMs)
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    private fun persist() {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(_entries.value)).apply() }
    }

    private companion object {
        const val PREFS = "nuvio_youtube_history"
        const val KEY = "history"
        const val MAX_ENTRIES = 20
    }
}

/**
 * A part-watched video belongs in the Continue watching row only when there is enough progress
 * to be useful and not so much that it is effectively finished. The same 3/95 boundaries are
 * used for resuming, so a card never opens a video that starts from the beginning anyway.
 */
internal fun youtubeShouldKeepHistory(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L || positionMs < 3_000L) return false
    val percent = positionMs * 100L / durationMs
    return percent in 3L..94L && positionMs < durationMs - 5_000L
}

internal fun youtubeResumePosition(positionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L || positionMs <= 0L) return 0L
    val percent = positionMs * 100L / durationMs
    return if (percent in 3L..94L) positionMs else 0L
}

@HiltViewModel
class YouTubeHistoryViewModel @Inject constructor(
    val history: YouTubeHistoryRepository,
) : ViewModel()

/** Set by the Home screen so a YouTube card can open the full-screen video route. */
object YouTubeOpen {
    var open: ((videoId: String, title: String) -> Unit)? = null
}
