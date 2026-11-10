@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.music.LyricsData
import com.nuvio.tv.music.MusicPlayerRepository
import com.nuvio.tv.music.MusicRepeatMode
import com.nuvio.tv.music.MusicStream
import com.nuvio.tv.music.MusicTrack
import com.nuvio.tv.music.MusicViewModel
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val CROSSFADE_MS = 1200

private class MusicPlayback(
    val trackId: String,
    val stream: MusicStream,
    val player: ExoPlayer,
) {
    var alpha by mutableFloatStateOf(1f)
}

/**
 * The full-screen music player. Video uses a TextureView surface so two pictures can crossfade;
 * audio always crossfades, and the outgoing player is released after the overlap.
 */
@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val current by viewModel.player.current.collectAsStateWithLifecycle()
    val queue by viewModel.player.queue.collectAsStateWithLifecycle()
    val audioOnly by viewModel.player.audioOnly.collectAsStateWithLifecycle()
    val shuffle by viewModel.player.shuffle.collectAsStateWithLifecycle()
    val repeat by viewModel.player.repeat.collectAsStateWithLifecycle()
    val favorites by viewModel.library.favorites.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbacks = remember { mutableStateListOf<MusicPlayback>() }
    val active = playbacks.lastOrNull()
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    val lyricsState = rememberLazyListState()
    val scopeRef = rememberCoroutineScope()

    val resolved by produceState<Pair<String, Result<MusicStream>>?>(null, current?.id) {
        value = null
        val track = current ?: return@produceState
        value = track.id to viewModel.repository.resolve(track)
    }

    LaunchedEffect(current?.id) {
        if (current == null) onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            playbacks.forEach { runCatching { it.player.release() } }
            playbacks.clear()
        }
    }

    // Add the new track's player, then crossfade once it is ready.
    LaunchedEffect(current?.id, resolved) {
        val track = current ?: return@LaunchedEffect
        val resolution = resolved ?: return@LaunchedEffect
        if (resolution.first != track.id) return@LaunchedEffect
        val stream = resolution.second.getOrNull() ?: return@LaunchedEffect
        if (playbacks.any { it.trackId == track.id }) return@LaunchedEffect
        val player = com.nuvio.tv.music.MusicPlaybackFactory.create(context, stream, audioOnly)
        val playback = MusicPlayback(track.id, stream, player)
        playback.alpha = if (playbacks.isEmpty()) 1f else 0f
        player.volume = if (playbacks.isEmpty()) 1f else 0f
        playbacks.add(playback)
        error = null
        player.addListener(object : Player.Listener {
            override fun onPlayerError(playerError: androidx.media3.common.PlaybackException) {
                if (playbacks.lastOrNull() === playback) {
                    error = playerError.message ?: "Couldn't play this track."
                }
            }
        })
        player.prepare()
        player.play()
        val outgoing = playbacks.filter { it !== playback }
        if (outgoing.isNotEmpty()) {
            // Let the new stream buffer before touching the old one.
            withTimeoutOrNull(6_000L) {
                while (playback.player.playbackState != Player.STATE_READY &&
                    playback.player.playbackState != Player.STATE_ENDED
                ) {
                    delay(60)
                }
            }
            coroutineScope {
                launch {
                    animate(0f, 1f, animationSpec = tween(CROSSFADE_MS, easing = LinearEasing)) { value, _ ->
                        playback.alpha = value
                    }
                }
                launch { rampVolume(playback.player, 0f, 1f, CROSSFADE_MS) }
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
        }
    }

    // Keep the position, duration and play state in sync with the newest player.
    LaunchedEffect(active) {
        val player = active?.player ?: return@LaunchedEffect
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            if (player.playbackState == Player.STATE_ENDED) {
                when (repeat) {
                    MusicRepeatMode.ONE -> {
                        player.seekTo(0L)
                        player.play()
                    }
                    else -> viewModel.player.advanceOnEnd()
                }
            }
            delay(400L)
        }
    }

    BackHandler {
        viewModel.player.close()
        onBack()
    }

    val track = current
    if (track == null) {
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background))
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause -> {
                        active?.player?.let { if (it.isPlaying) it.pause() else it.play() }
                        true
                    }
                    Key.MediaNext -> {
                        viewModel.player.next()
                        true
                    }
                    Key.MediaPrevious -> {
                        viewModel.player.previous()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Two overlapping surfaces: outgoing below, incoming above.
        playbacks.forEach { playback ->
            key(playback.trackId) {
                if (!audioOnly) {
                    LivePlayerSurface(
                        player = playback.player,
                        useSurfaceView = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = playback.alpha },
                    )
                }
            }
        }
        if (audioOnly) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.92f)),
                    ),
                ),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                    ),
                ),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 30.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Button(onClick = {
                    viewModel.player.close()
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = NuvioTheme.textStyles.headline,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(track.artist, track.album.takeIf { it.isNotBlank() && it != "Single" })
                            .filterNotNull().joinToString(" · "),
                        style = NuvioTheme.textStyles.body,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = { viewModel.player.setAudioOnly(!audioOnly) }) {
                    Text(if (audioOnly) "Audio" else "Video")
                }
            }
            Spacer(Modifier.weight(1f))
            if (error != null) {
                Text(error.orEmpty(), color = NuvioTheme.colors.Error, style = NuvioTheme.textStyles.body)
                Spacer(Modifier.height(8.dp))
            }
            if (showLyrics) {
                LyricsPanel(track, positionMs, viewModel, lyricsState, onClose = { showLyrics = false })
                Spacer(Modifier.height(12.dp))
            }
            if (showQueue) {
                QueuePanel(queue, viewModel, onClose = { showQueue = false })
                Spacer(Modifier.height(12.dp))
            }
            ProgressBar(positionMs, durationMs)
            Spacer(Modifier.height(12.dp))
            if (showControls) {
                Row(
                    Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicPlayerButton(Icons.Default.Shuffle, "Shuffle", active = shuffle) { viewModel.player.toggleShuffle() }
                    MusicPlayerButton(Icons.Default.SkipPrevious, "Previous") { viewModel.player.previous() }
                    MusicPlayerButton(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        big = true,
                    ) { active?.player?.let { if (it.isPlaying) it.pause() else it.play() } }
                    MusicPlayerButton(Icons.Default.SkipNext, "Next") { viewModel.player.next() }
                    MusicPlayerButton(
                        if (repeat == MusicRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        active = repeat != MusicRepeatMode.OFF,
                    ) { viewModel.player.cycleRepeat() }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val liked = favorites.any { it.id == track.id }
                    MusicPlayerButton(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (liked) "Liked" else "Like",
                        active = liked,
                    ) { viewModel.library.toggleFavorite(track) }
                    MusicPlayerButton(Icons.Default.Lyrics, "Lyrics", active = showLyrics) { showLyrics = !showLyrics }
                    MusicPlayerButton(Icons.Default.QueueMusic, "Queue", active = showQueue) { showQueue = !showQueue }
                    MusicPlayerButton(Icons.Default.Download, "Download") { viewModel.downloads.download(track) }
                    MusicPlayerButton(Icons.Default.LibraryAdd, "Playlist") { showPlaylists = true }
                }
            }
        }

        if (showPlaylists) {
            AddToPlaylistPanel(
                viewModel = viewModel,
                track = track,
                onDismiss = { showPlaylists = false },
            )
        }
    }
}

@Composable
private fun MusicPlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = if (big) Modifier.size(74.dp) else Modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (active) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.75f),
            contentColor = if (active) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = if (big) PaddingValues(0.dp) else PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(if (big) 34.dp else 20.dp))
        if (!big) {
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    }
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.BackgroundCard),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background(NuvioTheme.colors.TextPrimary),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            Text(formatMs(durationMs), style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun LyricsPanel(
    track: MusicTrack,
    positionMs: Long,
    viewModel: MusicViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClose: () -> Unit,
) {
    val lyrics by produceState<LyricsData?>(null, track.id) { value = viewModel.repository.lyrics(track) }
    val data = lyrics
    val activeLine = data?.activeLineIndex(positionMs) ?: -1
    LaunchedEffect(activeLine) {
        if (activeLine >= 0) runCatching { listState.animateScrollToItem(activeLine) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f),
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f),
        ),
        onClick = onClose,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Lyrics", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Close", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
            when {
                data == null -> Text("Loading lyrics…", color = NuvioTheme.colors.TextSecondary)
                data.isEmpty -> Text("No lyrics found for this song.", color = NuvioTheme.colors.TextSecondary)
                data.isSynced -> LazyColumn(state = listState) {
                    itemsIndexed(data.syncedLines) { index, line ->
                        Text(
                            line.text.ifBlank { " " },
                            style = NuvioTheme.textStyles.body,
                            color = if (index == activeLine) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
                            fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
                else -> LazyColumn {
                    itemsIndexed(data.plainLyrics.lines()) { _, line ->
                        Text(line, style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(queue: List<MusicTrack>, viewModel: MusicViewModel, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f),
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f),
        ),
        onClick = onClose,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue · ${queue.size}", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Close", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
            LazyColumn {
                itemsIndexed(queue, key = { _, track -> track.id }) { index, item ->
                    Button(
                        onClick = { viewModel.player.playFromQueue(index) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Text("${index + 1}. ${item.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToPlaylistPanel(viewModel: MusicViewModel, track: MusicTrack, onDismiss: () -> Unit) {
    val playlists by viewModel.library.playlists.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.7f),
            shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            ),
            onClick = onDismiss,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add to playlist", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
            Button(onClick = {
                val playlist = viewModel.library.createPlaylist("My Playlist ${playlists.size + 1}")
                viewModel.library.addToPlaylist(playlist.id, track)
                onDismiss()
            }) { Text("New playlist") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                    Button(
                        onClick = {
                            viewModel.library.addToPlaylist(playlist.id, track)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Text("${playlist.title} · ${playlist.tracks.size} songs", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("Press Back or select the panel to close", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

private suspend fun rampVolume(player: ExoPlayer, from: Float, to: Float, durationMs: Int) {
    val steps = (durationMs / 40).coerceAtLeast(1)
    val start = from.coerceIn(0f, 1f)
    val end = to.coerceIn(0f, 1f)
    for (step in 0..steps) {
        val fraction = step.toFloat() / steps
        player.volume = start + (end - start) * fraction
        delay(40L)
    }
}
