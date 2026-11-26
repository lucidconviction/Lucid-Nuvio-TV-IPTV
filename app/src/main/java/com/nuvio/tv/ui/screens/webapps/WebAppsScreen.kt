package com.nuvio.tv.ui.screens.webapps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.components.nuvioFocusRing
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.UUID

private const val WEB_HOME_URL = "https://www.google.com"

private enum class WebAppsMode { HOME, MARKET }

private fun faviconUrl(url: String): String =
    "https://www.google.com/s2/favicons?sz=128&domain_url=" +
        URLEncoder.encode(url, "UTF-8")

/**
 * The TV Web-Apps hub: saved web apps on Home and the bundled FMHY market. Both open the
 * internal TV browser, which keeps its own tabs, pointer and media handoff.
 */
@Composable
fun WebAppsScreen(
    onBack: () -> Unit,
    onOpenBrowser: (url: String) -> Unit,
) {
    val context = LocalContext.current
    val tokens = NuvioTheme.colors
    var mode by remember { mutableStateOf(WebAppsMode.HOME) }
    var sites by remember { mutableStateOf<List<TvSavedSite>>(emptyList()) }
    var marketApps by remember { mutableStateOf<List<TvMarketApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(TvWebMarket.groups.first().id) }
    var categoryId by remember { mutableStateOf(TvWebMarket.groups.first().categories.first().id) }
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TvSavedSite?>(null) }

    val firstHomeFocus = remember { FocusRequester() }
    val firstMarketFocus = remember { FocusRequester() }
    val group = TvWebMarket.groups.firstOrNull { it.id == groupId } ?: TvWebMarket.groups.first()
    val category = group.categories.firstOrNull { it.id == categoryId } ?: group.categories.first()
    val visible = if (query.isBlank()) {
        marketApps.filter { it.categoryId == category.id }
    } else {
        marketApps.filter { TvWebMarket.matches(query, it) }
    }

    fun persist(next: List<TvSavedSite>) {
        sites = next
        TvWebAppsStore.save(context, next)
    }

    fun saveMarketApp(app: TvMarketApp) {
        if (sites.any { it.url == app.url }) return
        persist(sites + TvSavedSite(UUID.randomUUID().toString(), app.name, app.url))
    }

    fun toggleSaved(app: TvMarketApp) {
        if (sites.any { it.url == app.url }) {
            persist(sites.filterNot { it.url == app.url })
        } else {
            saveMarketApp(app)
        }
    }

    LaunchedEffect(Unit) {
        sites = TvWebAppsStore.loadOrSeed(context)
        marketApps = TvWebMarket.load(context)
        delay(120)
        runCatching { firstHomeFocus.requestFocus() }
    }
    LaunchedEffect(mode, groupId, categoryId) {
        delay(120)
        runCatching {
            if (mode == WebAppsMode.HOME) firstHomeFocus.requestFocus() else firstMarketFocus.requestFocus()
        }
    }

    BackHandler(enabled = addOpen || mode == WebAppsMode.MARKET) {
        if (addOpen) {
            addOpen = false
            editing = null
        } else {
            mode = WebAppsMode.HOME
        }
    }

    Box(Modifier.fillMaxSize().background(tokens.Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Web-Apps", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Saved websites and the SFW web-app market",
                        color = tokens.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { onOpenBrowser(WEB_HOME_URL) }, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browser")
                }
                Button(
                    onClick = {
                        mode = if (mode == WebAppsMode.HOME) WebAppsMode.MARKET else WebAppsMode.HOME
                    },
                    modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        if (mode == WebAppsMode.HOME) Icons.Default.Public else Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (mode == WebAppsMode.HOME) "Market" else "Home")
                }
                if (mode == WebAppsMode.HOME) {
                    Button(
                        onClick = { editing = null; addOpen = true },
                        modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add site")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                WebAppsMode.HOME -> HomeContent(
                    sites = sites,
                    firstFocus = firstHomeFocus,
                    onOpen = onOpenBrowser,
                    onAdd = { editing = null; addOpen = true },
                    onMarket = { mode = WebAppsMode.MARKET },
                    onRemove = { site -> persist(sites.filterNot { it.id == site.id }) },
                )
                WebAppsMode.MARKET -> MarketContent(
                    apps = visible,
                    groups = TvWebMarket.groups,
                    group = group,
                    category = category,
                    query = query,
                    firstFocus = firstMarketFocus,
                    savedUrls = sites.mapTo(HashSet()) { it.url },
                    onGroup = {
                        groupId = it.id
                        categoryId = it.categories.first().id
                        query = ""
                    },
                    onCategory = { categoryId = it.id; query = "" },
                    onQuery = { query = it },
                    onOpen = onOpenBrowser,
                    onToggleSaved = ::toggleSaved,
                )
            }
        }

        if (addOpen) {
            AddSiteOverlay(
                initial = editing,
                onSave = { name, url ->
                    if (url.isNotBlank()) {
                        val normalized = if (url.startsWith("http")) url else "https://$url"
                        val existing = editing
                        val next = if (existing != null) {
                            sites.map {
                                if (it.id == existing.id) {
                                    it.copy(name = name.ifBlank { TvWebMarket.hostOf(normalized) }, url = normalized)
                                } else {
                                    it
                                }
                            }
                        } else {
                            sites + TvSavedSite(
                                UUID.randomUUID().toString(),
                                name.ifBlank { TvWebMarket.hostOf(normalized) },
                                normalized,
                            )
                        }
                        persist(next)
                    }
                    addOpen = false
                    editing = null
                },
                onDismiss = { addOpen = false; editing = null },
            )
        }
    }
}

@Composable
private fun HomeContent(
    sites: List<TvSavedSite>,
    firstFocus: FocusRequester,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onMarket: () -> Unit,
    onRemove: (TvSavedSite) -> Unit,
) {
    val tokens = NuvioTheme.colors
    if (sites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No saved web apps yet.", color = tokens.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAdd, modifier = Modifier.focusRequester(firstFocus).nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Text("Add a site")
                    }
                    Button(onClick = onMarket, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Text("Open market")
                    }
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(firstFocus),
        contentPadding = PaddingValues(bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        gridItems(sites, key = { it.id }) { site ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SiteCard(
                    site = site,
                    modifier = Modifier.weight(1f)
                        .then(if (site == sites.first()) Modifier.focusRequester(firstFocus) else Modifier),
                    onClick = { onOpen(site.url) },
                )
                IconButton(onClick = { onRemove(site) }, modifier = Modifier.nuvioFocusRing(CircleShape)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove ${site.name}", tint = tokens.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SiteCard(site: TvSavedSite, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = NuvioTheme.colors
    Card(
        onClick = onClick,
        modifier = modifier.height(92.dp).nuvioFocusRing(RoundedCornerShape(16.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = tokens.BackgroundElevated,
            focusedContainerColor = tokens.BackgroundCard,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = faviconUrl(site.url),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(tokens.BackgroundCard),
            )
            Column(Modifier.weight(1f)) {
                Text(site.name, color = tokens.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(TvWebMarket.hostOf(site.url), color = tokens.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MarketContent(
    apps: List<TvMarketApp>,
    groups: List<TvMarketGroup>,
    group: TvMarketGroup,
    category: TvMarketCategory,
    query: String,
    firstFocus: FocusRequester,
    savedUrls: Set<String>,
    onGroup: (TvMarketGroup) -> Unit,
    onCategory: (TvMarketCategory) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggleSaved: (TvMarketApp) -> Unit,
) {
    val tokens = NuvioTheme.colors
    var queryText by remember(query) { mutableStateOf(query) }
    var searchFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = queryText,
                onValueChange = { queryText = it; onQuery(it) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = tokens.TextPrimary, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQuery(queryText) }),
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.BackgroundElevated)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            runCatching { firstFocus.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    .then(if (searchFocused) Modifier.border(2.dp, tokens.FocusRing, RoundedCornerShape(14.dp)) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    if (queryText.isBlank()) Text("Search all market apps", color = tokens.TextTertiary)
                    inner()
                },
            )
            Button(onClick = { onQuery(queryText) }, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Search")
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.id }) { item ->
                ChipButton(item.label, item.id == group.id, Modifier.nuvioFocusRing(RoundedCornerShape(50))) { onGroup(item) }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(group.categories, key = { it.id }) { item ->
                ChipButton(item.label, item.id == category.id, Modifier.nuvioFocusRing(RoundedCornerShape(50))) { onCategory(item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No market apps match.", color = tokens.TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(firstFocus),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(apps, key = { it.url }) { app ->
                    val saved = app.url in savedUrls
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MarketCard(
                            app = app,
                            modifier = Modifier.weight(1f)
                                .then(if (app == apps.first()) Modifier.focusRequester(firstFocus) else Modifier),
                            onClick = { onOpen(app.url) },
                        )
                        IconButton(onClick = { onToggleSaved(app) }, modifier = Modifier.nuvioFocusRing(CircleShape)) {
                            Icon(
                                if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (saved) "Remove ${app.name}" else "Save ${app.name}",
                                tint = if (saved) tokens.FocusRing else tokens.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketCard(app: TvMarketApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = NuvioTheme.colors
    Card(
        onClick = onClick,
        modifier = modifier.height(92.dp).nuvioFocusRing(RoundedCornerShape(16.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = tokens.BackgroundElevated,
            focusedContainerColor = tokens.BackgroundCard,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = faviconUrl(app.url),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(tokens.BackgroundCard),
            )
            Column(Modifier.weight(1f)) {
                Text(app.name, color = tokens.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(TvWebMarket.hostOf(app.url), color = tokens.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChipButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddSiteOverlay(
    initial: TvSavedSite?,
    onSave: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(20.dp)).background(tokens.BackgroundElevated).padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initial == null) "Add web app" else "Edit web app", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Field(
                    label = "Name",
                    value = name,
                    focusRequester = nameFocus,
                    onFocus = { focused = it },
                    onDown = { runCatching { urlFocus.requestFocus() } },
                ) { name = it }
                Field(
                    label = "URL",
                    value = url,
                    focusRequester = urlFocus,
                    onFocus = {},
                    onUp = { runCatching { nameFocus.requestFocus() } },
                    onDown = { runCatching { saveFocus.requestFocus() } },
                ) { url = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onSave(name, url) },
                        modifier = Modifier.focusRequester(saveFocus).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) { Text("Save") }
                    Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) { Text("Cancel") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { nameFocus.requestFocus() } }
}

@Composable
private fun Field(
    label: String,
    value: String,
    focusRequester: FocusRequester?,
    onFocus: (Boolean) -> Unit,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    val tokens = NuvioTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = tokens.TextSecondary, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = tokens.TextPrimary, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (label == "URL") KeyboardType.Uri else KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> if (onUp != null) { onUp(); true } else false
                            Key.DirectionDown -> if (onDown != null) { onDown(); true } else false
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.BackgroundCard)
                .then(if (focused) Modifier.border(2.dp, tokens.FocusRing, RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            decorationBox = { inner ->
                if (value.isBlank()) Text("https://", color = tokens.TextTertiary)
                inner()
            },
        )
    }
}
