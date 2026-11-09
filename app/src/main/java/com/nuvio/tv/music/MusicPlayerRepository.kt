package com.nuvio.tv.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

enum class MusicRepeatMode { OFF, ALL, ONE }

/**
 * The music session: what is playing, the queue it came from, the Video/Audio mode and the
 * shuffle/repeat settings. Shared by the Music page and the player.
 */
@Singleton
class MusicPlayerRepository @Inject constructor() {
    private val _current = MutableStateFlow<MusicTrack?>(null)
    val current: StateFlow<MusicTrack?> = _current.asStateFlow()

    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = _queue.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(MusicRepeatMode.OFF)
    val repeat: StateFlow<MusicRepeatMode> = _repeat.asStateFlow()

    fun play(track: MusicTrack, queue: List<MusicTrack>) {
        val ordered = if (_shuffle.value) queue.shuffled(Random.Default) else queue
        _queue.value = ordered.ifEmpty { listOf(track) }
        _current.value = track
    }

    fun close() {
        _current.value = null
    }

    fun setAudioOnly(audioOnly: Boolean) {
        _audioOnly.value = audioOnly
    }

    fun toggleAudioOnly() {
        _audioOnly.value = !_audioOnly.value
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
    }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            MusicRepeatMode.OFF -> MusicRepeatMode.ALL
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.OFF
        }
    }

    fun next(): MusicTrack? = step(1)

    fun previous(): MusicTrack? = step(-1)

    /** Called when the player reaches the end: repeat-one replays, repeat-all wraps, off stops. */
    fun advanceOnEnd(): MusicTrack? = when (_repeat.value) {
        MusicRepeatMode.ONE -> _current.value
        MusicRepeatMode.ALL -> step(1)
        MusicRepeatMode.OFF -> nextWithoutWrap()
    }

    fun playFromQueue(index: Int): MusicTrack? {
        val track = _queue.value.getOrNull(index) ?: return null
        _current.value = track
        return track
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        val removed = list.removeAt(index)
        _queue.value = list
        if (removed.id == _current.value?.id) {
            _current.value = list.getOrNull(index) ?: list.getOrNull(index - 1) ?: list.firstOrNull()
        }
    }

    fun clearQueue() {
        _queue.value = emptyList()
        _current.value = null
    }

    private fun nextWithoutWrap(): MusicTrack? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val index = indexOfCurrent(queue)
        val next = queue.getOrNull(index + 1) ?: return null
        _current.value = next
        return next
    }

    private fun step(delta: Int): MusicTrack? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        if (_shuffle.value && queue.size > 1) {
            val others = queue.filterNot { it.id == _current.value?.id }
            return others.randomOrNull(Random.Default)?.also { _current.value = it }
        }
        val index = indexOfCurrent(queue)
        val nextIndex = when {
            _repeat.value == MusicRepeatMode.ALL -> (index + delta).mod(queue.size)
            else -> (index + delta).coerceIn(0, queue.lastIndex)
        }
        if (nextIndex == index && delta > 0) return null
        val next = queue[nextIndex]
        _current.value = next
        return next
    }

    private fun indexOfCurrent(queue: List<MusicTrack>): Int =
        queue.indexOfFirst { it.id == _current.value?.id }.takeIf { it >= 0 } ?: 0
}
