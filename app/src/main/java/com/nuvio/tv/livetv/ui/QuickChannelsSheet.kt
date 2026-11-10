@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private enum class QuickTab(val label: String) { QUICK("Quick"), FAVORITES("Favorites"), RECENT("Recent") }

/**
 * Quick Channels, as on mobile: a curated list of names (ESPN, CNN, Sky Sports…) that searches
 * every playlist for matching channels, plus Favorites and Recently watched.
 */
@Composable
fun QuickChannelsSheet(
    viewModel: LiveTvViewModel,
    currentKey: String?,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val channels by viewModel.displayChannels.collectAsStateWithLifecycle()
    val byKey = remember(channels) { channels.associateBy { it.key } }
    val favorites = remember(channels, user.favorites) { user.favorites.mapNotNull { byKey[it] } }
    val recent = remember(channels, user.recent) { user.recent.mapNotNull { byKey[it] } }

    var tab by remember { mutableStateOf(QuickTab.QUICK) }
    var filter by remember { mutableStateOf("All") }
    var matchPopup by remember { mutableStateOf<Pair<String, List<LiveChannel>>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val firstTabFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val matchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstTabFocus.requestFocus() }
    }
    LaunchedEffect(tab) {
        delay(60)
        runCatching { firstItemFocus.requestFocus() }
    }
    LaunchedEffect(matchPopup) {
        if (matchPopup != null) {
            delay(100)
            runCatching { matchFocus.requestFocus() }
        }
    }

    BackHandler(enabled = matchPopup != null) {
        matchPopup = null
    }
    BackHandler(enabled = matchPopup == null) { onDismiss() }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.84f)
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick channels", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                    Text(
                        notice ?: "${channels.size} channels · ${QuickChannelList.all.size} curated names · Back to close",
                        style = NuvioTheme.textStyles.metadata,
                        color = if (notice != null) NuvioTheme.colors.Warning else NuvioTheme.colors.TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTab.entries.forEachIndexed { index, entry ->
                    Button(
                        onClick = { tab = entry },
                        modifier = if (index == 0) Modifier.focusRequester(firstTabFocus) else Modifier,
                        colors = ButtonDefaults.colors(
                            containerColor = if (tab == entry) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (tab == entry) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) { Text(entry.label) }
                }
            }
            if (tab == QuickTab.QUICK) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { filter = "All" },
                        colors = ButtonDefaults.colors(
                            containerColor = if (filter == "All") NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (filter == "All") NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextSecondary,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("All", style = NuvioTheme.textStyles.tab) }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(QuickChannelList.filters.drop(1)) { name ->
                            Button(
                                onClick = { filter = name },
                                colors = ButtonDefaults.colors(
                                    containerColor = if (filter == name) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                                    contentColor = if (filter == name) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextSecondary,
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) { Text(name, style = NuvioTheme.textStyles.tab) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            when (tab) {
                QuickTab.QUICK -> {
                    val curated = remember(filter, channels) {
                        QuickChannelList.all
                            .filter { QuickChannelList.inFilter(it, filter) }
                            .map { quick -> quick to channels.filter { QuickChannelList.matches(quick, it) } }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        itemsIndexed(curated, key = { _, pair -> pair.first.displayName }) { index, pair ->
                            val quick = pair.first
                            val matches = pair.second
                            Button(
                                onClick = {
                                    if (matches.isEmpty()) {
                                        notice = "${quick.displayName} not found in your playlists"
                                    } else {
                                        notice = null
                                        matchPopup = quick.displayName to matches
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier),
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextPrimary,
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                                ),
                            ) {
                                Text(quick.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    if (matches.isEmpty()) "No match" else "${matches.size} match${if (matches.size == 1) "" else "es"}",
                                    style = NuvioTheme.textStyles.metadata,
                                    color = if (matches.isEmpty()) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.Success,
                                )
                            }
                        }
                    }
                }
                QuickTab.FAVORITES, QuickTab.RECENT -> {
                    val list = if (tab == QuickTab.FAVORITES) favorites else recent
                    if (list.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing here yet.", color = NuvioTheme.colors.TextSecondary)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(list, key = { _, c -> c.key }) { index, channel ->
                                QuickChannelRow(
                                    channel = channel,
                                    playing = channel.key == currentKey,
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier),
                                    onClick = { onPick(channel) },
                                )
                            }
                        }
                    }
                }
            }
        }

        matchPopup?.let { (name, matches) ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.60f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(620.dp).fillMaxHeight(0.68f)
                        .glassSurface(RoundedCornerShape(20.dp))
                        .padding(18.dp),
                ) {
                    Text(name, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                    Text(
                        "${matches.size} match${if (matches.size == 1) "" else "es"} in your playlists · Back to close",
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        itemsIndexed(matches, key = { _, c -> c.key }) { index, channel ->
                            QuickChannelRow(
                                channel = channel,
                                playing = channel.key == currentKey,
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(matchFocus) else Modifier),
                                onClick = { onPick(channel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChannelRow(
    channel: LiveChannel,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (playing) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (playing) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
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
            if (playing) "Playing" else channel.group,
            style = NuvioTheme.textStyles.metadata,
            color = if (playing) NuvioTheme.colors.TextInverse.copy(alpha = 0.7f) else NuvioTheme.colors.TextSecondary,
            maxLines = 1,
        )
    }
}
