@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui.lucid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.livetv.lucid.LucidChannelKind
import com.nuvio.tv.livetv.lucid.LucidOrder
import com.nuvio.tv.livetv.lucid.LucidStoredSpec
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StudioMode {
    LIST,
    ADD_MENU,
    CATALOG_ADDON,
    CATALOG_CATALOG,
    SERIES_ADDON,
    SERIES_CATALOG,
    SERIES_ITEM,
    MANUAL_ITEM,
    LIVE_ADDON,
    LIVE_CATALOG,
    LIVE_ITEM,
    RELAY_GROUP,
    EDIT,
}

/**
 * The Lucid TV Channel Studio. Addon catalogs, series marathons and live-channel relays are
 * created here; auto-discovered catalog channels stay automatic and are not listed as custom.
 */
@Composable
fun LucidTvStudioScreen(
    onBack: () -> Unit,
    viewModel: LucidTvStudioViewModel = hiltViewModel(),
) {
    val specs by viewModel.specs.collectAsStateWithLifecycle()
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val relayGroups by viewModel.relayGroups.collectAsStateWithLifecycle()
    val runtimeChannels by viewModel.lucid.channels.collectAsStateWithLifecycle()
    val runtimePrograms by viewModel.lucid.programs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(StudioMode.LIST) }
    var pendingKind by remember { mutableStateOf(LucidChannelKind.CATALOG_ROTATE) }
    var draft by remember { mutableStateOf<LucidStoredSpec?>(null) }
    var addonPick by remember { mutableStateOf<Addon?>(null) }
    var catalogPick by remember { mutableStateOf<CatalogDescriptor?>(null) }
    var seriesItems by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var seriesLoading by remember { mutableStateOf(false) }
    var seriesError by remember { mutableStateOf<String?>(null) }
    var manualItems by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var manualSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manualLoading by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var liveItems by remember { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var liveLoading by remember { mutableStateOf(false) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var shareOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var nowTick by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowTick++
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000L)
            notice = null
        }
    }
    LaunchedEffect(
        mode, specs.size, addons.size, relayGroups.size, addonPick,
        seriesItems.size, seriesLoading, manualItems.size, manualLoading,
        liveItems.size, liveLoading,
    ) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    fun stepBack() {
        when (mode) {
            StudioMode.LIST -> onBack()
            StudioMode.EDIT -> { draft = null; mode = StudioMode.LIST }
            StudioMode.SERIES_ITEM -> { mode = StudioMode.SERIES_CATALOG; seriesItems = emptyList() }
            StudioMode.MANUAL_ITEM -> { mode = StudioMode.CATALOG_CATALOG; manualItems = emptyList(); manualSelected = emptySet() }
            StudioMode.LIVE_ITEM -> { mode = StudioMode.LIVE_CATALOG; liveItems = emptyList() }
            StudioMode.LIVE_CATALOG -> mode = StudioMode.LIVE_ADDON
            StudioMode.CATALOG_CATALOG, StudioMode.SERIES_CATALOG -> {
                mode = if (mode == StudioMode.CATALOG_CATALOG) StudioMode.CATALOG_ADDON else StudioMode.SERIES_ADDON
            }
            StudioMode.CATALOG_ADDON, StudioMode.SERIES_ADDON, StudioMode.LIVE_ADDON, StudioMode.RELAY_GROUP -> mode = StudioMode.ADD_MENU
            else -> mode = StudioMode.LIST
        }
    }
    BackHandler { stepBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Lucid TV channels",
                style = NuvioTheme.textStyles.sectionTitle,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (mode == StudioMode.LIST) {
                Button(onClick = { mode = StudioMode.ADD_MENU }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add channel", fontSize = 12.sp)
                }
            }
            Button(onClick = { importOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Import", fontSize = 11.sp)
            }
            Button(
                onClick = { shareOpen = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Share", fontSize = 11.sp)
            }
            if (mode == StudioMode.LIST) {
                Button(onClick = onBack) { Text("Done") }
            } else {
                Button(onClick = { stepBack() }) { Text("Back") }
            }
        }
        notice?.let {
            Text(it, modifier = Modifier.padding(top = 8.dp), style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))

        when (mode) {
            StudioMode.LIST -> StudioList(
                specs = specs,
                runtimeChannels = runtimeChannels.map { it.key }.toSet(),
                programs = runtimePrograms,
                nowMs = remember(nowTick) { System.currentTimeMillis() },
                firstFocus = firstFocus,
                onAdd = { mode = StudioMode.ADD_MENU },
                onEdit = { draft = it; mode = StudioMode.EDIT },
            )
            StudioMode.ADD_MENU -> AddMenu(firstFocus) { kind, target ->
                pendingKind = kind
                mode = target
            }
            StudioMode.CATALOG_ADDON -> AddonList(
                addons = addons,
                forSeries = false,
                firstFocus = firstFocus,
                onPick = { addonPick = it; mode = StudioMode.CATALOG_CATALOG },
            )
            StudioMode.CATALOG_CATALOG -> CatalogList(
                catalogs = addonPick?.let { viewModel.catalogsFor(it, forSeries = false) }.orEmpty(),
                firstFocus = firstFocus,
                onPick = { catalog ->
                    val addon = addonPick ?: return@CatalogList
                    if (pendingKind == LucidChannelKind.MANUAL_LOOP) {
                        catalogPick = catalog
                        manualItems = emptyList()
                        manualSelected = emptySet()
                        manualError = null
                        manualLoading = true
                        mode = StudioMode.MANUAL_ITEM
                        scope.launch {
                            viewModel.catalogItems(addon, catalog)
                                .onSuccess { manualItems = it.filter { item -> item.apiType == "movie" } }
                                .onFailure { manualError = it.message ?: "The catalog couldn't be loaded." }
                            manualLoading = false
                        }
                    } else {
                        draft = viewModel.newSpec(
                            name = "${addon.displayName} · ${catalog.name}",
                            kind = pendingKind,
                            addon = addon,
                            catalog = catalog,
                        )
                        mode = StudioMode.EDIT
                    }
                },
            )
            StudioMode.SERIES_ADDON -> AddonList(
                addons = addons,
                forSeries = true,
                firstFocus = firstFocus,
                onPick = { addonPick = it; mode = StudioMode.SERIES_CATALOG },
            )
            StudioMode.LIVE_ADDON -> AddonList(
                addons = addons,
                forLive = true,
                firstFocus = firstFocus,
                onPick = { addonPick = it; mode = StudioMode.LIVE_CATALOG },
            )
            StudioMode.LIVE_CATALOG -> CatalogList(
                catalogs = addonPick?.let { viewModel.liveCatalogsFor(it) }.orEmpty(),
                firstFocus = firstFocus,
                onPick = { catalog ->
                    val addon = addonPick ?: return@CatalogList
                    catalogPick = catalog
                    liveItems = emptyList()
                    liveError = null
                    liveLoading = true
                    mode = StudioMode.LIVE_ITEM
                    scope.launch {
                        viewModel.catalogItems(addon, catalog)
                            .onSuccess { liveItems = it }
                            .onFailure { liveError = it.message ?: "The catalog couldn't be loaded." }
                        liveLoading = false
                    }
                },
            )
            StudioMode.LIVE_ITEM -> SeriesItemList(
                items = liveItems,
                loading = liveLoading,
                error = liveError,
                firstFocus = firstFocus,
                onPick = { item ->
                    val addon = addonPick ?: return@SeriesItemList
                    val catalog = catalogPick ?: return@SeriesItemList
                    draft = viewModel.newSpec(
                        name = item.name,
                        kind = LucidChannelKind.ADDON_LIVE,
                        addon = addon,
                        catalog = catalog,
                        poster = item.poster,
                        manualItems = listOf(viewModel.manualItem(item, addon)),
                    )
                    mode = StudioMode.EDIT
                },
            )
            StudioMode.SERIES_CATALOG -> CatalogList(
                catalogs = addonPick?.let { viewModel.catalogsFor(it, forSeries = true) }.orEmpty(),
                firstFocus = firstFocus,
                onPick = { catalog ->
                    val addon = addonPick ?: return@CatalogList
                    catalogPick = catalog
                    seriesItems = emptyList()
                    seriesError = null
                    seriesLoading = true
                    mode = StudioMode.SERIES_ITEM
                    scope.launch {
                        viewModel.catalogItems(addon, catalog)
                            .onSuccess { seriesItems = it.filter { item -> item.apiType == "series" } }
                            .onFailure { seriesError = it.message ?: "The catalog couldn't be loaded." }
                        seriesLoading = false
                    }
                },
            )
            StudioMode.SERIES_ITEM -> SeriesItemList(
                items = seriesItems,
                loading = seriesLoading,
                error = seriesError,
                firstFocus = firstFocus,
                onPick = { item ->
                    val addon = addonPick ?: return@SeriesItemList
                    val catalog = catalogPick ?: return@SeriesItemList
                    draft = viewModel.newSpec(
                        name = item.name,
                        kind = LucidChannelKind.SERIES_LOOP,
                        addon = addon,
                        catalog = catalog,
                        seriesId = item.id,
                        poster = item.poster,
                    )
                    mode = StudioMode.EDIT
                },
            )
            StudioMode.MANUAL_ITEM -> ManualItemList(
                items = manualItems,
                selected = manualSelected,
                loading = manualLoading,
                error = manualError,
                firstFocus = firstFocus,
                onToggle = { id ->
                    manualSelected = if (id in manualSelected) manualSelected - id else manualSelected + id
                },
                onCreate = {
                    val addon = addonPick
                    val catalog = catalogPick
                    if (addon != null && catalog != null) {
                        val chosen = manualItems.filter { it.id in manualSelected }
                        if (chosen.isEmpty()) {
                            notice = "Pick at least one film first."
                        } else {
                            draft = viewModel.newSpec(
                                name = "${addon.displayName} · ${catalog.name} · 24/7",
                                kind = LucidChannelKind.MANUAL_LOOP,
                                addon = addon,
                                catalog = catalog,
                                manualItems = chosen.map { viewModel.manualItem(it, addon) },
                            )
                            mode = StudioMode.EDIT
                        }
                    }
                },
            )
            StudioMode.RELAY_GROUP -> RelayGroupList(
                groups = relayGroups,
                firstFocus = firstFocus,
                onPick = { group ->
                    draft = viewModel.newSpec(
                        name = "${group.name} · 24/7",
                        kind = LucidChannelKind.RELAY_LOOP,
                        relayGroupId = group.id,
                    )
                    mode = StudioMode.EDIT
                },
            )
            StudioMode.EDIT -> {
                val current = draft
                if (current == null) {
                    mode = StudioMode.LIST
                } else {
                    Editor(
                        draft = current,
                        onChange = { draft = it },
                        firstFocus = firstFocus,
                        existing = specs.any { it.id == current.id },
                        now = runtimePrograms[current.channelKey].orEmpty(),
                        onSave = {
                            viewModel.save(current)
                            notice = "Channel saved."
                            draft = null
                            mode = StudioMode.LIST
                        },
                        onDelete = {
                            viewModel.delete(current.id)
                            notice = "Channel deleted."
                            draft = null
                            mode = StudioMode.LIST
                        },
                        onDuplicate = {
                            viewModel.duplicate(current)
                            notice = "Channel duplicated."
                            draft = null
                            mode = StudioMode.LIST
                        },
                        onCancel = { draft = null; mode = StudioMode.LIST },
                    )
                }
            }
        }
    }
    if (shareOpen) {
        LucidShareDialog(
            specs = specs,
            onDismiss = { shareOpen = false },
        )
    }
    if (importOpen) {
        LucidImportDialog(
            importText = { viewModel.importJson(it) },
            onImported = { count ->
                notice = "Imported $count channel${if (count == 1) "" else "s"}."
                importOpen = false
            },
            onDismiss = { importOpen = false },
        )
    }
}

@Composable
private fun StudioList(
    specs: List<LucidStoredSpec>,
    runtimeChannels: Set<String>,
    programs: Map<String, List<EpgProgram>>,
    nowMs: Long,
    firstFocus: FocusRequester,
    onAdd: () -> Unit,
    onEdit: (LucidStoredSpec) -> Unit,
) {
    if (specs.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "No custom Lucid TV channels yet. Addon catalogs already appear automatically in the guide.",
                color = NuvioTheme.colors.TextSecondary,
            )
            Button(onClick = onAdd, modifier = Modifier.focusRequester(firstFocus)) { Text("Add a channel") }
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        itemsIndexed(specs, key = { _, spec -> spec.id }) { index, spec ->
            val current = programs[spec.channelKey].orEmpty().firstOrNull { nowMs in it.startMs until it.stopMs }
            val next = programs[spec.channelKey].orEmpty().firstOrNull { it.startMs >= (current?.stopMs ?: nowMs) }
            Button(
                onClick = { onEdit(spec) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!spec.enabled) {
                            Spacer(Modifier.width(8.dp))
                            Text("OFF", fontSize = 10.sp, color = NuvioTheme.colors.TextSecondary)
                        }
                    }
                    Text(
                        "${kindLabel(spec.kind)} · ${spec.group}" +
                            (if (spec.channelKey !in runtimeChannels) " · waiting for source" else ""),
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    Text(
                        nowNextLabel(current, next),
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMenu(firstFocus: FocusRequester, onPick: (LucidChannelKind, StudioMode) -> Unit) {
    val items = listOf(
        Triple("Addon catalog · rotating", "Films from an addon catalog, joined in progress in 90-minute slots.", LucidChannelKind.CATALOG_ROTATE to StudioMode.CATALOG_ADDON),
        Triple("Addon catalog · 24/7", "The same catalog as a continuous queue, using each film's real runtime.", LucidChannelKind.CATALOG_LOOP to StudioMode.CATALOG_ADDON),
        Triple("Series marathon · 24/7", "One series, every episode in order, using the episode runtimes.", LucidChannelKind.SERIES_LOOP to StudioMode.SERIES_ADDON),
        Triple("Movie collection · 24/7", "Pick films from a catalog into a hand-made 24/7 marathon.", LucidChannelKind.MANUAL_LOOP to StudioMode.CATALOG_ADDON),
        Triple("Addon live channel", "One channel from an addon live, TV or sport catalog. Its stream is resolved when tuned.", LucidChannelKind.ADDON_LIVE to StudioMode.LIVE_ADDON),
        Triple("Live channel relay · 24/7", "Relay an existing IPTV group as a Lucid TV channel and switch at segment boundaries.", LucidChannelKind.RELAY_LOOP to StudioMode.RELAY_GROUP),
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items) { index, (title, subtitle, target) ->
            Button(
                onClick = { onPick(target.first, target.second) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp)
                    Text(subtitle, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun AddonList(
    addons: List<Addon>,
    forSeries: Boolean = false,
    forLive: Boolean = false,
    firstFocus: FocusRequester,
    onPick: (Addon) -> Unit,
) {
    val wanted = when {
        forLive -> setOf("live", "tv", "sport", "sports")
        forSeries -> setOf("series")
        else -> setOf("movie")
    }
    val choices = addons.filter { addon ->
        addon.enabled && addon.catalogs.any {
            it.apiType in wanted &&
                !it.isSearchOnlyCatalog() &&
                it.extra.none { extra -> extra.isRequired }
        }
    }
    if (choices.isEmpty()) {
        Text(
            when {
                forLive -> "No installed addon has a live, TV or sport catalog."
                forSeries -> "No installed addon has a series catalog."
                else -> "No installed addon has a movie catalog."
            },
            color = NuvioTheme.colors.TextSecondary,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(choices, key = { _, addon -> addon.id + addon.baseUrl }) { index, addon ->
            Button(
                onClick = { onPick(addon) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Text(addon.displayName, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${addon.catalogs.count { it.apiType in wanted && !it.isSearchOnlyCatalog() && it.extra.none { e -> e.isRequired } }} catalogs", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun CatalogList(
    catalogs: List<CatalogDescriptor>,
    firstFocus: FocusRequester,
    onPick: (CatalogDescriptor) -> Unit,
) {
    if (catalogs.isEmpty()) {
        Text("No catalogs here.", color = NuvioTheme.colors.TextSecondary)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(catalogs, key = { _, catalog -> catalog.id }) { index, catalog ->
            Button(
                onClick = { onPick(catalog) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Text(catalog.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(catalog.apiType, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun SeriesItemList(
    items: List<MetaPreview>,
    loading: Boolean,
    error: String?,
    firstFocus: FocusRequester,
    onPick: (MetaPreview) -> Unit,
) {
    when {
        loading -> Text("Loading the series catalog…", color = NuvioTheme.colors.TextSecondary)
        error != null -> Text(error, color = NuvioTheme.colors.Warning)
        items.isEmpty() -> Text("This series catalog is empty.", color = NuvioTheme.colors.TextSecondary)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                Button(
                    onClick = { onPick(item) },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(8.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(44.dp).height(64.dp).background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(item.releaseInfo, item.imdbRating?.let { "★ $it" }).joinToString(" · "),
                            style = NuvioTheme.textStyles.metadata,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayGroupList(
    groups: List<LucidRelayGroup>,
    firstFocus: FocusRequester,
    onPick: (LucidRelayGroup) -> Unit,
) {
    if (groups.isEmpty()) {
        Text("No playlist groups to relay yet.", color = NuvioTheme.colors.TextSecondary)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
            Button(
                onClick = { onPick(group) },
                modifier = Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) {
                Text(group.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${group.channelCount} channels", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ManualItemList(
    items: List<MetaPreview>,
    selected: Set<String>,
    loading: Boolean,
    error: String?,
    firstFocus: FocusRequester,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            if (selected.isEmpty()) "Pick films from this catalog, then use Set list." else "${selected.size} film(s) selected. Use Set list to continue.",
            style = NuvioTheme.textStyles.body,
            color = NuvioTheme.colors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        when {
            loading -> Text("Loading the catalog…", color = NuvioTheme.colors.TextSecondary)
            error != null -> Text(error, color = NuvioTheme.colors.Warning)
            items.isEmpty() -> Text("This catalog is empty.", color = NuvioTheme.colors.TextSecondary)
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val isSelected = item.id in selected
                    Button(
                        onClick = { onToggle(item.id) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        contentPadding = PaddingValues(8.dp),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.9f) else Color.Transparent,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        AsyncImage(
                            model = item.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(40.dp).height(58.dp).background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(item.releaseInfo, item.runtime).joinToString(" · "),
                                style = NuvioTheme.textStyles.metadata,
                                color = NuvioTheme.colors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onCreate) {
            Text(if (selected.isEmpty()) "Set list" else "Set list (${selected.size})")
        }
    }
}

@Composable
private fun Editor(
    draft: LucidStoredSpec,
    onChange: (LucidStoredSpec) -> Unit,
    firstFocus: FocusRequester,
    existing: Boolean,
    now: List<EpgProgram>,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onCancel: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val current = now.firstOrNull { nowMs in it.startMs until it.stopMs }
    val next = now.firstOrNull { it.startMs >= (current?.stopMs ?: nowMs) }
    Column(Modifier.fillMaxSize()) {
        Text(kindLabel(draft.kind), style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(nowNextLabel(current, next), style = NuvioTheme.textStyles.body, color = NuvioTheme.colors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                TextFieldSetting("Name", draft.name) { onChange(draft.copy(name = it)) }
            }
            item {
                TextFieldSetting("Group", draft.group) { onChange(draft.copy(group = it.ifBlank { "Lucid TV" })) }
            }
            if (draft.kind == LucidChannelKind.CATALOG_ROTATE) {
                item {
                    ChipSetting(
                        "Slot length",
                        listOf(30, 45, 60, 90, 120),
                        draft.slotMinutes,
                        first = true,
                        firstFocus = firstFocus,
                    ) { onChange(draft.copy(slotMinutes = it)) }
                }
                item {
                    ChipSetting(
                        "Join films at most",
                        listOf(-1, 0, 10, 20, 30, 50),
                        draft.maxStartPercent,
                    ) { onChange(draft.copy(maxStartPercent = it)) }
                }
            }
            if (draft.kind == LucidChannelKind.CATALOG_LOOP ||
                draft.kind == LucidChannelKind.SERIES_LOOP ||
                draft.kind == LucidChannelKind.MANUAL_LOOP
            ) {
                item {
                    OrderSetting(draft.order, firstFocus) { onChange(draft.copy(order = it)) }
                }
            }
            if (draft.kind == LucidChannelKind.MANUAL_LOOP) {
                item {
                    Text(
                        "${draft.manualItems.size} film(s) in this collection.",
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
            }
            if (draft.kind == LucidChannelKind.RELAY_LOOP) {
                item {
                    ChipSetting(
                        "Segment length",
                        listOf(15, 30, 60, 90),
                        draft.relaySegmentMinutes,
                        first = true,
                        firstFocus = firstFocus,
                    ) { onChange(draft.copy(relaySegmentMinutes = it)) }
                }
            }
            if (draft.kind == LucidChannelKind.ADDON_LIVE) {
                item {
                    Text(
                        "This channel plays one live stream from the addon. The stream is resolved when you tune.",
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
            }
            item {
                Button(
                    onClick = { onChange(draft.copy(enabled = !draft.enabled)) },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (draft.kind == LucidChannelKind.ADDON_LIVE) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.TextPrimary,
                    ),
                ) {
                    Text("Channel enabled", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (draft.enabled) "On" else "Off", fontSize = 12.sp, color = if (draft.enabled) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text("Save channel") }
            if (existing) {
                Button(onClick = onDuplicate) { Text("Duplicate") }
                Button(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Delete")
                }
            }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun TextFieldSetting(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(NuvioTheme.colors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
            modifier = Modifier.fillMaxWidth().height(38.dp),
        ) { inner ->
            Row(
                Modifier.fillMaxSize()
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { inner() }
        }
    }
}

@Composable
private fun ChipSetting(
    label: String,
    values: List<Int>,
    selected: Int,
    first: Boolean = false,
    firstFocus: FocusRequester? = null,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEachIndexed { index, value ->
                val isSelected = value == selected
                Button(
                    onClick = { onSelect(value) },
                    modifier = Modifier.then(if (first && index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                        contentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = if (isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(9.dp)),
                ) {
                    val text = when {
                        label == "Join films at most" && value < 0 -> "Exact"
                        label == "Join films at most" && value == 0 -> "Start"
                        else -> "$value" + if (label.contains("length", ignoreCase = true) || label.contains("films", ignoreCase = true)) {
                            if (label.contains("films", ignoreCase = true)) "%" else "m"
                        } else ""
                    }
                    Text(text, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun OrderSetting(selected: LucidOrder, firstFocus: FocusRequester, onSelect: (LucidOrder) -> Unit) {
    val choices = listOf(LucidOrder.SHUFFLE, LucidOrder.AIR, LucidOrder.RELEASE, LucidOrder.SEQUENTIAL, LucidOrder.MANUAL)
    Column(Modifier.fillMaxWidth()) {
        Text("Order", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEachIndexed { index, order ->
                val isSelected = order == selected
                Button(
                    onClick = { onSelect(order) },
                    modifier = Modifier.then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
                        contentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = if (isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(9.dp)),
                ) {
                    Text(orderLabel(order), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun kindLabel(kind: LucidChannelKind): String = when (kind) {
    LucidChannelKind.CATALOG_ROTATE -> "Addon catalog · rotating"
    LucidChannelKind.CATALOG_LOOP -> "Addon catalog · 24/7"
    LucidChannelKind.SERIES_LOOP -> "Series marathon · 24/7"
    LucidChannelKind.RELAY_LOOP -> "Live channel relay · 24/7"
    LucidChannelKind.ADDON_LIVE -> "Addon live channel"
    LucidChannelKind.MANUAL_LOOP -> "Movie collection · 24/7"
}

private fun orderLabel(order: LucidOrder): String = when (order) {
    LucidOrder.SHUFFLE -> "Shuffle"
    LucidOrder.SEQUENTIAL -> "Sequential"
    LucidOrder.RELEASE -> "Release"
    LucidOrder.AIR -> "Air order"
    LucidOrder.MANUAL -> "Selected order"
}

private fun nowNextLabel(current: EpgProgram?, next: EpgProgram?): String {
    val now = current?.title ?: "No guide data"
    val nextText = next?.let { "Next: ${it.title} at ${timeLabel(it.startMs)}" } ?: "Nothing next"
    return "$now · $nextText"
}

private fun timeLabel(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

