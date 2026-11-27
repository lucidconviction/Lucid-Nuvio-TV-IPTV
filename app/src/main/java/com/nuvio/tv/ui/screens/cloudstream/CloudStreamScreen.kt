package com.nuvio.tv.ui.screens.cloudstream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.lucid.LucidPluginDetail
import com.nuvio.tv.livetv.lucid.LucidPluginExtension
import com.nuvio.tv.livetv.lucid.LucidPluginItem
import com.nuvio.tv.livetv.lucid.LucidPluginTab
import com.nuvio.tv.ui.components.nuvioFocusRing
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The TV CloudStream page: provider list, provider home tabs, own search, item details,
 * episodes and stream handoff to the normal player. Mirrors the mobile provider browser.
 */
@Composable
fun CloudStreamScreen(
    onBack: () -> Unit,
    onPlay: (CloudStreamPlayback) -> Unit,
    viewModel: CloudStreamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler {
        when {
            state.detail != null -> viewModel.closeDetail()
            state.provider != null -> viewModel.backToProviders()
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.playback.collect { onPlay(it) }
    }
    LaunchedEffect(Unit) {
        CloudStreamLaunch.take()?.let { viewModel.searchAll(it) }
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (state.provider == null) {
            ProviderList(
                state = state,
                onBack = onBack,
                onOpen = viewModel::openProvider,
                onRetry = viewModel::loadProviders,
                onSearchAll = viewModel::searchAll,
                onGlobalResult = viewModel::openGlobalResult,
            )
        } else {
            ProviderBrowse(
                state = state,
                onBack = viewModel::backToProviders,
                onTab = viewModel::selectTab,
                onItem = viewModel::openItem,
                onLoadMore = viewModel::loadMore,
                onSearch = viewModel::search,
            )
        }

        state.detail?.let { detail ->
            DetailOverlay(
                detail = detail,
                loading = state.detailLoading,
                error = state.error,
                onBack = viewModel::closeDetail,
                onPlay = viewModel::playDetail,
                onEpisode = viewModel::playEpisode,
            )
        }
    }
}

@Composable
private fun ProviderList(
    state: CloudStreamUiState,
    onBack: () -> Unit,
    onOpen: (LucidPluginExtension) -> Unit,
    onRetry: () -> Unit,
    onSearchAll: (String) -> Unit,
    onGlobalResult: (CloudStreamGlobalResult) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val globalFocus = remember { FocusRequester() }
    var globalQuery by remember { mutableStateOf("") }
    var globalFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state.globalQuery) {
        if (state.globalQuery.isNotBlank()) globalQuery = state.globalQuery
    }
    LaunchedEffect(state.providers, state.provider) {
        if (state.provider == null && state.providers.isNotEmpty()) {
            delay(200)
            runCatching { firstFocus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
        ScreenHeader(
            title = "CloudStream",
            subtitle = "Browse extension catalogues, search their own sites and play their streams",
            onBack = onBack,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = globalQuery,
                onValueChange = {
                    globalQuery = it
                    if (it.isBlank()) onSearchAll("")
                },
                singleLine = true,
                textStyle = NuvioTheme.textStyles.body.copy(color = NuvioTheme.colors.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchAll(globalQuery) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(globalFocus)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NuvioTheme.colors.BackgroundElevated)
                    .onFocusChanged { globalFocused = it.isFocused }
                    .then(
                        if (globalFocused) {
                            Modifier.border(2.dp, NuvioTheme.colors.FocusRing, RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    if (globalQuery.isBlank()) {
                        Text("Search all extensions", color = NuvioTheme.colors.TextTertiary)
                    }
                    inner()
                },
            )
            Button(onClick = { onSearchAll(globalQuery) }) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search all")
            }
        }
        Spacer(Modifier.height(20.dp))
        when {
            state.globalSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
            state.globalQuery.isNotBlank() && state.globalResults.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("No extension results for \"${state.globalQuery}\".",
                    color = NuvioTheme.colors.TextSecondary)
            }
            state.globalQuery.isNotBlank() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 138.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(
                    state.globalResults,
                    key = { "${it.provider.id}|${it.item.url}" },
                ) { result ->
                    GlobalResultCard(result, onClick = { onGlobalResult(result) })
                }
            }
            state.providersLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
            state.providers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No CloudStream extensions are enabled.", color = NuvioTheme.colors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 176.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItemsIndexed(state.providers, key = { _, it -> it.id }) { index, provider ->
                    ProviderCard(
                        provider,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { onOpen(provider) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: LucidPluginExtension,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(106.dp)
            .nuvioFocusRing(RoundedCornerShape(18.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(67.dp).clip(RoundedCornerShape(12.dp)).background(NuvioTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center,
            ) {
                if (!provider.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = provider.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.size(27.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    provider.name,
                    color = NuvioTheme.colors.TextPrimary,
                    style = NuvioTheme.textStyles.sectionTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("Browse provider", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.metadata)
            }
        }
    }
}

@Composable
private fun GlobalResultCard(result: CloudStreamGlobalResult, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .nuvioFocusRing(RoundedCornerShape(14.dp)),
            shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!result.item.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = result.item.poster,
                        contentDescription = result.item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            tint = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Text(
                    result.provider.name,
                    color = NuvioTheme.colors.TextPrimary,
                    style = NuvioTheme.textStyles.metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            result.item.name,
            color = NuvioTheme.colors.TextPrimary,
            style = NuvioTheme.textStyles.metadata,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderBrowse(
    state: CloudStreamUiState,
    onBack: () -> Unit,
    onTab: (LucidPluginTab) -> Unit,
    onItem: (LucidPluginItem) -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val provider = state.provider ?: return
    var query by remember(provider.id) { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var searchOpen by remember(provider.id) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            delay(150)
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.activeTab, state.items, state.searchResults) {
        if (state.items.isNotEmpty() || state.searchResults.isNotEmpty()) {
            delay(200)
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
        ScreenHeader(
            title = provider.name,
            subtitle = "CloudStream extension",
            onBack = onBack,
            trailing = {
                Button(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen && query.isNotBlank()) {
                        query = ""
                        onSearch("")
                    }
                }) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (searchOpen) "Hide search" else "Search")
                }
            },
        )
        if (searchOpen) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.isBlank()) onSearch("")
                    },
                    singleLine = true,
                    textStyle = NuvioTheme.textStyles.body.copy(color = NuvioTheme.colors.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus)
                        .clip(RoundedCornerShape(14.dp))
                        .background(NuvioTheme.colors.BackgroundElevated)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .then(
                            if (searchFocused) {
                                Modifier.border(2.dp, NuvioTheme.colors.FocusRing, RoundedCornerShape(14.dp))
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    decorationBox = { inner ->
                        if (query.isBlank()) {
                            Text("Search this extension", color = NuvioTheme.colors.TextTertiary)
                        }
                        inner()
                    },
                )
                Button(onClick = { onSearch(query) }) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Search")
                }
            }
        }
        if (state.tabs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(state.tabs, key = { "${it.apiIndex}|${it.tabName}" }) { tab ->
                    TabChip(
                        label = tab.label,
                        selected = state.activeTab == tab,
                        onClick = { onTab(tab) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        val shown = if (state.searchQuery.isNotBlank()) state.searchResults else state.items
        when {
            state.loading && shown.isEmpty() && state.searchQuery.isBlank() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
            state.error != null && shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error.orEmpty(), color = NuvioTheme.colors.TextSecondary)
            }
            state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.searchQuery.isNotBlank()) "No results." else "This provider has no items in this tab.",
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 138.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItemsIndexed(shown, key = { _, it -> "${it.apiIndex}|${it.url}" }) { index, item ->
                    ItemCard(
                        item,
                        modifier = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier,
                        onClick = { onItem(item) },
                    )
                }
                if (state.searchQuery.isBlank() && state.hasNext) {
                    item(key = "load-more") {
                        Card(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                            shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
                            colors = CardDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundElevated,
                                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Load more", color = NuvioTheme.colors.TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(46.dp).nuvioFocusRing(RoundedCornerShape(50)),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ItemCard(
    item: LucidPluginItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .nuvioFocusRing(RoundedCornerShape(14.dp)),
            shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!item.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            tint = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.name,
            color = NuvioTheme.colors.TextPrimary,
            style = NuvioTheme.textStyles.metadata,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.year != null) {
            Text(item.year.toString(), color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.metadata)
        }
    }
}

@Composable
private fun DetailOverlay(
    detail: LucidPluginDetail,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onEpisode: (LucidPluginItem) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val firstEpisodeFocus = remember { FocusRequester() }
    val episodes = remember(detail) {
        detail.episodes.sortedWith(
            compareBy(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.name },
            ),
        )
    }
    LaunchedEffect(detail.name, detail.isSeries, episodes.size) {
        delay(180)
        runCatching {
            if (detail.isSeries && episodes.isNotEmpty()) {
                firstEpisodeFocus.requestFocus()
            } else {
                playFocus.requestFocus()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        if (!detail.poster.isNullOrBlank()) {
            AsyncImage(
                model = detail.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The Nuvio detail hero: a darkened backdrop, content anchored to the bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.42f to Color.Black.copy(alpha = 0.38f),
                    0.72f to NuvioTheme.colors.Background.copy(alpha = 0.88f),
                    1f to NuvioTheme.colors.Background,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to NuvioTheme.colors.Background.copy(alpha = 0.72f),
                    0.58f to Color.Transparent,
                    1f to Color.Transparent,
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.nuvioFocusRing(RoundedCornerShape(50)),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Black.copy(alpha = 0.46f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "CloudStream",
                        color = Color.White.copy(alpha = 0.75f),
                        style = NuvioTheme.textStyles.metadata,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth(0.68f)) {
                Text(
                    detail.name,
                    color = Color.White,
                    style = NuvioTheme.textStyles.headline.copy(
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        shadow = Shadow(Color.Black, Offset(0f, 2f), 12f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    detail.year?.let { DetailBadge(it.toString()) }
                    DetailBadge(if (detail.isSeries) "Series" else "Movie")
                    if (detail.isSeries && episodes.isNotEmpty()) {
                        DetailBadge("${episodes.size} episodes")
                    }
                    episodes.firstOrNull()?.runtimeMs
                        ?.takeIf { it > 0L }
                        ?.let { DetailBadge(formatDetailRuntime(it)) }
                }
                if (!detail.description.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        detail.description,
                        color = Color.White.copy(alpha = 0.86f),
                        style = NuvioTheme.textStyles.body.copy(
                            shadow = Shadow(Color.Black, Offset(0f, 1f), 6f),
                        ),
                        maxLines = if (detail.isSeries) 3 else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CloudStreamPlayButton(
                        text = if (detail.isSeries && episodes.isNotEmpty()) {
                            "Play first episode"
                        } else {
                            "Play"
                        },
                        enabled = !loading,
                        focusRequester = playFocus,
                        onClick = {
                            if (detail.isSeries && episodes.isNotEmpty()) {
                                onEpisode(episodes.first())
                            } else {
                                onPlay()
                            }
                        },
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Resolving streams…", color = Color.White.copy(alpha = 0.8f))
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = NuvioTheme.colors.Error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (detail.isSeries && episodes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Episodes",
                    color = Color.White,
                    style = NuvioTheme.textStyles.sectionTitle.copy(
                        shadow = Shadow(Color.Black, Offset(0f, 1f), 6f),
                    ),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 24.dp),
                ) {
                    itemsIndexed(
                        episodes,
                        key = { _, it -> "${it.season}|${it.episode}|${it.url}" },
                    ) { index, episode ->
                        CloudStreamEpisodeCard(
                            episode = episode,
                            fallbackPoster = detail.poster,
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstEpisodeFocus)
                            } else {
                                Modifier
                            },
                            onClick = { onEpisode(episode) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudStreamPlayButton(
    text: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusRequester(focusRequester).nuvioFocusRing(RoundedCornerShape(50)),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = Color.White,
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailBadge(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.44f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CloudStreamEpisodeCard(
    episode: LucidPluginItem,
    fallbackPoster: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val prefix = when {
        episode.season != null && episode.episode != null -> "S${episode.season} E${episode.episode}"
        episode.episode != null -> "E${episode.episode}"
        else -> "Episode"
    }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        modifier = modifier.width(230.dp).height(130.dp).nuvioFocusRing(shape),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = episode.poster ?: fallbackPoster,
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.38f),
                        1f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    prefix,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    episode.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.runtimeMs?.takeIf { it > 0L }?.let {
                    Text(formatDetailRuntime(it), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                }
            }
        }
    }
}

private fun formatDetailRuntime(runtimeMs: Long): String {
    val minutes = (runtimeMs / 60_000L).coerceAtLeast(1L)
    return if (minutes >= 60) {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "${hours}h" else "${hours}h ${rest}m"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Back")
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = NuvioTheme.colors.TextPrimary, style = NuvioTheme.textStyles.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
