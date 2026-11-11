@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.multiview

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.AddonBrowserViewModel
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Up to four live channels at once. The quick Multi-view button in the player adds the channel
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
    val layout by MultiViewRepository.layout.collectAsStateWithLifecycle()
    val channels by viewModel.displayChannels.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val addonBrowserViewModel: AddonBrowserViewModel = hiltViewModel()
    var showPicker by remember { mutableStateOf(streams.isEmpty()) }
    var showLayouts by remember { mutableStateOf(false) }
    var swapMode by remember { mutableStateOf(false) }
    var swapFirst by remember { mutableStateOf<String?>(null) }
    var fullscreenId by remember { mutableStateOf<String?>(null) }
    var paneMenuFor by remember { mutableStateOf<MultiViewStream?>(null) }
    val volumes = remember { mutableStateMapOf<String, Int>() }
    val refreshTicks = remember { mutableStateMapOf<String, Int>() }
    val paneFocuses = remember { mutableMapOf<String, FocusRequester>() }
    val controlsFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var showControls by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }

    // Windows fill the screen after five seconds without a key; any key brings the bars back.
    LaunchedEffect(showControls, controlsTick, showPicker, showLayouts, paneMenuFor, swapMode) {
        if (showControls && !showPicker && !showLayouts && paneMenuFor == null && !swapMode) {
            delay(5_000)
            showControls = false
        }
    }
    LaunchedEffect(showControls) {
        if (showControls && !showPicker && !showLayouts && paneMenuFor == null && !swapMode) {
            delay(80)
            runCatching { controlsFocus.requestFocus() }
        } else if (!showControls) {
            delay(30)
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(showPicker, showLayouts, paneMenuFor) {
        if (showPicker || showLayouts || paneMenuFor != null) {
            showControls = true
            controlsTick++
        }
    }
    // Swapping is a mode, not a dead end: put the remote on a window as soon as it starts,
    // and back on the bar when it ends.
    LaunchedEffect(swapMode, swapFirst, streams) {
        delay(90)
        if (swapMode) {
            val target = swapFirst ?: streams.firstOrNull()?.id
            val firstOther = streams.firstOrNull { it.id != target }?.id
            firstOther?.let { paneFocuses[it] }?.let { runCatching { it.requestFocus() } }
        } else {
            runCatching { controlsFocus.requestFocus() }
        }
    }

    // After a sheet closes, put the remote back on the control bar (otherwise focus is lost).
    LaunchedEffect(showPicker, showLayouts, paneMenuFor, swapMode) {
        if (!showPicker && !showLayouts && paneMenuFor == null && !swapMode) {
            delay(120)
            runCatching { controlsFocus.requestFocus() }
        }
    }

    KeepScreenAwake(true)
    BackHandler {
        when {
            fullscreenId != null -> fullscreenId = null
            paneMenuFor != null -> paneMenuFor = null
            showLayouts -> showLayouts = false
            showPicker -> showPicker = false
            swapMode -> { swapMode = false; swapFirst = null }
            else -> onBack()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    controlsTick++
                    if (!showControls) {
                        showControls = true
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .padding(horizontal = 32.dp, vertical = 22.dp),
    ) {
        if (showControls) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Button(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            if (fullscreenId != null) {
                Button(onClick = { fullscreenId = null }) { Text("Exit fullscreen") }
            }
            Column(Modifier.weight(1f)) {
                Text("Multi-view", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                Text(
                    if (swapMode) {
                        if (swapFirst == null) "Select the first window to swap" else "Select the second window"
                    } else {
                        "${streams.size} of ${MultiViewRepository.MAX_STREAMS} screens · select a screen to hear it"
                    },
                    style = NuvioTheme.textStyles.metadata,
                    color = if (swapMode) NuvioTheme.colors.Warning else NuvioTheme.colors.TextSecondary,
                )
            }
            Button(
                onClick = {
                    swapMode = !swapMode
                    swapFirst = null
                },
                enabled = streams.size >= 2,
                colors = ButtonDefaults.colors(
                    containerColor = if (swapMode) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
                    contentColor = if (swapMode) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Swap")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Button(
                onClick = { showLayouts = true },
                enabled = streams.size >= 2,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(layoutLabel(layout))
            }
            Button(
                onClick = { MultiViewRepository.setSoundAll(!soundAll) },
                modifier = Modifier.focusRequester(controlsFocus),
            ) {
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
            Button(onClick = {
                streams.forEach { stream -> refreshTicks[stream.id] = (refreshTicks[stream.id] ?: 0) + 1 }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Refresh")
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
        }
        val fullscreenStream = fullscreenId?.let { id -> streams.firstOrNull { it.id == id } }
        if (fullscreenStream != null) {
            Box(Modifier.fillMaxSize()) {
                MultiViewPane(
                    stream = fullscreenStream,
                    audible = true,
                    paused = pauseAll,
                    volume = volumes[fullscreenStream.id],
                    swapMarked = false,
                    refreshTick = refreshTicks[fullscreenStream.id] ?: 0,
                    showInfo = false,
                    onClick = {},
                    onLongPress = { paneMenuFor = fullscreenStream },
                )
            }
        } else if (streams.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No screens yet.", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pick up to four channels with Add channel, or press Multi-view while watching one.",
                        color = NuvioTheme.colors.TextSecondary,
                        style = NuvioTheme.textStyles.body,
                    )
                }
            }
        } else {
            MultiViewGrid(
                streams = streams,
                layout = layout,
                audibleId = audibleId,
                soundAll = soundAll,
                pauseAll = pauseAll,
                swapMode = swapMode,
                swapFirst = swapFirst,
                volumes = volumes,
                refreshTicks = refreshTicks,
                showInfo = showControls,
                paneFocuses = paneFocuses,
                onLongPress = { paneMenuFor = it },
                onPaneClick = { id ->
                    if (swapMode) {
                        if (swapFirst == null) swapFirst = id
                        else {
                            MultiViewRepository.swap(swapFirst!!, id)
                            swapFirst = null
                            swapMode = false
                        }
                    } else {
                        MultiViewRepository.setAudible(id)
                    }
                },
            )
        }
    }

    if (showPicker) {
        ChannelPicker(
            channels = channels,
            groups = ui.groups.filter { it.id != ChannelGroup.SEARCH && it.id != ChannelGroup.ADDONS },
            channelsForGroup = viewModel::channelsInGroup,
            favoriteKeys = user.favorites.toSet(),
            recentKeys = user.recent.toSet(),
            addedKeys = streams.mapTo(HashSet()) { it.id },
            addonViewModel = addonBrowserViewModel,
            onPick = { channel -> MultiViewRepository.add(channel) },
            onPickAddon = { stream, item ->
                val url = stream.getStreamUrl()
                if (url != null) {
                    MultiViewRepository.add(
                        LiveChannel(
                            key = "addon:${item.apiType}:${item.id}:${url.hashCode()}",
                            sourceId = "",
                            sourceName = stream.addonName,
                            name = item.name,
                            tvgId = null,
                            tvgName = null,
                            logo = item.logo ?: item.poster,
                            groupId = ChannelGroup.ADDONS,
                            group = "Addons",
                            number = 0,
                            url = url,
                        )
                    )
                }
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showLayouts) {
        LayoutMenu(
            current = layout,
            count = streams.size,
            onPick = {
                MultiViewRepository.setLayout(it)
                showLayouts = false
            },
            onDismiss = { showLayouts = false },
        )
    }

    paneMenuFor?.let { stream ->
        PaneMenu(
            stream = stream,
            currentVolume = volumes[stream.id],
            onFullscreen = {
                fullscreenId = stream.id
                paneMenuFor = null
            },
            onSwap = {
                swapMode = true
                swapFirst = stream.id
                paneMenuFor = null
            },
            onVolume = { volumes[stream.id] = it },
            onRefresh = {
                refreshTicks[stream.id] = (refreshTicks[stream.id] ?: 0) + 1
                paneMenuFor = null
            },
            onCloseWindow = {
                MultiViewRepository.remove(stream.id)
                volumes.remove(stream.id)
                refreshTicks.remove(stream.id)
                paneMenuFor = null
            },
            onClose = { paneMenuFor = null },
        )
    }
}

@Composable
private fun PaneMenu(
    stream: MultiViewStream,
    currentVolume: Int?,
    onFullscreen: () -> Unit,
    onSwap: () -> Unit,
    onVolume: (Int) -> Unit,
    onRefresh: () -> Unit,
    onCloseWindow: () -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(360.dp)
                .glassSurface(RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stream.title, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Window controls", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onFullscreen,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            ) { Text("Fullscreen") }
            Button(onClick = onSwap, modifier = Modifier.fillMaxWidth()) { Text("Swap with another window") }
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh stream") }
            Text("Volume", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(0, 20, 40, 60, 80, 100).forEach { level ->
                    Button(
                        onClick = { onVolume(level) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = if (currentVolume == level) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (currentVolume == level) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) { Text("$level%") }
                }
            }
            Button(onClick = onCloseWindow, modifier = Modifier.fillMaxWidth()) { Text("Close window") }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close menu") }
        }
    }
}

@Composable
private fun MultiViewGrid(
    streams: List<MultiViewStream>,
    layout: MultiViewLayout,
    audibleId: String?,
    soundAll: Boolean,
    pauseAll: Boolean,
    swapMode: Boolean,
    swapFirst: String?,
    volumes: Map<String, Int>,
    refreshTicks: Map<String, Int>,
    showInfo: Boolean,
    paneFocuses: MutableMap<String, FocusRequester>,
    onLongPress: (MultiViewStream) -> Unit,
    onPaneClick: (String) -> Unit,
) {
    val effective = effectiveLayout(layout, streams.size)
    val pane: @Composable (MultiViewStream, Modifier) -> Unit = { stream, modifier ->
        Box(modifier) {
            MultiViewPane(
                stream = stream,
                audible = soundAll || stream.id == audibleId,
                paused = pauseAll,
                volume = volumes[stream.id],
                swapMarked = swapMode && swapFirst == stream.id,
                refreshTick = refreshTicks[stream.id] ?: 0,
                showInfo = showInfo,
                focusRequester = paneFocuses.getOrPut(stream.id) { FocusRequester() },
                onClick = { onPaneClick(stream.id) },
                onLongPress = { onLongPress(stream) },
            )
        }
    }
    when (effective) {
        MultiViewLayout.SIDE_BY_SIDE, MultiViewLayout.ROW -> {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                streams.forEach { stream -> pane(stream, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        MultiViewLayout.COLUMN -> {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                streams.forEach { stream -> pane(stream, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        MultiViewLayout.FOCUS_CORNER -> {
            val main = streams.firstOrNull()
            val rest = streams.drop(1)
            if (rest.isEmpty()) {
                main?.let { pane(it, Modifier.fillMaxSize()) }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    main?.let { pane(it, Modifier.weight(0.68f).fillMaxHeight()) }
                    Column(Modifier.weight(0.32f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rest.forEach { stream -> pane(stream, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
        MultiViewLayout.AUTO, MultiViewLayout.GRID -> {
            val slots = slotsFor(effective, streams.size)
            val rows = (slots.maxOfOrNull { it.row } ?: 0) + 1
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(rows) { rowIndex ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        slots.filter { it.row == rowIndex }.sortedBy { it.col }.forEach { slot ->
                            val stream = streams.getOrNull(slot.index)
                            if (stream != null) {
                                pane(stream, Modifier.weight(slot.colSpan.toFloat()).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One window's place in a grid: which row/column it starts in and how many columns it spans. */
private data class MultiViewSlot(val index: Int, val row: Int, val col: Int, val colSpan: Int = 1)

/**
 * AUTO grows with the windows: 1 fills the screen, 2 share it side by side, 3 become one big
 * top window and two below, then 2×2, then one big + four, then 3×2. No empty cells.
 */
private fun slotsFor(layout: MultiViewLayout, count: Int): List<MultiViewSlot> = when (layout) {
    MultiViewLayout.AUTO -> when (count) {
        0, 1 -> listOf(MultiViewSlot(0, 0, 0, 1))
        2 -> listOf(MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1))
        3 -> listOf(MultiViewSlot(0, 0, 0, 2), MultiViewSlot(1, 1, 0), MultiViewSlot(2, 1, 1))
        4 -> listOf(MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 1, 0), MultiViewSlot(3, 1, 1))
        5 -> listOf(
            MultiViewSlot(0, 0, 0, 2),
            MultiViewSlot(1, 1, 0), MultiViewSlot(2, 1, 1),
            MultiViewSlot(3, 2, 0), MultiViewSlot(4, 2, 1),
        )
        else -> listOf(
            MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 0, 2),
            MultiViewSlot(3, 1, 0), MultiViewSlot(4, 1, 1), MultiViewSlot(5, 1, 2),
        )
    }
    MultiViewLayout.GRID -> when (count) {
        0, 1 -> listOf(MultiViewSlot(0, 0, 0))
        2 -> listOf(MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1))
        3 -> listOf(MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 0, 2))
        4 -> listOf(MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 1, 0), MultiViewSlot(3, 1, 1))
        5 -> listOf(
            MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 0, 2),
            MultiViewSlot(3, 1, 0, 1), MultiViewSlot(4, 1, 1, 2),
        )
        else -> listOf(
            MultiViewSlot(0, 0, 0), MultiViewSlot(1, 0, 1), MultiViewSlot(2, 0, 2),
            MultiViewSlot(3, 1, 0), MultiViewSlot(4, 1, 1), MultiViewSlot(5, 1, 2),
        )
    }
    else -> streamsPlaceholder(count)
}

private fun streamsPlaceholder(count: Int): List<MultiViewSlot> =
    (0 until count).map { MultiViewSlot(it, 0, it) }

private fun effectiveLayout(layout: MultiViewLayout, count: Int): MultiViewLayout {
    if (layout != MultiViewLayout.AUTO) return layout
    return MultiViewLayout.AUTO
}

private fun layoutLabel(layout: MultiViewLayout): String = when (layout) {
    MultiViewLayout.AUTO -> "Auto"
    MultiViewLayout.SIDE_BY_SIDE -> "Side by side"
    MultiViewLayout.GRID -> "Grid"
    MultiViewLayout.ROW -> "Row"
    MultiViewLayout.COLUMN -> "Column"
    MultiViewLayout.FOCUS_CORNER -> "Focus + corner"
}

@Composable
private fun LayoutMenu(
    current: MultiViewLayout,
    count: Int,
    onPick: (MultiViewLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.padding(start = 34.dp, top = 150.dp).width(330.dp)
                .glassSurface(RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Screen layout", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
            Text(
                "For $count window${if (count == 1) "" else "s"}",
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
            )
            availableMultiViewLayouts(count).forEachIndexed { index, layout ->
                Button(
                    onClick = { onPick(layout) },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (layout == current) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                        contentColor = if (layout == current) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Text(layoutLabel(layout), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

@Composable
private fun MultiViewPane(
    stream: MultiViewStream,
    audible: Boolean,
    paused: Boolean,
    volume: Int? = null,
    swapMarked: Boolean,
    refreshTick: Int = 0,
    showInfo: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    var player by remember(stream.id) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(stream.id) { mutableStateOf(false) }
    val longPressTracker = rememberLongPressKeyTracker()

    LaunchedEffect(stream.id, refreshTick) {
        failed = false
        player?.release()
        player = null
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

    LaunchedEffect(player, audible, paused, volume) {
        val current = player ?: return@LaunchedEffect
        current.volume = volume?.let { (it / 100f).coerceIn(0f, 1f) } ?: if (audible) 1f else 0f
        // Muted windows don't decode audio: with four streams at once that saves a decoder each.
        val audioOn = audible || (volume ?: 0) > 0
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audioOn)
            .build()
        if (paused) current.pause() else current.play()
    }

    DisposableEffect(stream.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    fun isSelectKey(code: Int): Boolean =
        code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            code == AndroidKeyEvent.KEYCODE_ENTER ||
            code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
            longPressTracker.handle(event.nativeKeyEvent, ::isSelectKey) { onLongPress() }
        },
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
            if (showInfo) {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    ),
                )
            }
            if (swapMarked) {
                Box(
                    Modifier.fillMaxSize().border(
                        4.dp,
                        NuvioTheme.colors.Warning,
                        RoundedCornerShape(14.dp),
                    ),
                )
            }
            if (showInfo) {
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
                        if (swapMarked) "Selected to swap" else if (audible) "Sound on" else stream.subtitle.orEmpty(),
                        style = NuvioTheme.textStyles.metadata,
                        color = if (swapMarked) NuvioTheme.colors.Warning else if (audible) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
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
    // Several decoders at once: cap the picture size/bitrate and keep the buffers small so every
    // window can start and keep up together.
    val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setMaxVideoBitrate(2_500_000),
        )
    }
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(4_000, 12_000, 1_000, 2_000)
        .build()
    val player = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .build()
    val attrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    player.setAudioAttributes(attrs, false)
    player.setMediaSource(mediaSource)
    return player
}

private enum class PickerTab(val label: String) {
    ADDONS("Addons"),
    GROUPS("Playlist groups"),
    CHANNELS("All channels"),
}

@Composable
private fun PickerTabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) { Text(label) }
}

@Composable
private fun ChannelPicker(
    channels: List<LiveChannel>,
    groups: List<ChannelGroup>,
    channelsForGroup: (String) -> List<LiveChannel>,
    favoriteKeys: Set<String>,
    recentKeys: Set<String>,
    addedKeys: Set<String>,
    addonViewModel: AddonBrowserViewModel,
    onPick: (LiveChannel) -> Unit,
    onPickAddon: (Stream, MetaPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val addonState by addonViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ordered = remember(channels, favoriteKeys, recentKeys) {
        val byKey = channels.associateBy { it.key }
        (favoriteKeys.mapNotNull { byKey[it] } + recentKeys.mapNotNull { byKey[it] } + channels)
            .distinctBy { it.key }
            .take(120)
    }
    var tab by remember { mutableStateOf(PickerTab.ADDONS) }
    var selectedGroupId by remember { mutableStateOf(groups.firstOrNull()?.id) }
    LaunchedEffect(groups) {
        if (selectedGroupId == null || groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull()?.id
        }
    }
    val groupChannels = remember(selectedGroupId, groups) {
        selectedGroupId?.let(channelsForGroup).orEmpty()
    }
    val catalogFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val tabsFocus = remember { FocusRequester() }
    BackHandler { onDismiss() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { tabsFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(20.dp))
                .background(NuvioTheme.colors.BackgroundElevated)
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add channels", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                Text("Back to close", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerTab.entries.forEach { entry ->
                    PickerTabButton(
                        label = entry.label,
                        selected = tab == entry,
                        modifier = if (entry == PickerTab.ADDONS) Modifier.focusRequester(tabsFocus) else Modifier,
                    ) { tab = entry }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                PickerTab.ADDONS -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.38f).fillMaxHeight()) {
                        Text("Catalogs", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        if (addonState.catalogs.isEmpty()) {
                            Text(
                                if (addonState.loaded) "No live, TV, other or sport catalogs installed."
                                else "Loading addons…",
                                color = NuvioTheme.colors.TextSecondary,
                                style = NuvioTheme.textStyles.body,
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                itemsIndexed(addonState.catalogs, key = { _, entry -> entry.key }) { index, entry ->
                                    val selected = addonState.selectedKey == entry.key
                                    Button(
                                        onClick = { addonViewModel.selectCatalog(entry) },
                                        modifier = Modifier.fillMaxWidth()
                                            .then(if (index == 0) Modifier.focusRequester(catalogFocus) else Modifier),
                                        colors = ButtonDefaults.colors(
                                            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                                            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                                        ),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.catalog.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${entry.addon.displayName} · ${entry.catalog.apiType}",
                                                style = NuvioTheme.textStyles.metadata,
                                                color = if (selected) NuvioTheme.colors.TextInverse.copy(alpha = 0.72f) else NuvioTheme.colors.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(0.62f).fillMaxHeight()) {
                        Text(
                            addonState.catalogs.firstOrNull { it.key == addonState.selectedKey }?.catalog?.name ?: "Streams",
                            style = NuvioTheme.textStyles.sectionTitle,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        when {
                            addonState.loading -> Text("Loading streams…", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
                            addonState.error != null -> Text(addonState.error!!, color = NuvioTheme.colors.Warning, style = NuvioTheme.textStyles.body)
                            addonState.selectedKey == null -> Text("Pick a catalog to see its streams.", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
                            addonState.items.isEmpty() -> Text("No streams in this catalog.", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                itemsIndexed(addonState.items, key = { _, item -> "${item.apiType}:${item.id}" }) { index, item ->
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val stream = addonViewModel.resolvePreview(item)
                                                if (stream != null) onPickAddon(stream, item)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                            .then(if (index == 0) Modifier.focusRequester(itemFocus) else Modifier),
                                        colors = ButtonDefaults.colors(
                                            containerColor = NuvioTheme.colors.BackgroundCard,
                                            contentColor = NuvioTheme.colors.TextPrimary,
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                                        ),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                item.apiType,
                                                style = NuvioTheme.textStyles.metadata,
                                                color = NuvioTheme.colors.TextSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                PickerTab.GROUPS -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(0.36f).fillMaxHeight()) {
                        Text("Playlist groups", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
                                val selected = group.id == selectedGroupId
                                Button(
                                    onClick = { selectedGroupId = group.id },
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (index == 0) Modifier.focusRequester(groupFocus) else Modifier),
                                    colors = ButtonDefaults.colors(
                                        containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                                        contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                                    ),
                                ) {
                                    Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(
                                        group.count.toString(),
                                        style = NuvioTheme.textStyles.metadata,
                                        color = if (selected) NuvioTheme.colors.TextInverse.copy(alpha = 0.72f) else NuvioTheme.colors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(0.64f).fillMaxHeight()) {
                        Text(
                            groups.firstOrNull { it.id == selectedGroupId }?.title ?: "Channels",
                            style = NuvioTheme.textStyles.sectionTitle,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (groupChannels.isEmpty()) {
                            Text("No channels in this group.", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                itemsIndexed(groupChannels, key = { _, channel -> channel.key }) { index, channel ->
                                    val added = channel.key in addedKeys
                                    Button(
                                        onClick = { if (!added) onPick(channel) },
                                        enabled = !added && addedKeys.size < MultiViewRepository.MAX_STREAMS,
                                        modifier = Modifier.fillMaxWidth()
                                            .then(if (index == 0) Modifier.focusRequester(channelFocus) else Modifier),
                                        colors = ButtonDefaults.colors(
                                            containerColor = if (added) NuvioTheme.colors.BackgroundElevated else NuvioTheme.colors.BackgroundCard,
                                            contentColor = if (added) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                                        ),
                                    ) {
                                        Text("${channel.number}  ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(
                                            if (added) "Added" else channel.group,
                                            style = NuvioTheme.textStyles.metadata,
                                            color = if (added) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                PickerTab.CHANNELS -> {
                    if (ordered.isEmpty()) {
                        Text("Channels are still loading.", color = NuvioTheme.colors.TextSecondary)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                            itemsIndexed(ordered, key = { _, channel -> channel.key }) { index, channel ->
                                val added = channel.key in addedKeys
                                Button(
                                    onClick = { if (!added) onPick(channel) },
                                    enabled = !added && addedKeys.size < MultiViewRepository.MAX_STREAMS,
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (index == 0) Modifier.focusRequester(channelFocus) else Modifier),
                                    colors = ButtonDefaults.colors(
                                        containerColor = if (added) NuvioTheme.colors.BackgroundElevated else NuvioTheme.colors.BackgroundCard,
                                        contentColor = if (added) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                                    ),
                                ) {
                                    Text(
                                        "${channel.number}  ${channel.name}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (added) "Added" else channel.group,
                                        style = NuvioTheme.textStyles.metadata,
                                        color = if (added) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
