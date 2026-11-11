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

/** How the open windows are arranged. Auto picks the best layout for the number of windows. */
enum class MultiViewLayout {
    AUTO,
    SIDE_BY_SIDE,
    GRID,
    ROW,
    COLUMN,
    FOCUS_CORNER,
}

fun availableMultiViewLayouts(count: Int): List<MultiViewLayout> = when (count) {
    0, 1 -> listOf(MultiViewLayout.AUTO)
    2 -> listOf(MultiViewLayout.AUTO, MultiViewLayout.SIDE_BY_SIDE, MultiViewLayout.FOCUS_CORNER)
    3 -> listOf(MultiViewLayout.AUTO, MultiViewLayout.GRID, MultiViewLayout.FOCUS_CORNER, MultiViewLayout.ROW, MultiViewLayout.COLUMN)
    else -> listOf(
        MultiViewLayout.AUTO,
        MultiViewLayout.GRID,
        MultiViewLayout.FOCUS_CORNER,
        MultiViewLayout.ROW,
        MultiViewLayout.COLUMN,
    )
}

/**
 * The TV multi-view session: up to four channels at once. Kept above navigation so the quick
 * button in the player and the guide both feed the same grid.
 */
object MultiViewRepository {
    const val MAX_STREAMS = 4

    private val _streams = MutableStateFlow<List<MultiViewStream>>(emptyList())
    val streams: StateFlow<List<MultiViewStream>> = _streams.asStateFlow()

    private val _audibleId = MutableStateFlow<String?>(null)
    val audibleId: StateFlow<String?> = _audibleId.asStateFlow()

    private val _soundAll = MutableStateFlow(false)
    val soundAll: StateFlow<Boolean> = _soundAll.asStateFlow()

    private val _pauseAll = MutableStateFlow(false)
    val pauseAll: StateFlow<Boolean> = _pauseAll.asStateFlow()

    private val _layout = MutableStateFlow(MultiViewLayout.AUTO)
    val layout: StateFlow<MultiViewLayout> = _layout.asStateFlow()

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

    fun setLayout(layout: MultiViewLayout) {
        _layout.value = layout
    }

    /** Exchanges two windows, keeping both streams playing. */
    fun swap(firstId: String, secondId: String) {
        if (firstId == secondId) return
        val current = _streams.value
        val first = current.indexOfFirst { it.id == firstId }
        val second = current.indexOfFirst { it.id == secondId }
        if (first < 0 || second < 0) return
        _streams.value = current.toMutableList().also {
            it[first] = current[second]
            it[second] = current[first]
        }
    }

    fun setSoundAll(value: Boolean) {
        _soundAll.value = value
    }

    fun setPaused(value: Boolean) {
        _pauseAll.value = value
    }
}
