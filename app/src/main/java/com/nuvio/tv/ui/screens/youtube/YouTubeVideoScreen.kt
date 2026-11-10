package com.nuvio.tv.ui.screens.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.music.MusicPlaybackFactory
import com.nuvio.tv.music.MusicStream
import com.nuvio.tv.music.YouTubeMusicService
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalPictureInPicture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class YouTubeVideoViewModel @Inject constructor(
    val youtube: YouTubeMusicService,
    val history: YouTubeHistoryRepository,
) : ViewModel()

/** A full-screen YouTube video: used by Sports replays and the YouTube Continue Watching entry. */
@Composable
fun YouTubeVideoScreen(
    videoId: String,
    title: String,
    onBack: () -> Unit,
    viewModel: YouTubeVideoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pip = LocalPictureInPicture.current
    val resolved by produceState<Result<MusicStream>?>(null, videoId) {
        value = viewModel.youtube.resolve(videoId)
    }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(showControls, controlsTick) {
        if (showControls) {
            delay(5_000)
            showControls = false
        } else {
            delay(30)
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(resolved) {
        val stream = resolved?.getOrNull() ?: return@LaunchedEffect
        error = null
        val created = MusicPlaybackFactory.create(context, stream, audioOnly = false)
        created.addListener(object : Player.Listener {
            override fun onPlayerError(playerError: androidx.media3.common.PlaybackException) {
                error = playerError.message ?: "Couldn't play this video."
            }
        })
        created.prepare()
        created.play()
        player = created
    }

    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var saveTick = 0
        while (true) {
            isPlaying = current.isPlaying
            positionMs = current.currentPosition.coerceAtLeast(0L)
            durationMs = current.duration.coerceAtLeast(0L)
            if (++saveTick >= 12 && durationMs > 0L) {
                saveTick = 0
                viewModel.history.record(videoId, title, positionMs, durationMs)
            }
            delay(400L)
        }
    }

    val currentPlayer = player
    DisposableEffect(currentPlayer) {
        onDispose {
            currentPlayer?.let { current ->
                val duration = current.duration.coerceAtLeast(0L)
                val position = current.currentPosition.coerceAtLeast(0L)
                if (duration > 0L) viewModel.history.record(videoId, title, position, duration)
                current.release()
            }
            if (player === currentPlayer) player = null
        }
    }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    controlsTick++
                    if (!showControls) {
                        showControls = true
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                controlsTick++
                when (event.key) {
                    Key.DirectionLeft -> {
                        player?.seekTo((player?.currentPosition ?: 0L) - 10_000L)
                        true
                    }
                    Key.DirectionRight -> {
                        player?.seekTo((player?.currentPosition ?: 0L) + 10_000L)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        true
                    }
                    else -> false
                }
            },
    ) {
        LivePlayerSurface(
            player = player,
            useSurfaceView = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (!pip.isInPip && showControls) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                ),
            ),
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
                Text(
                    title,
                    style = NuvioTheme.textStyles.sectionTitle,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { pip.enter() },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.85f),
                        contentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Default.PictureInPictureAlt,
                        contentDescription = "Picture in picture",
                        modifier = Modifier.size(22.dp),
                    )
                }
                Button(
                    onClick = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.85f),
                        contentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            error?.let {
                Text(it, color = NuvioTheme.colors.Error, style = NuvioTheme.textStyles.body)
                Spacer(Modifier.height(8.dp))
            }
            if (resolved == null) {
                Text("Preparing video…", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
            } else if (resolved?.isFailure == true && error == null) {
                Text(
                    resolved?.exceptionOrNull()?.message ?: "Couldn't play this video.",
                    color = NuvioTheme.colors.TextSecondary,
                    style = NuvioTheme.textStyles.body,
                )
            }
            if (durationMs > 0L) {
                val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(fraction).height(6.dp)
                            .background(NuvioTheme.colors.TextPrimary, RoundedCornerShape(50)),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
