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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import com.nuvio.tv.youtube.YtPlaylist
import com.nuvio.tv.youtube.YtVideoInfo
import com.nuvio.tv.youtube.YouTubePlaybackSession
import com.nuvio.tv.youtube.YouTubeProgress
import com.nuvio.tv.youtube.YouTubeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeVideoViewModel @Inject constructor(
    val youtube: YouTubeMusicService,
    val history: YouTubeHistoryRepository,
) : ViewModel()

private enum class YtPanel { INFO, MORE, COMMENTS, SAVE, QUALITY }

/** How far one D-pad press jumps in the progress bar. */
private const val YT_SEEK_STEP_MS = 10_000L

// The bottom control ring, left to right: Last, Subscribe, Watch later, Quality, Info, More,
// Comments, Like, Dislike, Next. The seek bar sits below it.
private const val YT_LAST_VIDEO_CONTROL = 2
private const val YT_SUBSCRIBE_CONTROL = 3
private const val YT_WATCH_LATER_CONTROL = 4
private const val YT_QUALITY_CONTROL = 5
private const val YT_INFO_CONTROL = 6
private const val YT_MORE_CONTROL = 7
private const val YT_COMMENTS_CONTROL = 8
private const val YT_LIKE_CONTROL = 9
private const val YT_DISLIKE_CONTROL = 10
private const val YT_NEXT_VIDEO_CONTROL = 11
private const val YT_SEEK_CONTROL = 12
private const val YT_FIRST_BOTTOM_CONTROL = YT_LAST_VIDEO_CONTROL
private const val YT_LAST_BOTTOM_CONTROL = YT_NEXT_VIDEO_CONTROL
private const val YT_BOTTOM_HALF = (YT_FIRST_BOTTOM_CONTROL + YT_LAST_BOTTOM_CONTROL) / 2

private const val YT_REACTION_NONE = 0
private const val YT_REACTION_LIKE = 1
private const val YT_REACTION_DISLIKE = 2

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
    var info by remember(current.videoId) { mutableStateOf<YtVideoInfo?>(null) }
    var infoLoading by remember(current.videoId) { mutableStateOf(false) }
    var infoError by remember(current.videoId) { mutableStateOf<String?>(null) }
    var likeStatus by remember(current.videoId) { mutableIntStateOf(YT_REACTION_NONE) }
    var reactionTouched by remember(current.videoId) { mutableStateOf(false) }
    var reactionBusy by remember(current.videoId) { mutableStateOf(false) }
    var channelId by remember(current.videoId) { mutableStateOf<String?>(null) }
    var subscribed by remember(current.videoId) { mutableStateOf(false) }
    var subscribeTouched by remember(current.videoId) { mutableStateOf(false) }
    var subscribeBusy by remember(current.videoId) { mutableStateOf(false) }
    var qualityHeight by remember(current.videoId) { mutableIntStateOf(YouTubeSettings.preferredHeight(context)) }
    var qualityOptions by remember { mutableStateOf<List<YouTubeQuality>>(emptyList()) }
    var qualityApplied by remember { mutableStateOf<Int?>(null) }
    var endHandled by remember { mutableStateOf(false) }
    var nextBusy by remember { mutableStateOf(false) }
    var extraHistory by remember { mutableStateOf<List<YouTubePlaybackSession.Entry>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    var controlIndex by remember { mutableIntStateOf(YT_INFO_CONTROL) }

    fun touchControls() {
        showControls = true
        controlsTick++
    }

    fun revealControls() {
        touchControls()
        controlIndex = YT_INFO_CONTROL
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
        touchControls()
        controlIndex = YT_SEEK_CONTROL
    }

    fun switchTo(entry: YouTubePlaybackSession.Entry, rememberCurrent: Boolean = false) {
        if (entry.videoId == current.videoId) {
            panel = null
            return
        }
        // Videos picked through YouTube's suggestions aren't in the opened list; keep the way
        // back so Last still returns to the video the user came from.
        if (rememberCurrent) extraHistory = (extraHistory + current).takeLast(50)
        player = null
        current = entry
        panel = null
        revealControls()
    }

    fun goPrevious() {
        val remembered = extraHistory.lastOrNull()
        val previous = remembered ?: YouTubePlaybackSession.previous(current.videoId)
        if (previous == null) {
            notice = "No previous video in this list."
            touchControls()
            return
        }
        if (remembered != null) extraHistory = extraHistory.dropLast(1)
        switchTo(previous)
    }

    fun goNext() {
        val next = YouTubePlaybackSession.next(current.videoId)
        if (next != null) {
            switchTo(next)
            return
        }
        if (nextBusy) return
        nextBusy = true
        val from = current
        scope.launch {
            val found = viewModel.youtube.nextEntry(from.videoId)
            nextBusy = false
            if (current.videoId != from.videoId) return@launch
            if (found == null) {
                notice = "No next video was found."
                touchControls()
            } else {
                switchTo(found, rememberCurrent = true)
            }
        }
    }

    fun react(target: Int) {
        if (reactionBusy) return
        if (!viewModel.youtube.isSignedIn()) {
            notice = "Sign in to YouTube to like or dislike videos."
            touchControls()
            return
        }
        val from = current.videoId
        val previous = likeStatus
        val next = if (previous == target) YT_REACTION_NONE else target
        likeStatus = next
        reactionTouched = true
        reactionBusy = true
        touchControls()
        scope.launch {
            viewModel.youtube.setReaction(from, previous, next).onFailure {
                if (current.videoId == from) {
                    likeStatus = previous
                    notice = it.message ?: "Couldn't update your reaction."
                }
            }
            reactionBusy = false
        }
    }

    fun toggleSubscription() {
        if (subscribeBusy) return
        val channel = channelId
        if (channel.isNullOrBlank()) {
            notice = "The channel isn't available yet."
            touchControls()
            return
        }
        if (!viewModel.youtube.isSignedIn()) {
            notice = "Sign in to YouTube to subscribe."
            touchControls()
            return
        }
        val from = current.videoId
        val next = !subscribed
        subscribed = next
        subscribeTouched = true
        subscribeBusy = true
        touchControls()
        scope.launch {
            viewModel.youtube.setSubscribed(channel, next).onFailure {
                if (current.videoId == from) {
                    subscribed = !next
                    notice = it.message ?: "Couldn't update the subscription."
                }
            }
            subscribeBusy = false
        }
    }

    fun chooseQuality(height: Int) {
        YouTubeSettings.setPreferredHeight(context, height)
        qualityHeight = height
        qualityApplied = null
        panel = null
        player?.let { p -> if (p.applyYouTubeQuality(height)) qualityApplied = height }
        touchControls()
        notice = if (height <= 0) "Quality: Auto" else "Quality: ${height}p"
    }

    fun activateControl(index: Int) {
        touchControls()
        when (index) {
            0 -> onBack()
            1 -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            YT_LAST_VIDEO_CONTROL -> goPrevious()
            YT_SUBSCRIBE_CONTROL -> toggleSubscription()
            YT_WATCH_LATER_CONTROL -> panel = if (panel == YtPanel.SAVE) null else YtPanel.SAVE
            YT_QUALITY_CONTROL -> panel = if (panel == YtPanel.QUALITY) null else YtPanel.QUALITY
            YT_INFO_CONTROL -> panel = if (panel == YtPanel.INFO) null else YtPanel.INFO
            YT_MORE_CONTROL -> panel = if (panel == YtPanel.MORE) null else YtPanel.MORE
            YT_COMMENTS_CONTROL -> panel = if (panel == YtPanel.COMMENTS) null else YtPanel.COMMENTS
            YT_LIKE_CONTROL -> react(YT_REACTION_LIKE)
            YT_DISLIKE_CONTROL -> react(YT_REACTION_DISLIKE)
            YT_NEXT_VIDEO_CONTROL -> goNext()
            YT_SEEK_CONTROL -> player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
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
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            notice = null
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
        // Pick up where this video was left, on this device first and then from the account.
        val resumeMs = if (!YouTubeSettings.resumePlayback(context)) {
            0L
        } else {
            viewModel.history.resumePositionFor(current.videoId).let { saved ->
                if (saved > 0L) {
                    saved
                } else {
                    YouTubeProgress.localState(context, current.videoId)?.let { (pos, dur) ->
                        val percent = if (dur > 0L) (pos * 100 / dur).toInt() else 0
                        if (percent in 3..94) pos else 0L
                    } ?: 0L
                }
            }
        }
        created.prepare()
        if (resumeMs > 0L) created.seekTo(resumeMs)
        created.play()
        positionMs = resumeMs
        playingEntry = current
        player = created
    }

    LaunchedEffect(player) {
        val currentPlayer = player ?: return@LaunchedEffect
        var saveTick = 0
        qualityApplied = null
        qualityOptions = emptyList()
        endHandled = false
        while (true) {
            isPlaying = currentPlayer.isPlaying
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = currentPlayer.duration.coerceAtLeast(0L)
            val options = currentPlayer.youtubeQualities()
            if (options != qualityOptions) qualityOptions = options
            if (qualityApplied != qualityHeight && currentPlayer.applyYouTubeQuality(qualityHeight)) {
                qualityApplied = qualityHeight
            }
            if (++saveTick >= 12 && durationMs > 0L) {
                saveTick = 0
                viewModel.history.record(
                    playingEntry.videoId,
                    playingEntry.title.ifBlank { title },
                    positionMs,
                    durationMs,
                )
                YouTubeProgress.record(
                    context,
                    YouTubeProgress.ID_PREFIX + playingEntry.videoId,
                    positionMs,
                    durationMs,
                )
            }
            if (currentPlayer.playbackState == Player.STATE_ENDED && !endHandled) {
                endHandled = true
                if (YouTubeSettings.autoplayNext(context)) goNext() else revealControls()
            }
            delay(400L)
        }
    }

    val currentPlayer = player
    DisposableEffect(currentPlayer) {
        onDispose {
            currentPlayer?.let { p ->
                val duration = p.duration.coerceAtLeast(0L)
                val position = p.currentPosition.coerceAtLeast(0L)
                if (duration > 0L) {
                    viewModel.history.record(playingEntry.videoId, playingEntry.title.ifBlank { title }, position, duration)
                    YouTubeProgress.record(
                        context,
                        YouTubeProgress.ID_PREFIX + playingEntry.videoId,
                        position,
                        duration,
                    )
                }
                p.release()
            }
            if (player === currentPlayer) player = null
        }
    }

    LaunchedEffect(current.videoId) {
        infoLoading = true
        infoError = null
        info = null
        viewModel.youtube.videoInfo(current.videoId)
            .onSuccess {
                info = it
                channelId = it.channelId
                if (!reactionTouched) likeStatus = it.likeStatus
                if (!subscribeTouched) subscribed = it.isSubscribed
            }
            .onFailure { infoError = it.message }
        infoLoading = false
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
                                controlIndex = YT_INFO_CONTROL
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
                            when {
                                controlIndex == YT_SEEK_CONTROL -> seekBy(-YT_SEEK_STEP_MS)
                                controlIndex == 1 -> controlIndex = 0
                                controlIndex in YT_FIRST_BOTTOM_CONTROL..YT_LAST_BOTTOM_CONTROL ->
                                    controlIndex = (controlIndex - 1).coerceAtLeast(YT_FIRST_BOTTOM_CONTROL)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            when {
                                controlIndex == YT_SEEK_CONTROL -> seekBy(YT_SEEK_STEP_MS)
                                controlIndex == 0 -> controlIndex = 1
                                controlIndex in YT_FIRST_BOTTOM_CONTROL..YT_LAST_BOTTOM_CONTROL ->
                                    controlIndex = (controlIndex + 1).coerceAtMost(YT_LAST_BOTTOM_CONTROL)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            when {
                                controlIndex == YT_SEEK_CONTROL -> controlIndex = YT_INFO_CONTROL
                                controlIndex in YT_FIRST_BOTTOM_CONTROL..YT_BOTTOM_HALF -> controlIndex = 0
                                controlIndex in (YT_BOTTOM_HALF + 1)..YT_LAST_BOTTOM_CONTROL -> controlIndex = 1
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            when {
                                controlIndex == 0 -> controlIndex = YT_LAST_VIDEO_CONTROL
                                controlIndex == 1 -> controlIndex = YT_NEXT_VIDEO_CONTROL
                                controlIndex in YT_FIRST_BOTTOM_CONTROL..YT_LAST_BOTTOM_CONTROL ->
                                    controlIndex = YT_SEEK_CONTROL
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
                    YtControl("Back", Icons.AutoMirrored.Filled.ArrowBack, focused = controlIndex == 0, onClick = { activateControl(0) })
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
                notice?.let {
                    Text(it, color = NuvioTheme.colors.TextPrimary, style = NuvioTheme.textStyles.body)
                    Spacer(Modifier.height(8.dp))
                }
                // Last / Subscribe / Watch later / Quality / Info / More / Comments / Like /
                // Dislike / Next, centred just above the progress bar.
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    YtControl(
                        "Last",
                        Icons.Default.SkipPrevious,
                        focused = controlIndex == YT_LAST_VIDEO_CONTROL,
                        onClick = { activateControl(YT_LAST_VIDEO_CONTROL) },
                    )
                    YtControl(
                        if (subscribed) "Subscribed" else "Subscribe",
                        if (subscribed) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                        focused = controlIndex == YT_SUBSCRIBE_CONTROL,
                        active = subscribed,
                        onClick = { activateControl(YT_SUBSCRIBE_CONTROL) },
                    )
                    YtControl(
                        "Watch later",
                        Icons.Default.WatchLater,
                        focused = controlIndex == YT_WATCH_LATER_CONTROL,
                        selected = panel == YtPanel.SAVE,
                        onClick = { activateControl(YT_WATCH_LATER_CONTROL) },
                    )
                    YtControl(
                        if (qualityHeight <= 0) "Auto" else "${qualityHeight}p",
                        Icons.Default.Tune,
                        focused = controlIndex == YT_QUALITY_CONTROL,
                        selected = panel == YtPanel.QUALITY,
                        active = qualityHeight > 0,
                        onClick = { activateControl(YT_QUALITY_CONTROL) },
                    )
                    YtControl(
                        "Info",
                        Icons.Default.Info,
                        focused = controlIndex == YT_INFO_CONTROL,
                        selected = panel == YtPanel.INFO,
                        onClick = { activateControl(YT_INFO_CONTROL) },
                    )
                    YtControl(
                        "More",
                        Icons.Default.VideoLibrary,
                        focused = controlIndex == YT_MORE_CONTROL,
                        selected = panel == YtPanel.MORE,
                        onClick = { activateControl(YT_MORE_CONTROL) },
                    )
                    YtControl(
                        "Comments",
                        Icons.Default.Comment,
                        focused = controlIndex == YT_COMMENTS_CONTROL,
                        selected = panel == YtPanel.COMMENTS,
                        onClick = { activateControl(YT_COMMENTS_CONTROL) },
                    )
                    YtControl(
                        null,
                        if (likeStatus == YT_REACTION_LIKE) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                        focused = controlIndex == YT_LIKE_CONTROL,
                        modifier = Modifier.size(36.dp),
                        active = likeStatus == YT_REACTION_LIKE,
                        onClick = { activateControl(YT_LIKE_CONTROL) },
                    )
                    YtControl(
                        null,
                        if (likeStatus == YT_REACTION_DISLIKE) Icons.Default.ThumbDown else Icons.Outlined.ThumbDown,
                        focused = controlIndex == YT_DISLIKE_CONTROL,
                        modifier = Modifier.size(36.dp),
                        active = likeStatus == YT_REACTION_DISLIKE,
                        onClick = { activateControl(YT_DISLIKE_CONTROL) },
                    )
                    YtControl(
                        "Next",
                        Icons.Default.SkipNext,
                        focused = controlIndex == YT_NEXT_VIDEO_CONTROL,
                        onClick = { activateControl(YT_NEXT_VIDEO_CONTROL) },
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
                qualities = qualityOptions,
                qualityHeight = qualityHeight,
                onQuality = ::chooseQuality,
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
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val highlight = focused || selected
    val background = if (highlight) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f)
    val content = when {
        highlight -> NuvioTheme.colors.TextInverse
        active -> NuvioTheme.colors.Secondary
        else -> NuvioTheme.colors.TextPrimary
    }
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
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 12.sp, color = content, maxLines = 1)
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
    qualities: List<YouTubeQuality>,
    qualityHeight: Int,
    onQuality: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onPick: (YouTubePlaybackSession.Entry) -> Unit,
) {
    Box(modifier.background(Color.Black.copy(alpha = 0.40f))) {
        when (panel) {
            YtPanel.INFO -> VideoInfoPanel(current, info, infoLoading, infoError, onClose)
            YtPanel.MORE -> MoreVideosPanel(current, onPick, onClose)
            YtPanel.COMMENTS -> CommentsPanel(current.videoId, youtube, onClose)
            YtPanel.SAVE -> SaveToPlaylistPanel(current.videoId, youtube, onClose)
            YtPanel.QUALITY -> QualityPanel(qualities, qualityHeight, onQuality, onClose)
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
private fun SaveToPlaylistPanel(
    videoId: String,
    youtube: YouTubeMusicService,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val signedIn = remember { youtube.isSignedIn() }
    var playlists by remember(videoId) { mutableStateOf<List<YtPlaylist>>(emptyList()) }
    var loading by remember(videoId) { mutableStateOf(signedIn) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var status by remember(videoId) { mutableStateOf<String?>(null) }
    var busy by remember(videoId) { mutableStateOf(false) }
    var creating by remember(videoId) { mutableStateOf(false) }
    var newName by remember(videoId) { mutableStateOf("") }
    val newNameFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }

    fun load() {
        if (!signedIn) return
        loading = true
        error = null
        scope.launch {
            youtube.playlists(videoId)
                .onSuccess { playlists = it }
                .onFailure { error = it.message ?: "Couldn't load your playlists." }
            loading = false
        }
    }

    LaunchedEffect(videoId) { load() }
    LaunchedEffect(creating) {
        if (creating) {
            delay(90)
            runCatching { newNameFocus.requestFocus() }
        }
    }
    LaunchedEffect(loading, creating, playlists.size) {
        if (!creating && !loading) {
            delay(90)
            runCatching { firstFocus.requestFocus() }
        }
    }
    BackHandler(enabled = creating) { creating = false }
    BackHandler(enabled = !creating) { onClose() }

    fun toggle(playlist: YtPlaylist) {
        if (busy) return
        busy = true
        status = null
        val add = !playlist.selected
        scope.launch {
            val result = if (add) {
                youtube.addToPlaylist(playlist.id, videoId)
            } else {
                youtube.removeFromPlaylist(playlist.id, videoId)
            }
            result
                .onSuccess {
                    playlists = playlists.map { if (it.id == playlist.id) it.copy(selected = add) else it }
                    status = if (add) "Added to ${playlist.title}" else "Removed from ${playlist.title}"
                }
                .onFailure { status = it.message ?: "Couldn't update ${playlist.title}." }
            busy = false
        }
    }

    fun create() {
        val name = newName.trim()
        if (name.isBlank() || busy) return
        busy = true
        status = null
        scope.launch {
            youtube.createPlaylist(name, videoId)
                .onSuccess {
                    status = "Created “$name” and added this video."
                    newName = ""
                    creating = false
                    load()
                }
                .onFailure { status = it.message ?: "Couldn't create the playlist." }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Save to playlist", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text("Close") }
        }
        status?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 14.dp),
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            !signedIn -> LoadingText("Sign in to YouTube to save videos to playlists.")
            creating -> NewPlaylistEditor(
                name = newName,
                onNameChange = { newName = it },
                busy = busy,
                focusRequester = newNameFocus,
                onCreate = { create() },
                onCancel = { creating = false },
            )
            loading -> LoadingText("Loading playlists…")
            error != null -> LoadingText(error.orEmpty())
            else -> {
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                        .then(if (playlists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(10.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("New playlist", fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                        val icon = when {
                            playlist.selected -> Icons.Default.Check
                            playlist.isWatchLater -> Icons.Default.WatchLater
                            else -> Icons.AutoMirrored.Filled.PlaylistPlay
                        }
                        Button(
                            onClick = { toggle(playlist) },
                            modifier = Modifier.fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            contentPadding = PaddingValues(10.dp),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = NuvioTheme.colors.TextPrimary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.TextPrimary,
                            ),
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                playlist.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (playlist.selected) {
                                Text("Added", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistEditor(
    name: String,
    onNameChange: (String) -> Unit,
    busy: Boolean,
    focusRequester: FocusRequester,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("New playlist", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
        Text(
            "Give the playlist a name, then press Create.",
            style = NuvioTheme.textStyles.metadata,
            color = NuvioTheme.colors.TextSecondary,
        )
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCreate() }),
            modifier = Modifier.fillMaxWidth().height(38.dp).focusRequester(focusRequester),
        ) { inner ->
            Row(
                Modifier.fillMaxSize()
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) {
                        Text(
                            "Playlist name",
                            style = NuvioTheme.textStyles.metadata,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                    inner()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate, enabled = name.isNotBlank() && !busy) { Text("Create", fontSize = 12.sp) }
            Button(onClick = onCancel) { Text("Cancel", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun QualityPanel(
    qualities: List<YouTubeQuality>,
    selectedHeight: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(qualities.size) {
        if (qualities.isNotEmpty()) {
            delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Quality", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text("Close") }
        }
        Text(
            "Auto follows your connection. A fixed size can use more data.",
            modifier = Modifier.padding(horizontal = 14.dp),
            style = NuvioTheme.textStyles.metadata,
            color = NuvioTheme.colors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        if (qualities.isEmpty()) {
            LoadingText("Reading the available resolutions…")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                item {
                    QualityRow(
                        title = "Auto",
                        detail = "Adapts to your connection",
                        selected = selectedHeight <= 0,
                        modifier = Modifier.focusRequester(firstFocus),
                        onClick = { onPick(0) },
                    )
                }
                itemsIndexed(qualities, key = { _, quality -> quality.height }) { _, quality ->
                    QualityRow(
                        title = quality.label,
                        detail = quality.summary,
                        selected = selectedHeight == quality.height,
                        onClick = { onPick(quality.height) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityRow(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(10.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.9f) else Color.Transparent,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(detail, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
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
