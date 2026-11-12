@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.youtube

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.music.MusicPlaybackFactory
import com.nuvio.tv.music.MusicStream
import com.nuvio.tv.music.YouTubeMusicService
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalPictureInPicture
import com.nuvio.tv.youtube.YtComment
import com.nuvio.tv.youtube.YtCommentPage
import com.nuvio.tv.youtube.YtVideoInfo
import com.nuvio.tv.youtube.YouTubePlaybackSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeVideoViewModel @Inject constructor(
    val youtube: YouTubeMusicService,
    val history: YouTubeHistoryRepository,
) : ViewModel()

private enum class YtPanel { INFO, MORE, COMMENTS }

/** How far one D-pad press jumps in the progress bar. */
private const val YT_SEEK_STEP_MS = 10_000L

/** The seek bar's place in the control ring, after the row of panel buttons. */
private const val YT_SEEK_CONTROL = 5

/** A full-screen YouTube video with the info, source-list and comments panels. */
@Composable
fun YouTubeVideoScreen(
    videoId: String,
    title: String,
    onBack: () -> Unit,
    viewModel: YouTubeVideoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val pip = LocalPictureInPicture.current
    // The activity already asks for this, but setting it on the screen's own view also covers
    // devices whose screen saver ignores the window flag while a video is playing.
    KeepScreenAwake(true)
    val initialEntry = remember(videoId) {
        YouTubePlaybackSession.current(videoId)
            ?: YouTubePlaybackSession.Entry(videoId, title, null)
    }
    var current by remember(videoId) { mutableStateOf(initialEntry) }
    var advanceTo by remember { mutableStateOf<YouTubePlaybackSession.Entry?>(null) }

    val resolved by produceState<Result<MusicStream>?>(null, current.videoId) {
        value = viewModel.youtube.resolve(current.videoId)
    }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playingEntry by remember { mutableStateOf(initialEntry) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<YtPanel?>(null) }
    var info by remember { mutableStateOf<YtVideoInfo?>(null) }
    var infoLoading by remember { mutableStateOf(false) }
    var infoError by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    var controlIndex by remember { mutableIntStateOf(2) }

    fun revealControls() {
        showControls = true
        controlsTick++
        controlIndex = 2
    }

    /** Moves the video by [deltaMs] and keeps the seek bar focused so the user can keep scrubbing. */
    fun seekBy(deltaMs: Long) {
        player?.let { p ->
            val duration = if (durationMs > 0L) durationMs else p.duration.coerceAtLeast(0L)
            val target = (p.currentPosition + deltaMs).coerceAtLeast(0L).let {
                if (duration > 0L) it.coerceAtMost(duration) else it
            }
            p.seekTo(target)
            positionMs = target
        }
        showControls = true
        controlsTick++
        controlIndex = YT_SEEK_CONTROL
    }

    fun activateControl(index: Int) {
        when (index) {
            0 -> onBack()
            1 -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            2 -> panel = if (panel == YtPanel.INFO) null else YtPanel.INFO
            3 -> panel = if (panel == YtPanel.MORE) null else YtPanel.MORE
            4 -> panel = if (panel == YtPanel.COMMENTS) null else YtPanel.COMMENTS
            YT_SEEK_CONTROL -> player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        if (index in 2..4) revealControls()
        if (index == YT_SEEK_CONTROL) {
            showControls = true
            controlsTick++
        }
    }

    fun switchTo(entry: YouTubePlaybackSession.Entry) {
        if (entry.videoId == current.videoId) {
            panel = null
            return
        }
        player = null
        current = entry
        panel = null
        revealControls()
    }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(showControls, controlsTick, panel) {
        if (showControls && panel == null) {
            delay(5_000)
            showControls = false
        } else if (!showControls) {
            delay(30)
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(showControls, panel, current.videoId) {
        if (panel == null) {
            delay(40)
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
        playingEntry = current
        player = created
    }

    LaunchedEffect(player) {
        val currentPlayer = player ?: return@LaunchedEffect
        var saveTick = 0
        while (true) {
            isPlaying = currentPlayer.isPlaying
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = currentPlayer.duration.coerceAtLeast(0L)
            if (++saveTick >= 12 && durationMs > 0L) {
                saveTick = 0
                viewModel.history.record(
                    playingEntry.videoId,
                    playingEntry.title.ifBlank { title },
                    positionMs,
                    durationMs,
                )
            }
            if (currentPlayer.playbackState == Player.STATE_ENDED && advanceTo == null) {
                val next = YouTubePlaybackSession.next(current.videoId)
                if (next != null) advanceTo = next else revealControls()
            }
            delay(400L)
        }
    }

    LaunchedEffect(advanceTo) {
        val next = advanceTo ?: return@LaunchedEffect
        advanceTo = null
        player = null
        current = next
        panel = null
        revealControls()
    }

    val currentPlayer = player
    DisposableEffect(currentPlayer) {
        onDispose {
            currentPlayer?.let { p ->
                val duration = p.duration.coerceAtLeast(0L)
                val position = p.currentPosition.coerceAtLeast(0L)
                if (duration > 0L) {
                    viewModel.history.record(playingEntry.videoId, playingEntry.title.ifBlank { title }, position, duration)
                }
                p.release()
            }
            if (player === currentPlayer) player = null
        }
    }

    LaunchedEffect(panel, current.videoId) {
        if (panel == YtPanel.INFO) {
            infoLoading = true
            infoError = null
            info = null
            viewModel.youtube.videoInfo(current.videoId)
                .onSuccess { info = it }
                .onFailure { infoError = it.message }
            infoLoading = false
        }
    }

    BackHandler {
        if (panel != null) panel = null else onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable(enabled = panel == null)
            .onPreviewKeyEvent { event ->
                if (panel != null) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val code = event.nativeKeyEvent.keyCode
                    // Dedicated rewind/fast-forward buttons work even when the controls are hidden.
                    if (code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
                        code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                    ) {
                        seekBy(if (code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD) YT_SEEK_STEP_MS else -YT_SEEK_STEP_MS)
                        return@onPreviewKeyEvent true
                    }
                    controlsTick++
                    if (!showControls) {
                        // Left/right scrubs straight away and leaves the seek bar focused;
                        // any other key just brings the controls back.
                        when (event.key) {
                            Key.DirectionLeft -> seekBy(-YT_SEEK_STEP_MS)
                            Key.DirectionRight -> seekBy(YT_SEEK_STEP_MS)
                            else -> {
                                showControls = true
                                controlIndex = 2
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        code == AndroidKeyEvent.KEYCODE_ENTER ||
                        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        activateControl(controlIndex)
                        return@onPreviewKeyEvent true
                    }
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (controlIndex == YT_SEEK_CONTROL) {
                                seekBy(-YT_SEEK_STEP_MS)
                            } else {
                                controlIndex = if (controlIndex < 2) (controlIndex - 1).coerceAtLeast(0)
                                else (controlIndex - 1).coerceAtLeast(2)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (controlIndex == YT_SEEK_CONTROL) {
                                seekBy(YT_SEEK_STEP_MS)
                            } else {
                                controlIndex = if (controlIndex < 2) (controlIndex + 1).coerceAtMost(1)
                                else (controlIndex + 1).coerceAtMost(4)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            when {
                                controlIndex == YT_SEEK_CONTROL -> controlIndex = 3
                                controlIndex in 2..4 -> controlIndex -= 2
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            when {
                                controlIndex == 0 -> controlIndex = 2
                                controlIndex == 1 -> controlIndex = 3
                                controlIndex in 2..4 -> controlIndex = YT_SEEK_CONTROL
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            activateControl(controlIndex)
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                }
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                controlsTick++
                if (event.key == Key.MediaPlayPause) {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    true
                } else {
                    false
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
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                ),
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YtControl("Back", Icons.Default.ArrowBack, focused = controlIndex == 0, onClick = { activateControl(0) })
                    Text(
                        current.title.ifBlank { title },
                        style = NuvioTheme.textStyles.sectionTitle,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    YtControl(
                        null,
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        focused = controlIndex == 1,
                        modifier = Modifier.size(50.dp),
                        onClick = { activateControl(1) },
                    )
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
                // Info / More videos / Comments, centred just above the progress bar.
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    YtControl(
                        "Info",
                        Icons.Default.Info,
                        focused = controlIndex == 2,
                        selected = panel == YtPanel.INFO,
                        onClick = { activateControl(2) },
                    )
                    Spacer(Modifier.width(10.dp))
                    YtControl(
                        "More videos",
                        Icons.Default.VideoLibrary,
                        focused = controlIndex == 3,
                        selected = panel == YtPanel.MORE,
                        onClick = { activateControl(3) },
                    )
                    Spacer(Modifier.width(10.dp))
                    YtControl(
                        "Comments",
                        Icons.Default.Comment,
                        focused = controlIndex == 4,
                        selected = panel == YtPanel.COMMENTS,
                        onClick = { activateControl(4) },
                    )
                }
                if (durationMs > 0L) {
                    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    val seekFocused = controlIndex == YT_SEEK_CONTROL
                    Box(
                        Modifier.fillMaxWidth()
                            .height(if (seekFocused) 10.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = if (seekFocused) 0.35f else 0.25f))
                            .then(
                                if (seekFocused) {
                                    Modifier.border(2.dp, NuvioTheme.colors.TextPrimary, RoundedCornerShape(50))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .background(NuvioTheme.colors.TextPrimary, RoundedCornerShape(50)),
                        )
                    }
                    Spacer(Modifier.height(if (seekFocused) 8.dp else 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                            style = NuvioTheme.textStyles.metadata,
                            color = if (seekFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        if (seekFocused) {
                            Text(
                                "◀  10s      ▶  10s",
                                style = NuvioTheme.textStyles.metadata,
                                color = NuvioTheme.colors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        val openPanel = panel
        if (openPanel != null) {
            YouTubeSidePanel(
                panel = openPanel,
                current = current,
                info = info,
                infoLoading = infoLoading,
                infoError = infoError,
                youtube = viewModel.youtube,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.30f).align(Alignment.CenterEnd),
                onClose = { panel = null },
                onPick = { switchTo(it) },
            )
        }
    }
}

@Composable
private fun YtControl(
    label: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focused: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val highlight = focused || selected
    val background = if (highlight) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f)
    val content = if (highlight) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        } else {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(label, fontSize = 13.sp, color = content)
            }
        }
    }
}

@Composable
private fun YouTubeSidePanel(
    panel: YtPanel,
    current: YouTubePlaybackSession.Entry,
    info: YtVideoInfo?,
    infoLoading: Boolean,
    infoError: String?,
    youtube: YouTubeMusicService,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPick: (YouTubePlaybackSession.Entry) -> Unit,
) {
    Box(modifier.background(Color.Black.copy(alpha = 0.40f))) {
        when (panel) {
            YtPanel.INFO -> VideoInfoPanel(current, info, infoLoading, infoError, onClose)
            YtPanel.MORE -> MoreVideosPanel(current, onPick, onClose)
            YtPanel.COMMENTS -> CommentsPanel(current.videoId, youtube, onClose)
        }
    }
}

@Composable
private fun VideoInfoPanel(
    current: YouTubePlaybackSession.Entry,
    info: YtVideoInfo?,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val closeFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { panelFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize()
            .focusRequester(panelFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) {
                    scope.launch { listState.scrollBy(if (e.key == Key.DirectionDown) 140f else -140f) }
                    true
                } else false
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Info", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) { Text("Close") }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            item {
                Text(current.title, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Text("Loading video info…", color = NuvioTheme.colors.TextSecondary)
                } else if (error != null) {
                    Text(error, color = NuvioTheme.colors.Warning)
                } else {
                    val meta = info
                    if (meta != null) {
                        Text(
                            listOfNotNull(
                                meta.author,
                                meta.viewCount?.let { "$it views" },
                                meta.publishedDate,
                            ).joinToString(" · "),
                            style = NuvioTheme.textStyles.metadata,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                        meta.likeCount?.let {
                            Spacer(Modifier.height(6.dp))
                            Text("$it likes", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            meta.description?.takeIf { it.isNotBlank() } ?: "No description.",
                            style = NuvioTheme.textStyles.body,
                            color = NuvioTheme.colors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreVideosPanel(
    current: YouTubePlaybackSession.Entry,
    onPick: (YouTubePlaybackSession.Entry) -> Unit,
    onClose: () -> Unit,
) {
    val entries = remember(current.videoId) {
        YouTubePlaybackSession.all().ifEmpty { listOf(current) }
    }
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(entries) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("More videos", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text("Close") }
        }
        if (entries.size <= 1) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No other videos in this list.", color = NuvioTheme.colors.TextSecondary)
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> e.videoId }) { index, entry ->
                Button(
                    onClick = { onPick(entry) },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(8.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = if (entry.videoId == current.videoId) NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.8f) else Color.Transparent,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    AsyncImage(
                        model = entry.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(104.dp).height(59.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        entry.author?.takeIf { it.isNotBlank() }?.let {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentsPanel(
    videoId: String,
    youtube: YouTubeMusicService,
    onClose: () -> Unit,
) {
    var comments by remember(videoId) { mutableStateOf<List<YtComment>>(emptyList()) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var replyTo by remember(videoId) { mutableStateOf<YtComment?>(null) }
    var replies by remember(videoId) { mutableStateOf<List<YtComment>>(emptyList()) }
    var repliesLoading by remember(videoId) { mutableStateOf(false) }
    var repliesError by remember(videoId) { mutableStateOf<String?>(null) }

    val firstCommentFocus = remember { FocusRequester() }
    val firstReplyFocus = remember { FocusRequester() }

    LaunchedEffect(videoId) {
        loading = true
        error = null
        youtube.comments(videoId)
            .onSuccess {
                comments = it.comments
            }
            .onFailure { error = it.message ?: "Couldn't load comments." }
        loading = false
    }
    LaunchedEffect(replyTo) {
        val comment = replyTo ?: return@LaunchedEffect
        val key = comment.nestedKey ?: return@LaunchedEffect
        repliesLoading = true
        repliesError = null
        replies = emptyList()
        youtube.replies(key)
            .onSuccess { replies = it.comments }
            .onFailure { repliesError = it.message ?: "Couldn't load replies." }
        repliesLoading = false
    }
    LaunchedEffect(replyTo, loading) {
        delay(90)
        runCatching {
            if (replyTo == null) firstCommentFocus.requestFocus() else firstReplyFocus.requestFocus()
        }
    }
    BackHandler(enabled = replyTo == null) { onClose() }
    BackHandler(enabled = replyTo != null) { replyTo = null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (replyTo != null) {
                Button(
                    onClick = { replyTo = null },
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Comments", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (replyTo == null) "Comments" else "Replies",
                style = NuvioTheme.textStyles.sectionTitle,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onClose) { Text("Close") }
        }
        if (replyTo == null) {
            when {
                loading -> LoadingText("Loading comments…")
                error != null -> LoadingText(error.orEmpty())
                comments.isEmpty() -> LoadingText("No comments yet.")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(comments, key = { _, c -> c.id.ifBlank { c.hashCode().toString() } }) { index, comment ->
                        CommentRow(
                            comment = comment,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstCommentFocus) else Modifier),
                            onClick = { if (comment.nestedKey != null) replyTo = comment },
                        )
                    }
                }
            }
        } else {
            when {
                repliesLoading -> LoadingText("Loading replies…")
                repliesError != null -> LoadingText(repliesError.orEmpty())
                replies.isEmpty() -> LoadingText("No replies.")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(replies, key = { _, c -> c.id.ifBlank { c.hashCode().toString() } }) { index, reply ->
                        CommentRow(
                            comment = reply,
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstReplyFocus) else Modifier),
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
    }
}

@Composable
private fun CommentRow(
    comment: YtComment,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(10.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        AsyncImage(
            model = comment.authorPhoto,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author.orEmpty(),
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                comment.published?.let {
                    Text("  ·  $it", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(comment.message, style = NuvioTheme.textStyles.bodyCompact, color = NuvioTheme.colors.TextPrimary, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                comment.likeCount?.takeIf { it.isNotBlank() }?.let {
                    Text("👍 $it", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextTertiary)
                }
                comment.replyCount?.takeIf { it.isNotBlank() }?.let {
                    Text("Replies $it", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
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
