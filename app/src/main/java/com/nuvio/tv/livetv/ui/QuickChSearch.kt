@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick Ch Search: a strip directly above the EPG. The first level is regions (horizontal); a
 * region replaces itself with that region's channels (also horizontal). Picking a channel
 * searches every playlist for it and probes the streams: working ones come first, dead ones are
 * hidden. "Manage" adds, edits and deletes your own regions and channels.
 */
@Composable
internal fun QuickChSearchBar(
    viewModel: LiveTvViewModel,
    entryFocus: FocusRequester,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
) {
    val allChannels by viewModel.displayChannels.collectAsStateWithLifecycle()
    val savedJson by viewModel.quickChannelsJson.collectAsStateWithLifecycle("")
    var version by remember { mutableIntStateOf(0) }
    var focusLevel by remember { mutableStateOf(false) } // false = regions, true = channels
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var chipIndex by remember { mutableIntStateOf(0) }
    var selectedQuick by remember { mutableStateOf<QuickChannel?>(null) }
    var manageOpen by remember { mutableStateOf(false) }
    var addRegionOpen by remember { mutableStateOf(false) }
    var addChannelOpen by remember { mutableStateOf(false) }
    var editingRegion by remember { mutableStateOf<QuickChannelList.QuickCategory?>(null) }
    var editingChannel by remember { mutableStateOf<QuickChannel?>(null) }
    var barFocused by remember { mutableStateOf(false) }
    val regionRow = rememberLazyListState()
    val channelRow = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun persist() {
        viewModel.saveQuickChannels(QuickChannelList.encodeCustom())
        version++
    }

    fun focusBarLater() {
        scope.launchFocus(entryFocus)
    }

    // Swapping the chip row (regions <-> channels) can drop the bar's focus on some remotes;
    // put it back on the strip once the new row is in place.
    fun refocusBarSoon() {
        scope.launch { delay(40); runCatching { entryFocus.requestFocus() } }
    }

    // Saved data is read per TV; the inline list then shows your own regions/channels too.
    LaunchedEffect(savedJson) {
        if (savedJson.isNotBlank()) {
            QuickChannelList.load(savedJson)
            version++
        }
    }

    val categories = remember(version) { QuickChannelList.allCategories() }
    val quickChannels = remember(version, selectedCategory) {
        selectedCategory?.let { QuickChannelList.getChannelsForCategory(it) }.orEmpty()
    }
    // Leaving a region that was deleted, or a channel list that emptied, goes back to regions.
    LaunchedEffect(categories, quickChannels) {
        if (selectedCategory != null && categories.none { it.id == selectedCategory }) {
            selectedCategory = null
            focusLevel = false
            chipIndex = 0
        }
    }

    val validation = rememberQuickChannelMatchState(selectedQuick, allChannels)


    val chips: List<String> = if (!focusLevel) {
        categories.map { it.title } + "Manage"
    } else {
        listOf("‹ Regions") + quickChannels.map { it.displayName } + "Manage"
    }
    val lastIndex = chips.lastIndex.coerceAtLeast(0)
    LaunchedEffect(chips.size) { chipIndex = chipIndex.coerceIn(0, lastIndex) }
    // Keep the highlighted chip wholly in view; there is no per-chip focus to scroll it for us.
    // Instant, clamped scrolling: an unfinished animation on a row that is being replaced was a
    // crash source on some boxes.
    LaunchedEffect(chipIndex, focusLevel, selectedCategory, chips.size) {
        val state = if (focusLevel) channelRow else regionRow
        val target = chipIndex.coerceIn(0, (chips.size - 1).coerceAtLeast(0))
        runCatching { state.scrollToItem(target) }
    }

    fun choose(index: Int) {
        if (index == lastIndex) { manageOpen = true; return }
        if (!focusLevel) {
            categories.getOrNull(index)?.let { cat ->
                selectedCategory = cat.id
                focusLevel = true
                chipIndex = 0
                refocusBarSoon()
            }
        } else if (index == 0) {
            focusLevel = false
            selectedCategory = null
            chipIndex = 0
            refocusBarSoon()
        } else {
            quickChannels.getOrNull(index - 1)?.let { selectedQuick = it }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            // The same left/right edges as the EPG and its time ruler.
            .padding(end = 24.dp, top = 3.dp, bottom = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(NuvioTheme.colors.BackgroundCard.copy(alpha = 0.72f))
            .border(
                2.dp,
                if (barFocused) NuvioTheme.colors.FocusRing else Color.Transparent,
                RoundedCornerShape(9.dp)
            )
            .focusRequester(entryFocus)
            .onFocusChanged { barFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (ok && event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                when {
                    event.key == Key.DirectionLeft -> {
                        chipIndex = (chipIndex - 1).coerceAtLeast(0); true
                    }
                    event.key == Key.DirectionRight -> {
                        chipIndex = (chipIndex + 1).coerceAtMost(lastIndex); true
                    }
                    ok -> { choose(chipIndex); true }
                    event.key == Key.DirectionUp -> { onUp(); true }
                    event.key == Key.DirectionDown -> { onDown(); true }
                    event.key == Key.Back -> {
                        if (focusLevel) {
                            focusLevel = false
                            selectedCategory = null
                            chipIndex = 0
                            refocusBarSoon()
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            LiveText(
                if (focusLevel) (categories.firstOrNull { it.id == selectedCategory }?.title ?: "Channels") else "Quick Ch Search",
                size = 12.sp,
                weight = FontWeight.Bold,
                color = if (barFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 126.dp).padding(end = 8.dp)
            )
            // Two separate rows: swapping the LazyListState of one row while it scrolls could
            // leave the list half-measured on low-end boxes.
            if (focusLevel) {
                QuickChipRow(
                    state = channelRow,
                    chips = chips,
                    active = { index -> index == 0 || quickChannels.getOrNull(index - 1)?.displayName == selectedQuick?.displayName },
                    focusedIndex = if (barFocused) chipIndex else -1,
                    onClick = { index -> chipIndex = index; choose(index) },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            } else {
                QuickChipRow(
                    state = regionRow,
                    chips = chips,
                    active = { index -> categories.getOrNull(index)?.id == selectedCategory },
                    focusedIndex = if (barFocused) chipIndex else -1,
                    onClick = { index -> chipIndex = index; choose(index) },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }

    // Results: the search and the stream checks. Opens as soon as a channel is picked.
    selectedQuick?.let { quick ->
        QuickChResultsDialog(
            quick = quick,
            state = validation,
            onPlay = {
                selectedQuick = null
                onPlay(it)
                focusBarLater()
            },
            onDismiss = {
                selectedQuick = null
                focusBarLater()
            },
        )
    }

    if (manageOpen) {
        QuickChManageDialog(
            onDismiss = { manageOpen = false; focusBarLater() },
            onAddRegion = { manageOpen = false; addRegionOpen = true },
            onAddChannel = { manageOpen = false; addChannelOpen = true },
            onEditRegion = { manageOpen = false; editingRegion = it },
            onEditChannel = { manageOpen = false; editingChannel = it },
            onDeleteRegion = {
                QuickChannelList.removeCustomCategory(it.id)
                persist()
            },
            onDeleteChannel = {
                QuickChannelList.removeCustomChannel(it.displayName)
                persist()
            },
        )
    }

    if (addRegionOpen || editingRegion != null) {
        val existing = editingRegion
        QuickRegionEditorDialog(
            existing = existing,
            onDismiss = { addRegionOpen = false; editingRegion = null; manageOpen = true },
            onSave = { category ->
                if (existing == null) {
                    QuickChannelList.addCustomCategory(category)
                } else {
                    QuickChannelList.updateCustomCategory(existing.id, category)
                }
                persist()
                addRegionOpen = false
                editingRegion = null
                focusBarLater()
            },
        )
    }

    if (addChannelOpen || editingChannel != null) {
        val existing = editingChannel
        QuickChannelEditorDialog(
            existing = existing,
            onDismiss = { addChannelOpen = false; editingChannel = null; manageOpen = true },
            onSave = { channel ->
                if (existing == null) {
                    QuickChannelList.addCustomChannel(channel)
                } else {
                    QuickChannelList.updateCustomChannel(existing.displayName, channel)
                }
                persist()
                addChannelOpen = false
                editingChannel = null
                focusBarLater()
            },
        )
    }
}

/** Lets a dialog hand focus back once it has closed (the bar must be composed again first). */
private fun kotlinx.coroutines.CoroutineScope.launchFocus(requester: FocusRequester) {
    launch { delay(80); runCatching { requester.requestFocus() } }
}

@Composable
private fun QuickChipRow(
    state: androidx.compose.foundation.lazy.LazyListState,
    chips: List<String>,
    active: (Int) -> Boolean,
    focusedIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        itemsIndexed(chips) { index, label ->
            QuickChip(
                label = label,
                active = active(index),
                focused = index == focusedIndex,
                onClick = { onClick(index) },
            )
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    active: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .height(30.dp)
            .widthIn(max = 190.dp)
            .clip(shape)
            .background(
                when {
                    focused -> NuvioTheme.colors.FocusBackground
                    active -> NuvioTheme.colors.Secondary.copy(alpha = 0.22f)
                    else -> NuvioTheme.colors.BackgroundElevated
                }
            )
            .border(1.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiveText(
            label,
            size = 12.sp,
            weight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            marquee = true,
        )
    }
}

@Composable
private fun QuickChResultsDialog(
    quick: QuickChannel,
    state: QuickChannelMatchState,
    onPlay: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val visible = state.visibleMatches
    // Focus the first row once, when the dialog opens. Check results arrive afterwards and must
    // not pull the highlight back to the top while you're moving through the list.
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
    LiveDialog(onDismiss = onDismiss, width = 780.dp, background = NuvioTheme.colors.BackgroundElevated) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LiveText(quick.displayName, size = 24.sp, weight = FontWeight.Bold, maxLines = 1)
                LiveText(
                    when {
                        state.matches.isEmpty() -> "No channel in your playlists matches this name."
                        state.isChecking -> "${state.checkedCount}/${state.matches.size} checked · ${state.workingCount} working"
                        state.workingCount == 0 -> "No working streams found."
                        else -> "${state.workingCount} working · dead streams hidden"
                    },
                    color = NuvioTheme.colors.TextSecondary,
                    size = 13.sp,
                )
            }
            if (state.isChecking) {
                LoadingSpinner(Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            LiveText(
                "Back to close",
                color = NuvioTheme.colors.TextTertiary,
                size = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                LiveText(
                    if (state.isChecking) "Searching your playlists…" else "Nothing playable here.",
                    color = NuvioTheme.colors.TextSecondary,
                    size = 15.sp,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                itemsIndexed(visible, key = { _, c -> c.key }) { index, channel ->
                    LiveFocusRow(
                        modifier = Modifier.fillMaxWidth().then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        onClick = { onPlay(channel) },
                        horizontalPadding = 10.dp,
                        verticalPadding = 7.dp,
                    ) { f ->
                        ChannelLogo(channel.logo, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            LiveText(
                                channel.name,
                                size = 16.sp,
                                weight = FontWeight.SemiBold,
                                color = focusedTextColor(f),
                                maxLines = 1,
                                marquee = true,
                            )
                            LiveText(
                                listOfNotNull(channel.group.takeIf { it.isNotBlank() }, channel.sourceName.takeIf { it.isNotBlank() })
                                    .joinToString("  ·  "),
                                size = 12.sp,
                                color = focusedSecondaryTextColor(f),
                                maxLines = 1,
                            )
                        }
                        val status = state.statuses[channel.url]
                        val probeable = channel.url.startsWith("http", ignoreCase = true)
                        LiveText(
                            when {
                                !probeable -> "Addon"
                                status == true -> "✓ Live"
                                status == null -> "Checking…"
                                else -> ""
                            },
                            size = 12.sp,
                            weight = FontWeight.Bold,
                            color = when {
                                !probeable -> NuvioTheme.colors.TextSecondary
                                status == true -> NuvioTheme.colors.Success
                                else -> NuvioTheme.colors.TextTertiary
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Your own regions and channels: add, edit or delete. Built-in ones stay as they are. */
@Composable
private fun QuickChManageDialog(
    onDismiss: () -> Unit,
    onAddRegion: () -> Unit,
    onAddChannel: () -> Unit,
    onEditRegion: (QuickChannelList.QuickCategory) -> Unit,
    onEditChannel: (QuickChannel) -> Unit,
    onDeleteRegion: (QuickChannelList.QuickCategory) -> Unit,
    onDeleteChannel: (QuickChannel) -> Unit,
) {
    val first = remember { FocusRequester() }
    val regions = QuickChannelList.allCategories().filter { QuickChannelList.isCustomCategory(it.id) }
    val channels = QuickChannelList.all.filter { QuickChannelList.isCustomChannel(it.displayName) }
    var actionRegion by remember { mutableStateOf<QuickChannelList.QuickCategory?>(null) }
    var actionChannel by remember { mutableStateOf<QuickChannel?>(null) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    LiveDialog(onDismiss = onDismiss, width = 720.dp, background = NuvioTheme.colors.BackgroundElevated) {
        LiveText("Quick Ch Search · manage", size = 22.sp, weight = FontWeight.Bold)
        LiveText(
            "Your own regions and channels are saved on this TV. Built-in names are searched across every playlist.",
            color = NuvioTheme.colors.TextSecondary,
            size = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 430.dp)) {
            item { MenuHeader("Regions", first = true) }
            item { MenuItem("+ Add region", Modifier.focusRequester(first), onAddRegion) }
            items(regions, key = { "r:" + it.id }) { region ->
                MenuItem("${region.title} · edit or delete", onClick = { actionRegion = region })
            }
            item { MenuHeader("Channels") }
            item { MenuItem("+ Add channel", onClick = onAddChannel) }
            items(channels, key = { "c:" + it.displayName }) { channel ->
                MenuItem("${channel.displayName} · edit or delete", onClick = { actionChannel = channel })
            }
        }
        Spacer(Modifier.height(10.dp))
        LiveFocusRow(onClick = onDismiss) { f -> LiveText("Close", color = focusedTextColor(f)) }
    }

    actionRegion?.let { region ->
        QuickActionDialog(
            title = region.title,
            actions = listOf(
                "Edit" to { actionRegion = null; onEditRegion(region) },
                "Delete" to { actionRegion = null; onDeleteRegion(region) },
                "Cancel" to { actionRegion = null },
            ),
            onDismiss = { actionRegion = null },
        )
    }
    actionChannel?.let { channel ->
        QuickActionDialog(
            title = channel.displayName,
            actions = listOf(
                "Edit" to { actionChannel = null; onEditChannel(channel) },
                "Delete" to { actionChannel = null; onDeleteChannel(channel) },
                "Cancel" to { actionChannel = null },
            ),
            onDismiss = { actionChannel = null },
        )
    }
}

@Composable
private fun QuickActionDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 420.dp, background = NuvioTheme.colors.BackgroundElevated) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            actions.forEachIndexed { index, (label, action) ->
                MenuItem(label, if (index == 0) Modifier.focusRequester(first) else Modifier, action)
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
private fun QuickRegionEditorDialog(
    existing: QuickChannelList.QuickCategory?,
    onDismiss: () -> Unit,
    onSave: (QuickChannelList.QuickCategory) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 560.dp, background = NuvioTheme.colors.BackgroundElevated) {
        LiveText(if (existing == null) "New region" else "Edit region", size = 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LiveText("Name", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = title, onValueChange = { title = it; error = null }, hint = "e.g. Sports extras", focusRequester = titleFocus)
        Spacer(Modifier.height(8.dp))
        LiveText("Tags (optional, comma separated)", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = tags, onValueChange = { tags = it }, hint = "sports, news")
        error?.let { Spacer(Modifier.height(6.dp)); LiveText(it, color = NuvioTheme.colors.Warning, size = 13.sp) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(onClick = {
                val name = title.trim()
                if (name.isBlank()) {
                    error = "Give the region a name."
                } else {
                    val id = existing?.id ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "region" }
                    onSave(
                        QuickChannelList.QuickCategory(
                            id = id,
                            title = name,
                            tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        )
                    )
                }
            }) { f -> LiveText("Save", color = focusedTextColor(f)) }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText("Cancel", color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { titleFocus.requestFocus() } }
}

@Composable
private fun QuickChannelEditorDialog(
    existing: QuickChannel?,
    onDismiss: () -> Unit,
    onSave: (QuickChannel) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var aliases by remember { mutableStateOf(existing?.aliases?.joinToString(", ").orEmpty()) }
    var regions by remember { mutableStateOf(existing?.regions?.joinToString(", ").orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 620.dp, background = NuvioTheme.colors.BackgroundElevated) {
        LiveText(if (existing == null) "New quick channel" else "Edit quick channel", size = 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LiveText("Name (as it appears in a playlist)", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = name, onValueChange = { name = it; error = null }, hint = "e.g. My Sports Net", focusRequester = nameFocus)
        Spacer(Modifier.height(6.dp))
        LiveText("Other names it goes by (comma separated)", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = aliases, onValueChange = { aliases = it }, hint = "MSN, My Sports Network")
        Spacer(Modifier.height(6.dp))
        LiveText("Regions (comma separated)", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = regions, onValueChange = { regions = it }, hint = "US, UK, CUSTOM")
        Spacer(Modifier.height(6.dp))
        LiveText("Tags (comma separated)", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LiveTextField(value = tags, onValueChange = { tags = it }, hint = "sports, news")
        error?.let { Spacer(Modifier.height(6.dp)); LiveText(it, color = NuvioTheme.colors.Warning, size = 13.sp) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(onClick = {
                val display = name.trim()
                if (display.isBlank()) {
                    error = "Give the channel a name."
                } else {
                    onSave(
                        QuickChannel(
                            displayName = display,
                            aliases = aliases.split(",").map { it.trim() }.filter { it.isNotBlank() },
                            regions = regions.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() },
                            tags = tags.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() },
                        )
                    )
                }
            }) { f -> LiveText("Save", color = focusedTextColor(f)) }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText("Cancel", color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { nameFocus.requestFocus() } }
}
