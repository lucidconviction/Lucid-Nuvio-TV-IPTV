package com.nuvio.tv.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads a song's audio-only YouTube stream for offline playback. */
@Singleton
class MusicDownloadsRepository @Inject constructor(
    private val music: MusicRepository,
    private val library: MusicLibraryRepository,
    private val platform: MusicPlatform,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun isDownloading(trackId: String): Boolean = trackId in _active.value

    fun isDownloaded(trackId: String): Boolean = library.downloadFor(trackId) != null

    fun download(track: MusicTrack) {
        if (isDownloading(track.id) || isDownloaded(track.id)) return
        _active.value = _active.value + track.id
        _message.value = null
        scope.launch {
            try {
                val resolved = music.resolve(track).getOrElse { error ->
                    finish(track.id, error.message ?: "Couldn't download this song.")
                    return@launch
                }
                val audioUrl = resolved.audioUrl?.takeIf { it.isNotBlank() } ?: resolved.videoUrl
                val path = platform.downloadAudio(audioUrl, track.id)
                    ?: return@launch finish(track.id, "The download failed.")
                library.addDownloaded(track.copy(localFile = path), path)
                finish(track.id, "Downloaded \"${track.title}\"")
            } catch (error: Exception) {
                finish(track.id, error.message ?: "The download failed.")
            }
        }
    }

    private fun finish(trackId: String, message: String) {
        _active.value = _active.value - trackId
        _message.value = message
    }
}
