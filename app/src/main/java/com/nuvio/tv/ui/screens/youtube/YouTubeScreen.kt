@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.youtube.TvYouTubeAccountViewModel
import com.nuvio.tv.youtube.TvYouTubeViewModel
import com.nuvio.tv.youtube.YtItem
import com.nuvio.tv.youtube.YtKind
import com.nuvio.tv.youtube.YtRow
import com.nuvio.tv.youtube.YtTab
import com.nuvio.tv.youtube.YouTubeAccountState
import com.nuvio.tv.youtube.YouTubePlaybackSession

/**
 * The YouTube tab, built for the leanback remote: one large horizontal feed, focusable cards and
 * a QR code for Google's TV sign-in. The same engine powers Music and Sports Replays.
 */
@Composable
fun YouTubeScreen(
    onBack: () -> Unit,
    onPlay: (videoId: String, title: String, poster: String?) -> Unit,
    viewModel: TvYouTubeViewModel = hiltViewModel(),
    accountViewModel: TvYouTubeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val account by accountViewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }

    val openVideoFrom: (YtItem, List<YtItem>) -> Unit = { item, list ->
        val id = item.videoId
        if (!id.isNullOrBlank()) {
            YouTubePlaybackSession.set(
                list.filter { it.kind == YtKind.VIDEO && !it.videoId.isNullOrBlank() }
                    .map { YouTubePlaybackSession.Entry(it.videoId!!, it.title, it.thumbnail, it.author) },
                id,
            )
            onPlay(id, item.title, item.thumbnail)
        }
    }

    LaunchedEffect(Unit) { accountViewModel.refresh() }
    BackHandler { if (!viewModel.back()) onBack() }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        NuvioTheme.colors.Secondary.copy(alpha = 0.12f),
                        Color.Transparent,
                        NuvioTheme.colors.Background.copy(alpha = 0.95f),
                    ),
                ),
            ),
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
                    .glassSurface(RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("YouTube", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    account.accountName ?: if (account.signedIn) "Signed in" else "Not signed in",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Button(
                    onClick = { showAccount = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) { Text(if (account.signedIn) "Account" else "Sign in", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(YtTab.entries, key = { it.name }) { tab ->
                    YouTubeChip(
                        label = tab.title,
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
                    modifier = Modifier.weight(1f).height(36.dp),
                ) { inner ->
                    Row(
                        Modifier.fillMaxSize()
                            .glassSurface(RoundedCornerShape(10.dp), strength = 0.55f)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text("Search YouTube", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                            }
                            inner()
                        }
                    }
                }
                Button(
                    onClick = { viewModel.search(query) },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                ) { Text("Search", fontSize = 11.sp) }
            }
            if (state.subTitle != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.back() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Back")
                    }
                    Text(
                        state.subTitle.orEmpty(),
                        style = NuvioTheme.textStyles.sectionTitle,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = state.tab to state.subTitle,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.fillMaxSize(),
                label = "youtube-tab",
            ) { (tab, subtitle) ->
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading && state.rows.isEmpty() && state.grid.isEmpty() -> YouTubeLoading()
                        (error != null || state.error != null) && state.rows.isEmpty() && state.grid.isEmpty() -> {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(error ?: state.error.orEmpty(), color = NuvioTheme.colors.TextSecondary)
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { viewModel.selectTab(tab) }) { Text("Try again") }
                            }
                        }
                        state.grid.isNotEmpty() -> YouTubeGrid(state.grid) { item -> openItem(item, viewModel, state.grid, openVideoFrom) }
                        state.rows.isNotEmpty() -> YouTubeRows(state.rows, viewModel, openVideoFrom)
                        tab.needsSignIn && !state.signedIn -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Sign in to YouTube to see ${tab.title.lowercase()}.", color = NuvioTheme.colors.TextSecondary)
                                    Spacer(Modifier.height(10.dp))
                                    Button(onClick = { showAccount = true }) { Text("Sign in") }
                                }
                            }
                        }
                        subtitle == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing here yet.", color = NuvioTheme.colors.TextSecondary)
                        }
                    }
                }
            }
        }

        if (showAccount) {
            YouTubeAccountOverlay(
                account = account,
                onStartSignIn = { accountViewModel.startSignIn(); showAccount = true },
                onCancelSignIn = accountViewModel::cancelSignIn,
                onSelectAccount = { id -> accountViewModel.selectAccount(id) { showAccount = false } },
                onClose = { showAccount = false; accountViewModel.cancelSignIn() },
            )
        }
    }
}

@Composable
private fun YouTubeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, style = NuvioTheme.textStyles.tab, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun YouTubeLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
    }
}

@Composable
private fun YouTubeRows(
    rows: List<YtRow>,
    viewModel: TvYouTubeViewModel,
    onOpenVideo: (YtItem, List<YtItem>) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        itemsIndexed(rows, key = { index, row -> "${row.title}_$index" }) { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(row.items, key = { it.videoId ?: it.title }) { item ->
                        YouTubeCard(item) { openItem(item, viewModel, row.items, onOpenVideo) }
                    }
                    if (row.canLoadMore) {
                        item {
                            Button(onClick = { viewModel.loadMoreRow(index) }) { Text("More") }
                        }
                    }
                }
            }
        }
        item {
            if (viewModel.canGoBack) {
                Button(onClick = { viewModel.back() }) { Text("Back") }
            } else {
                Button(onClick = { viewModel.loadMore() }) { Text("Load more") }
            }
        }
    }
}

@Composable
private fun YouTubeGrid(
    items: List<YtItem>,
    onClick: (YtItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(items, key = { it.videoId ?: it.title }) { item ->
            YouTubeCard(item, onClick)
        }
    }
}

@Composable
private fun YouTubeCard(item: YtItem, onClick: (YtItem) -> Unit) {
    Card(
        onClick = { onClick(item) },
        modifier = Modifier.width(240.dp),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f),
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard),
            ) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.isLive) {
                    Text(
                        "LIVE",
                        style = NuvioTheme.textStyles.badge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NuvioTheme.colors.Error)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                if (!item.badge.isNullOrBlank()) {
                    Text(
                        item.badge.orEmpty(),
                        style = NuvioTheme.textStyles.badge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                item.title,
                style = NuvioTheme.textStyles.cardTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            Text(
                item.author.orEmpty(),
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
            )
        }
    }
}

private fun openItem(
    item: YtItem,
    viewModel: TvYouTubeViewModel,
    source: List<YtItem>,
    onOpenVideo: (YtItem, List<YtItem>) -> Unit,
) {
    if (item.kind == YtKind.VIDEO) {
        if (!item.videoId.isNullOrBlank()) onOpenVideo(item, source)
    } else {
        viewModel.open(item)
    }
}

@Composable
private fun YouTubeAccountOverlay(
    account: YouTubeAccountState,
    onStartSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSelectAccount: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Card(
            onClick = {},
            modifier = Modifier.width(620.dp),
            shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f),
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f),
            ),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YouTube account", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                if (account.code != null) {
                    val activate = account.activateLink ?: "https://www.youtube.com/activate?user_code=${account.code}"
                    val qr = remember(activate) { QrCodeGenerator.generate(activate, 360, 2).asImageBitmap() }
                    Text("Scan the QR code, or open youtube.com/activate and enter:", color = NuvioTheme.colors.TextSecondary)
                    Image(qr, contentDescription = null, modifier = Modifier.size(260.dp).align(Alignment.CenterHorizontally))
                    Text(
                        account.code.orEmpty(),
                        style = NuvioTheme.textStyles.headline,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onCancelSignIn, modifier = Modifier.focusRequester(firstFocus)) { Text("Cancel") }
                        Button(onClick = onClose) { Text("Close") }
                    }
                } else {
                    Text(
                        if (account.signedIn) "Signed in as ${account.accountName.orEmpty()}" else "Sign in with Google's TV code. No password is typed on this device.",
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    account.error?.let { Text(it, color = NuvioTheme.colors.Error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onStartSignIn, modifier = Modifier.focusRequester(firstFocus)) { Text(if (account.signedIn) "Add another account" else "Sign in") }
                        Button(onClick = onClose) { Text("Close") }
                    }
                    if (account.accounts.isNotEmpty()) {
                        Text("Accounts on this device", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                        account.accounts.forEach { a ->
                            Button(
                                onClick = { onSelectAccount(a.id) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.tv.material3.ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextPrimary,
                                ),
                            ) {
                                Text("${a.name}${if (a.selected) "  ✓" else ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
