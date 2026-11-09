@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.multiview

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Up to six live channels at once. The quick Multi-view button in the player adds the channel
 * on screen; the picker adds more from Favorites, Recently watched and the channel list.
 */
@Composable
fun LiveTvMultiViewScreen(
    onBack: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val streams by MultiViewRepository.streams.collectAsStateWithLifecycle()
    val audibleId by MultiViewRepository.audibleId.collectAsStateWithLifecycle()
    val soundAll by MultiViewRepository.soundAll.collectAsStateWithLifecycle()
    val pauseAll by MultiViewRepository.pauseAll.collectAsStateWithLifecycle()
    val channels by viewModel.displayChannels.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(streams.isEmpty()) }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 32.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Multi-view", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                Text(
                    "${streams.size} of ${MultiViewRepository.MAX_STREAMS} screens · select a screen to hear it",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            Button(onClick = { MultiViewRepository.setSoundAll(!soundAll) }) {
                Icon(
                    if (soundAll) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(if (soundAll) "All sound" else "One sound")
            }
            Button(onClick = { MultiViewRepository.setPaused(!pauseAll) }) {
                Icon(
                    if (pauseAll) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(if (pauseAll) "Resume all" else "Pause all")
            }
            Button(onClick = { showPicker = true }, enabled = streams.size < MultiViewRepository.MAX_STREAMS) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Add channel")
            }
            Button(onClick = { MultiViewRepository.clear() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Clear")
            }
        }
        Spacer(Modifier.height(14.dp))
        if (streams.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No screens yet.", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pick up to six channels with Add channel, or press Multi-view while watching one.",
                        color = NuvioTheme.colors.TextSecondary,
                        style = NuvioTheme.textStyles.body,
                    )
                }
            }
        } else {
            MultiViewGrid(
                streams = streams,
                audibleId = audibleId,
                soundAll = soundAll,
                pauseAll = pauseAll,
                onSelectAudible = { MultiViewRepository.setAudible(it) },
            )
        }
    }

    if (showPicker) {
        ChannelPicker(
            channels = channels,
            favoriteKeys = user.favorites.toSet(),
            recentKeys = user.recent.toSet(),
            onPick = { channel ->
                MultiViewRepository.add(channel)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun MultiViewGrid(
    streams: List<MultiViewStream>,
    audibleId: String?,
    soundAll: Boolean,
    pauseAll: Boolean,
    onSelectAudible: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowStart in 0 until MultiViewRepository.MAX_STREAMS step 3) {
            val rowStreams = streams.drop(rowStart).take(3)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 0 until 3) {
                    val stream = rowStreams.getOrNull(column)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (stream != null) {
                            MultiViewPane(
                                stream = stream,
                                audible = soundAll || stream.id == audibleId,
                                paused = pauseAll,
                                onClick = { onSelectAudible(stream.id) },
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.04f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiViewPane(
    stream: MultiViewStream,
    audible: Boolean,
    paused: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember(stream.id) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(stream.id) { mutableStateOf(false) }

    LaunchedEffect(stream.id) {
        failed = false
        val created = buildLivePlayer(context, stream)
        created.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true
            }
        })
        created.prepare()
        created.play()
        player = created
    }

    LaunchedEffect(player, audible, paused) {
        val current = player ?: return@LaunchedEffect
        current.volume = if (audible) 1f else 0f
        if (paused) current.pause() else current.play()
    }

    DisposableEffect(stream.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = Color.Black,
            focusedContainerColor = Color.Black,
        ),
        border = CardDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.0f),
    ) {
        Box(Modifier.fillMaxSize()) {
            LivePlayerSurface(
                player = player,
                useSurfaceView = true,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stream.logo != null) {
                    AsyncImage(
                        model = stream.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stream.title,
                        style = NuvioTheme.textStyles.cardTitle,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (audible) "Sound on" else stream.subtitle.orEmpty(),
                        style = NuvioTheme.textStyles.metadata,
                        color = if (audible) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (failed) {
                    Text("Retrying…", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.Warning)
                }
            }
        }
    }

    if (failed) {
        LaunchedEffect(stream.id, failed) {
            if (!failed) return@LaunchedEffect
            delay(2500)
            player?.let { p ->
                p.prepare()
                p.play()
            }
        }
    }
}

private fun buildLivePlayer(context: android.content.Context, stream: MultiViewStream): ExoPlayer {
    val headers = stream.headers
    val dataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(headers["User-Agent"] ?: "NuvioTV")
        .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val mediaSource = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(dataSource)
        .createMediaSource(MediaItem.fromUri(stream.url))
    val player = ExoPlayer.Builder(context).build()
    val attrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    player.setAudioAttributes(attrs, false)
    player.setMediaSource(mediaSource)
    return player
}

@Composable
private fun ChannelPicker(
    channels: List<LiveChannel>,
    favoriteKeys: Set<String>,
    recentKeys: Set<String>,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = remember(channels, favoriteKeys, recentKeys) {
        val byKey = channels.associateBy { it.key }
        (favoriteKeys.mapNotNull { byKey[it] } + recentKeys.mapNotNull { byKey[it] } + channels)
            .distinctBy { it.key }
            .take(120)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.8f),
            shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            ),
            onClick = onDismiss,
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a channel", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                    Text("Back to close", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                }
                Spacer(Modifier.height(10.dp))
                if (ordered.isEmpty()) {
                    Text("Channels are still loading.", color = NuvioTheme.colors.TextSecondary)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(ordered, key = { it.key }) { channel ->
                            Button(
                                onClick = { onPick(channel) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextPrimary,
                                ),
                            ) {
                                Text(
                                    "${channel.number}  ${channel.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(channel.group, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
