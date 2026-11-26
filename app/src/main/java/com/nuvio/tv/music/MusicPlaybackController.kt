package com.nuvio.tv.music

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CROSSFADE_MS = 1_200

/** One player during a crossfade. The newest is the one the mini player and controls act on. */
class MusicPlayback(
    val trackId: String,
    val player: ExoPlayer,
) {
    var alpha by mutableFloatStateOf(1f)
}

data class MusicSession(
    val track: MusicTrack? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioOnly: Boolean = false,
    val error: String? = null,
    /** Bumped when the player list changes so video surfaces rebind. */
    val generation: Int = 0,
)

/**
 * The app-wide music session. The player screen is only a view over it: leaving the screen
 * keeps the music playing, and the TV mini player can pause, skip or reopen it from anywhere.
 * The two-player crossfade from the old screen lives here so navigation cannot cut it off.
 */
@Singleton
class MusicPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val playerRepo: MusicPlayerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playbacks = mutableStateListOf<MusicPlayback>()
    private val _session = MutableStateFlow(MusicSession())
    val session: StateFlow<MusicSession> = _session.asStateFlow()

    private var startJob: Job? = null
    private var playToken = 0
    private var lastStartedId: String? = null
    private var lastEndedId: String? = null

    init {
        scope.launch {
            playerRepo.current.collect { track ->
                when {
                    track == null -> stopAll(clearTrack = true)
                    track.id != lastStartedId -> startTrack(track)
                }
            }
        }
        scope.launch {
            playerRepo.audioOnly.collect { audioOnly ->
                _session.value = _session.value.copy(audioOnly = audioOnly)
                if (playbacks.isNotEmpty()) {
                    val position = playbacks.lastOrNull()?.player?.currentPosition ?: 0L
                    stopAll(clearTrack = false)
                    playerRepo.current.value?.let { startTrack(it, startPositionMs = position) }
                }
            }
        }
        scope.launch {
            while (isActive) {
                pollPlayback()
                delay(400L)
            }
        }
    }

    fun play(track: MusicTrack, queue: List<MusicTrack>) {
        playerRepo.play(track, queue)
        val existing = playbacks.firstOrNull { it.trackId == track.id }
        if (existing != null) {
            existing.player.play()
        } else if (track.id == lastStartedId) {
            startTrack(track)
        }
    }

    fun toggle() {
        val player = playbacks.lastOrNull()?.player ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun next() {
        playerRepo.next()
    }

    fun previous() {
        val player = playbacks.lastOrNull()?.player
        if (player != null && player.currentPosition > 3_000L) {
            player.seekTo(0L)
        } else {
            playerRepo.previous()
        }
    }

    fun seekTo(positionMs: Long) {
        playbacks.lastOrNull()?.player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setAudioOnly(enabled: Boolean) {
        playerRepo.setAudioOnly(enabled)
    }

    fun close() {
        playerRepo.clearQueue()
    }

    fun reportError(message: String) {
        _session.value = _session.value.copy(error = message, loading = false)
    }

    private fun startTrack(track: MusicTrack, startPositionMs: Long = 0L) {
        if (playbacks.any { it.trackId == track.id }) {
            playbacks.first { it.trackId == track.id }.player.play()
            return
        }
        val token = ++playToken
        startJob?.cancel()
        _session.value = _session.value.copy(track = track, loading = true, error = null)
        startJob = scope.launch {
            val result = withContext(Dispatchers.IO) { repository.resolve(track) }
            if (token != playToken || playerRepo.current.value?.id != track.id) return@launch
            val stream = result.getOrNull()
            if (stream == null) {
                _session.value = _session.value.copy(
                    loading = false,
                    error = result.exceptionOrNull()?.message ?: "Couldn't play this track.",
                )
                return@launch
            }
            val player = MusicPlaybackFactory.create(context, stream, playerRepo.audioOnly.value)
            val playback = MusicPlayback(track.id, player)
            val first = playbacks.isEmpty()
            playback.alpha = if (first) 1f else 0f
            player.volume = if (first) 1f else 0f
            playbacks.add(playback)
            lastStartedId = track.id
            lastEndedId = null
            _session.value = _session.value.copy(generation = _session.value.generation + 1)
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (playbacks.lastOrNull() === playback) {
                        _session.value = _session.value.copy(
                            loading = false,
                            error = error.message ?: "Couldn't play this track.",
                        )
                    }
                }
            })
            player.prepare()
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
            player.play()
            if (!first) {
                withTimeoutOrNull(6_000L) {
                    while (
                        player.playbackState != Player.STATE_READY &&
                        player.playbackState != Player.STATE_ENDED
                    ) {
                        delay(60L)
                    }
                }
                val outgoing = playbacks.filter { it !== playback }
                coroutineScope {
                    launch {
                        animate(0f, 1f, animationSpec = tween(CROSSFADE_MS, easing = LinearEasing)) { value, _ ->
                            playback.alpha = value
                        }
                    }
                    launch { rampVolume(player, 0f, 1f, CROSSFADE_MS) }
                    outgoing.forEach { old ->
                        launch {
                            animate(old.alpha, 0f, animationSpec = tween(CROSSFADE_MS, easing = LinearEasing)) { value, _ ->
                                old.alpha = value
                            }
                        }
                        launch { rampVolume(old.player, old.player.volume, 0f, CROSSFADE_MS) }
                    }
                }
                delay(CROSSFADE_MS.toLong() + 100L)
                outgoing.forEach { old ->
                    runCatching { old.player.release() }
                    playbacks.remove(old)
                }
                _session.value = _session.value.copy(generation = _session.value.generation + 1)
            }
        }
    }

    private fun pollPlayback() {
        val active = playbacks.lastOrNull() ?: run {
            _session.value = _session.value.copy(
                track = playerRepo.current.value,
                playing = false,
                loading = _session.value.loading,
                positionMs = 0L,
                durationMs = 0L,
            )
            return
        }
        val player = active.player
        val track = playerRepo.current.value
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        val duration = runCatching { player.duration }.getOrDefault(0L)
        val ended = player.playbackState == Player.STATE_ENDED
        _session.value = _session.value.copy(
            track = track ?: _session.value.track,
            playing = player.isPlaying,
            loading = player.playbackState == Player.STATE_BUFFERING,
            positionMs = position.coerceAtLeast(0L),
            durationMs = (if (duration > 0L) duration else track?.durationSeconds?.times(1_000L) ?: 0L),
        )
        if (ended && lastEndedId != active.trackId) {
            lastEndedId = active.trackId
            playerRepo.advanceOnEnd()
        }
    }

    private fun stopAll(clearTrack: Boolean) {
        playToken++
        startJob?.cancel()
        startJob = null
        playbacks.forEach { runCatching { it.player.release() } }
        playbacks.clear()
        lastStartedId = null
        lastEndedId = null
        _session.value = _session.value.copy(
            track = if (clearTrack) null else _session.value.track,
            playing = false,
            loading = false,
            positionMs = 0L,
            durationMs = 0L,
            generation = _session.value.generation + 1,
        )
    }

    private suspend fun rampVolume(player: ExoPlayer, from: Float, to: Float, durationMs: Int) {
        animate(0f, 1f, animationSpec = tween(durationMs, easing = LinearEasing)) { value, _ ->
            player.volume = from + (to - from) * value
        }
    }
}
