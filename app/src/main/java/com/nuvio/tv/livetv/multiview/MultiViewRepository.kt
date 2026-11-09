package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MultiViewStream(
    val id: String,
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val logo: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val providerName: String = "",
)

/**
 * The TV multi-view session: up to six channels at once. Kept above navigation so the quick
 * button in the player and the guide both feed the same grid.
 */
object MultiViewRepository {
    const val MAX_STREAMS = 6

    private val _streams = MutableStateFlow<List<MultiViewStream>>(emptyList())
    val streams: StateFlow<List<MultiViewStream>> = _streams.asStateFlow()

    private val _audibleId = MutableStateFlow<String?>(null)
    val audibleId: StateFlow<String?> = _audibleId.asStateFlow()

    private val _soundAll = MutableStateFlow(false)
    val soundAll: StateFlow<Boolean> = _soundAll.asStateFlow()

    private val _pauseAll = MutableStateFlow(false)
    val pauseAll: StateFlow<Boolean> = _pauseAll.asStateFlow()

    fun add(channel: LiveChannel): Boolean {
        if (channel.url.isBlank()) return false
        val current = _streams.value
        if (current.any { it.id == channel.key }) return false
        if (current.size >= MAX_STREAMS) return false
        val stream = MultiViewStream(
            id = channel.key,
            url = channel.url,
            title = channel.name,
            subtitle = channel.group,
            logo = channel.logo,
            headers = channel.headers,
            providerName = channel.sourceName,
        )
        _streams.value = current + stream
        if (_audibleId.value == null) _audibleId.value = stream.id
        return true
    }

    fun remove(id: String) {
        _streams.value = _streams.value.filterNot { it.id == id }
        if (_audibleId.value == id) _audibleId.value = _streams.value.firstOrNull()?.id
    }

    fun clear() {
        _streams.value = emptyList()
        _audibleId.value = null
        _pauseAll.value = false
    }

    fun setAudible(id: String) {
        if (_streams.value.any { it.id == id }) _audibleId.value = id
    }

    fun setSoundAll(value: Boolean) {
        _soundAll.value = value
    }

    fun setPaused(value: Boolean) {
        _pauseAll.value = value
    }
}
