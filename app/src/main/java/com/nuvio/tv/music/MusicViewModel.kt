package com.nuvio.tv.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(
    val repository: MusicRepository,
    val library: MusicLibraryRepository,
    val downloads: MusicDownloadsRepository,
    val player: MusicPlayerRepository,
) : ViewModel() {
    private val _home = MutableStateFlow<MusicHomeData?>(null)
    val home: StateFlow<MusicHomeData?> = _home.asStateFlow()

    private val _loadingHome = MutableStateFlow(false)
    val loadingHome: StateFlow<Boolean> = _loadingHome.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadHome(force: Boolean = false) {
        if (_loadingHome.value) return
        _loadingHome.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { repository.home(force) }
                .onSuccess { _home.value = it }
                .onFailure { _error.value = it.message ?: "Couldn't load music." }
            _loadingHome.value = false
        }
    }

    fun play(track: MusicTrack, queue: List<MusicTrack>) {
        player.play(track, queue)
    }

    fun playPlaylist(tracks: List<MusicTrack>, startIndex: Int = 0) {
        val track = tracks.getOrNull(startIndex) ?: tracks.firstOrNull() ?: return
        player.play(track, tracks)
    }

    fun clearError() {
        _error.value = null
    }
}
