package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The middle and right columns of the guide's Addons group: the addon catalogs that carry
 * live/TV/other/sport content, and the playable items in the selected catalog.
 */
@Composable
internal fun AddonBrowserPanel(
    modifier: Modifier = Modifier,
    onOpenStream: (videoId: String, contentType: String, title: String, poster: String?) -> Unit,
    onPreviewStream: (Stream, MetaPreview) -> Unit,
    onBackToGroups: () -> Unit,
    focusTick: Int = 0,
    viewModel: AddonBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalogFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var itemsFocused by remember { mutableStateOf(false) }
    var catalogFocused by remember { mutableStateOf(false) }
    var focusItemsTick by remember { mutableIntStateOf(0) }

    BackHandler(enabled = itemsFocused || catalogFocused) {
        if (itemsFocused) {
            runCatching { catalogFocus.requestFocus() }
            itemsFocused = false
        } else {
            onBackToGroups()
        }
    }

    LaunchedEffect(state.loaded) {
        if (state.loaded) {
            delay(80)
            runCatching { catalogFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusItemsTick) {
        if (focusItemsTick > 0) {
            delay(80)
            runCatching { firstItemFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            delay(50)
            runCatching { catalogFocus.requestFocus() }
        }
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 8.dp, top = 6.dp, end = 24.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(0.40f).fillMaxHeight()) {
            Text(
                "Addon Catalogs",
                style = NuvioTheme.textStyles.sectionTitle,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
            )
            if (state.loaded && state.catalogs.isEmpty()) {
                Text(
                    "No addons with live, TV, other or sport catalogs are installed.",
                    style = NuvioTheme.textStyles.body,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(state.catalogs, key = { _, entry -> entry.key }) { index, entry ->
                        AddonCatalogRow(
                            entry = entry,
                            selected = state.selectedKey == entry.key,
                            modifier = Modifier.then(
                                if (index == 0) Modifier.focusRequester(catalogFocus) else Modifier,
                            ),
                            onFocused = { focused ->
                                catalogFocused = focused
                                if (focused) viewModel.selectCatalog(entry)
                            },
                            onClick = {
                                viewModel.selectCatalog(entry)
                                focusItemsTick++
                            },
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(0.60f).fillMaxHeight()) {
            val selected = state.catalogs.firstOrNull { it.key == state.selectedKey }
            Text(
                selected?.let { "${it.catalog.name} · ${it.addon.displayName}" } ?: "Streams",
                style = NuvioTheme.textStyles.sectionTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            when {
                state.loading -> BrowserMessage("Loading streams…")
                state.error != null -> BrowserMessage(state.error!!, error = true)
                state.selectedKey == null -> BrowserMessage("Pick a catalog to see its streams.")
                state.items.isEmpty() -> BrowserMessage("No streams in this catalog.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(state.items, key = { _, item -> "${item.apiType}:${item.id}" }) { index, item ->
                        AddonStreamRow(
                            item = item,
                            modifier = Modifier.then(
                                if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                            ),
                            onFocused = { itemsFocused = it },
                            onClick = {
                                scope.launch {
                                    val stream = viewModel.resolvePreview(item)
                                    if (stream != null) {
                                        onPreviewStream(stream, item)
                                    } else {
                                        onOpenStream(item.apiType, item.id, item.name, item.poster)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonCatalogRow(
    entry: AddonBrowserViewModel.CatalogEntry,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember(entry.key) { mutableStateOf(false) }
    val highlighted = focused || selected
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (highlighted) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.86f),
            )
            .then(
                if (focused) Modifier.border(2.dp, NuvioTheme.colors.TextPrimary, shape) else Modifier,
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.catalog.name,
                style = NuvioTheme.textStyles.body,
                color = if (highlighted) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.addon.displayName} · ${entry.catalog.apiType.replaceFirstChar { it.uppercase() }}",
                style = NuvioTheme.textStyles.metadata,
                color = if (highlighted) NuvioTheme.colors.TextInverse.copy(alpha = 0.72f) else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "›",
            style = NuvioTheme.textStyles.sectionTitle,
            color = if (highlighted) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextTertiary,
        )
    }
}

@Composable
private fun AddonStreamRow(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember(item.apiType, item.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.86f),
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = item.poster ?: item.background
        Box(
            Modifier
                .width(112.dp)
                .height(63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(
                    item.name.take(1).uppercase(),
                    style = NuvioTheme.textStyles.sectionTitle,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = NuvioTheme.textStyles.body,
                color = if (focused) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.releaseInfo?.takeIf { it.isNotBlank() },
                item.apiType.replaceFirstChar { it.uppercase() },
            ).joinToString(" · ")
            Text(
                meta,
                style = NuvioTheme.textStyles.metadata,
                color = if (focused) NuvioTheme.colors.TextInverse.copy(alpha = 0.72f) else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BrowserMessage(text: String, error: Boolean = false) {
    Text(
        text,
        style = NuvioTheme.textStyles.body,
        color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
    )
}
