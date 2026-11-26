package com.nuvio.tv.livetv.lucid

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lucid TV: addon catalogs, series, hand-picked films and live-channel relays become always-on
 * channels.
 *
 * A channel is a definition plus a lazy pool, never a stream URL. Every VOD kind is a
 * continuous queue with real media runtimes, so the guide and the stream run on the same clock
 * and a programme changes exactly when the film or episode ends. Catalog channels are joined in
 * progress at their real EPG position.
 *
 * Custom channels are saved by the studio; catalog channels are discovered from the installed
 * addons. Both are merged here, and streams are resolved only when a channel is tuned.
 */
@Singleton
class LucidTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: MetaRepository,
    private val liveTvRepository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    private val pluginBridge: LucidPluginBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile = File(File(context.filesDir, "lucid"), "pools.json")
    @Volatile private var cacheSavedAtMs = 0L
    private var saveCacheJob: Job? = null

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Why a channel has nothing to play, for the studio and the start-up report. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    @Volatile private var specs: List<LucidChannelSpec> = emptyList()
    private val pools = ConcurrentHashMap<String, List<LucidItem>>()
    private val poolSources = ConcurrentHashMap<String, String>()
    /** item id -> epoch ms until which it is skipped after "no playable stream". */
    private val unavailable = ConcurrentHashMap<String, Long>()
    /** Where the current queue starts after skipping a broken item. */
    private val queueStart = ConcurrentHashMap<String, String>()
    /** A skipped item re-anchors the queue so the next programme starts at the skip time. */
    private val scheduleAnchor = ConcurrentHashMap<String, Long>()
    private val metaPermits = Semaphore(META_CONCURRENCY)
    private val generation = AtomicInteger()
    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        scope.launch {
            restorePoolCache()
            combine(
                addonRepository.getInstalledAddons().debounce(400),
                prefs.lucidChannels,
                prefs.settings.map { it.lucidTvEnabled }.distinctUntilChanged(),
                prefs.settings.map { it.lucidAutoSeriesCatalogs }.distinctUntilChanged(),
                liveTvRepository.channels,
            ) { addons, custom, enabled, autoSeries, liveChannels ->
                LoadInput(addons, custom, enabled, autoSeries, liveChannels)
            }.collectLatest { input ->
                if (!input.enabled) {
                    generation.incrementAndGet()
                    specs = emptyList()
                    pools.clear()
                    queueStart.clear()
                    scheduleAnchor.clear()
                    unavailable.clear()
                    _errors.value = emptyMap()
                    publish()
                    return@collectLatest
                }
                load(input)
            }
        }
        // The guide window moves with the clock. The schedule itself is deterministic; only the
        // window needs extending, so half-hourly is plenty even for 24/7 queues.
        scope.launch {
            while (true) {
                delay(30 * 60_000L)
                publish()
            }
        }
    }

    /** What a channel should play at [atMs]. Null when the channel or its pool isn't ready. */
    fun tune(
        channelKey: String,
        atMs: Long = System.currentTimeMillis(),
    ): LucidTune? {
        val spec = specs.firstOrNull { it.channelKey == channelKey } ?: return null
        val pool = availablePool(spec, pools[spec.id].orEmpty())
        if (pool.isEmpty()) return null
        if (spec.kind == LucidChannelKind.ADDON_LIVE) {
            val item = pool.firstOrNull() ?: return null
            return LucidTune(
                channelKey = spec.channelKey,
                slotNumber = atMs,
                slotStartMs = atMs,
                slotEndMs = atMs + LIVE_BLOCK_MS,
                item = item,
                startOffsetMs = 0L,
                live = true,
            )
        }
        return tuneQueue(spec, pool, atMs)
    }

    /**
     * Every VOD channel walks a queue of real runtimes from a stable anchor. What the guide says
     * is on now is therefore exactly the item (and join offset) that tuning starts.
     */
    private fun tuneQueue(spec: LucidChannelSpec, pool: List<LucidItem>, atMs: Long): LucidTune? {
        val ordered = orderedPool(spec, pool)
        val durations = durations(spec, ordered)
        val slot = LucidSchedule.queueAt(atMs, anchor(spec), durations) ?: return null
        val item = ordered[slot.itemIndex]
        // Exact join-in-progress: the guide's progress and the stream's position agree, even
        // when tuning in during the last minute of a programme.
        val start = if (item.type == "live") 0L else (atMs - slot.startMs).coerceAtLeast(0L)
        return LucidTune(
            channelKey = spec.channelKey,
            slotNumber = slot.startMs,
            slotStartMs = slot.startMs,
            slotEndMs = slot.endMs,
            item = item,
            startOffsetMs = start,
            relay = item.type == "live",
        )
    }

    private suspend fun load(input: LoadInput) {
        val run = generation.incrementAndGet()
        val custom = input.custom.filter { it.enabled }
        val customCatalogSources = custom.filter {
            (it.kind == LucidChannelKind.CATALOG_ROTATE || it.kind == LucidChannelKind.CATALOG_LOOP) &&
                it.addonBaseUrl != null && it.catalogId != null
        }.mapNotNull { spec ->
            spec.addonBaseUrl?.let { base -> catalogSourceKey(base, spec.catalogId.orEmpty(), spec.contentType) }
        }.toSet()
        val discovered = discover(input.addons, customCatalogSources, input.autoSeries)
        val pluginChannels = runCatching {
            withTimeoutOrNull(12_000L) { pluginBridge.extensions() }.orEmpty()
        }.getOrDefault(emptyList()).map { extension ->
            LucidChannelSpec(
                id = "plugin-${extension.id}",
                name = extension.name,
                catalogName = "CloudStream",
                addonName = "CloudStream",
                addonBaseUrl = "",
                addonId = extension.id,
                kind = LucidChannelKind.PLUGIN_ROTATE,
                group = "CloudStream",
                pluginScraperId = extension.id,
                pluginScraperName = extension.name,
                auto = true,
            )
        }
        val allSpecs = discovered.map { it.spec } + custom.map { it.toRuntime() } + pluginChannels
        specs = allSpecs
        Log.d(TAG, "load: ${allSpecs.size} channel(s), ${allSpecs.count { it.auto }} automatic, autoSeries=${input.autoSeries}")
        val liveIds = allSpecs.mapTo(HashSet()) { it.id }
        val previousSources = HashMap(poolSources)
        pools.keys.retainAll(liveIds)
        poolSources.keys.retainAll(liveIds)
        allSpecs.forEach { poolSources[it.id] = sourceKey(it) }
        // Cached pools make the guide appear immediately; the network catch-up below only
        // fetches what is missing, stale or changed.
        publish()

        val permits = Semaphore(POOL_CONCURRENCY)
        coroutineScope {
            allSpecs.map { spec ->
                async {
                    permits.withPermit {
                        val source = sourceKey(spec)
                        val stale = cacheSavedAtMs > 0L &&
                            System.currentTimeMillis() - cacheSavedAtMs > POOL_CACHE_TTL_MS
                        val sourceChanged = previousSources[spec.id] != null &&
                            previousSources[spec.id] != source
                        val needsLoad = spec.kind == LucidChannelKind.RELAY_LOOP ||
                            !pools.containsKey(spec.id) ||
                            sourceChanged ||
                            stale
                        if (!needsLoad) return@withPermit
                        if (sourceChanged) {
                            queueStart.remove(spec.id)
                            scheduleAnchor.remove(spec.id)
                            unavailable.keys.removeIf { it.startsWith("${spec.id}|") }
                        }
                        val hadPool = pools.containsKey(spec.id)
                        val items = runCatching {
                            when (spec.kind) {
                                LucidChannelKind.CATALOG_ROTATE,
                                LucidChannelKind.CATALOG_LOOP -> loadCatalogPool(spec, input.addons)
                                LucidChannelKind.SERIES_LOOP -> loadSeriesPool(spec)
                                LucidChannelKind.RELAY_LOOP -> buildRelayPool(spec, input.liveChannels)
                                LucidChannelKind.MANUAL_LOOP -> buildManualPool(spec)
                                LucidChannelKind.ADDON_LIVE -> buildManualPool(spec)
                                LucidChannelKind.PLUGIN_ROTATE -> loadPluginPool(spec)
                            }
                        }.onFailure {
                            Log.w(TAG, "pool load failed for ${spec.name} (${spec.kind})", it)
                        }.getOrDefault(emptyList())
                        if (run != generation.get()) return@withPermit
                        if (items.isEmpty()) {
                            // A failed refresh never wipes a usable cached pool.
                            if (!hadPool) pools.remove(spec.id)
                        } else {
                            pools[spec.id] = items
                            scheduleSaveCache()
                        }
                        publish()
                    }
                }
            }.awaitAll()
        }
        _errors.value = allSpecs
            .filter { pools[it.id].isNullOrEmpty() }
            .associate { it.channelKey to emptyPoolMessage(it) }
    }

    /**
     * A CloudStream extension channel: its latest pages, flattened into a movie pool. Series
     * and live items are skipped for now; they need an episode/relay resolver of their own.
     */
    private suspend fun loadPluginPool(spec: LucidChannelSpec): List<LucidItem> {
        val scraperId = spec.pluginScraperId ?: return emptyList()
        val items = buildList {
            for (page in 1..3) {
                val pageItems = withTimeoutOrNull(60_000L) {
                    pluginBridge.latest(scraperId, page)
                }.orEmpty()
                if (pageItems.isEmpty()) break
                addAll(pageItems)
                if (pageItems.size < 20) break
            }
        }
        return items
            .filter { it.type == "movie" }
            .distinctBy { it.url }
            .take(60)
            .map { item ->
                LucidItem(
                    id = "plugin:$scraperId:${item.url}",
                    streamId = item.url,
                    type = "movie",
                    title = item.name,
                    poster = item.poster,
                    backdrop = null,
                    description = null,
                    year = item.year,
                    runtimeMs = item.runtimeMs?.takeIf { it > 0L } ?: DEFAULT_PLUGIN_RUNTIME_MS,
                    addonId = scraperId,
                    addonName = spec.pluginScraperName ?: spec.name,
                    addonBaseUrl = "",
                    pluginScraperId = scraperId,
                    pluginUrl = item.url,
                )
            }
    }

    /** Resolve one CloudStream item when its Lucid slot is tuned. */
    suspend fun resolvePluginLinks(item: LucidItem): List<LucidPluginLink> {
        val scraperId = item.pluginScraperId ?: return emptyList()
        val url = item.pluginUrl ?: return emptyList()
        return runCatching {
            withTimeoutOrNull(65_000L) { pluginBridge.links(scraperId, url) }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun emptyPoolMessage(spec: LucidChannelSpec): String = when (spec.kind) {
        LucidChannelKind.RELAY_LOOP -> "No channels were found in this relay group."
        LucidChannelKind.ADDON_LIVE -> "This live addon channel is no longer available."
        LucidChannelKind.PLUGIN_ROTATE -> "The CloudStream extension returned no playable movies."
        LucidChannelKind.MANUAL_LOOP -> "This channel has no items."
        LucidChannelKind.SERIES_LOOP -> "The series returned no episodes."
        LucidChannelKind.CATALOG_ROTATE, LucidChannelKind.CATALOG_LOOP -> when {
            spec.addonBaseUrl.isBlank() -> "The addon behind this channel is missing."
            else -> "The catalog returned no items."
        }
    }

    private fun sourceKey(spec: LucidChannelSpec): String = when (spec.kind) {
        LucidChannelKind.CATALOG_ROTATE, LucidChannelKind.CATALOG_LOOP ->
            "catalog|${spec.addonBaseUrl}|${spec.catalogId}|${spec.contentType}"
        LucidChannelKind.SERIES_LOOP -> "series|${spec.addonBaseUrl}|${spec.seriesId}"
        LucidChannelKind.MANUAL_LOOP ->
            "manual|" + spec.manualItems.joinToString(",") { it.id }.hashCode()
        LucidChannelKind.RELAY_LOOP ->
            "relay|${spec.relayGroupId}|${spec.relayKeys.joinToString(",")}"
        LucidChannelKind.ADDON_LIVE ->
            "live|${spec.manualItems.firstOrNull()?.id}|${spec.manualItems.firstOrNull()?.type}"
        LucidChannelKind.PLUGIN_ROTATE -> "plugin|${spec.pluginScraperId}"
    }

    private fun restorePoolCache() {
        val cache = runCatching {
            if (!cacheFile.exists()) return
            json.decodeFromString<PoolCache>(cacheFile.readText())
        }.getOrNull() ?: return
        if (cache.version < CACHE_VERSION) {
            Log.d(TAG, "pool cache is from an older Lucid build; reloading pools")
            return
        }
        if (cache.savedAtMs <= 0L || cache.pools.isEmpty()) return
        pools.clear()
        pools.putAll(cache.pools)
        poolSources.clear()
        poolSources.putAll(cache.sources)
        cacheSavedAtMs = cache.savedAtMs
    }

    private fun scheduleSaveCache() {
        saveCacheJob?.cancel()
        saveCacheJob = scope.launch {
            delay(2_000L)
            savePoolCache()
        }
    }

    private fun savePoolCache() {
        val relayIds = specs.filter { it.kind == LucidChannelKind.RELAY_LOOP }.mapTo(HashSet()) { it.id }
        val cache = PoolCache(
            version = CACHE_VERSION,
            savedAtMs = System.currentTimeMillis(),
            pools = pools.filterKeys { it !in relayIds },
            sources = poolSources.filterKeys { it !in relayIds },
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json.encodeToString(cache))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
            cacheSavedAtMs = cache.savedAtMs
        }
    }

    @Serializable
    private data class PoolCache(
        /** Bumped when the pool contents change shape (series are now expanded into episodes). */
        val version: Int = 0,
        val savedAtMs: Long,
        val pools: Map<String, List<LucidItem>>,
        val sources: Map<String, String>,
    )

    private fun discover(
        addons: List<Addon>,
        excludedSources: Set<String>,
        includeSeries: Boolean,
    ): List<Discovered> {
        val wanted = if (includeSeries) setOf("movie", "series") else setOf("movie")
        return addons.asSequence()
            .filter { it.enabled }
            .flatMap { addon ->
                addon.catalogs.asSequence()
                    .filter {
                        it.apiType in wanted &&
                            !it.isSearchOnlyCatalog() &&
                            it.extra.none { extra -> extra.isRequired } &&
                            catalogSourceKey(addon.baseUrl, it.id, it.apiType) !in excludedSources
                    }
                    .map { catalog -> addon to catalog }
            }
            .distinctBy { (addon, catalog) -> "${addon.baseUrl}|${addon.id}|${catalog.apiType}|${catalog.id}" }
            .take(MAX_AUTO_CHANNELS)
            .map { (addon, catalog) ->
                val id = "vc_" + LucidSchedule.stableHash(
                    "${addon.baseUrl}|${addon.id}|${catalog.apiType}|${catalog.id}"
                ).toString(16)
                val series = catalog.apiType == "series"
                Discovered(
                    spec = LucidChannelSpec(
                        id = id,
                        // Both Cinemeta movie and series catalogs are called "Popular"; keep the
                        // series one distinguishable in the guide.
                        name = if (series) {
                            "${addon.displayName} · ${catalog.name} · Series"
                        } else {
                            "${addon.displayName} · ${catalog.name}"
                        },
                        catalogName = catalog.name,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        catalogId = catalog.id,
                        contentType = catalog.apiType,
                        slotMinutes = if (series) 45 else 90,
                        // The pool is interleaved one episode per series, so this plays a
                        // sampler of the catalog's shows instead of shuffling episode numbers.
                        order = if (series) LucidOrder.SEQUENTIAL else LucidOrder.SHUFFLE,
                        auto = true,
                    ),
                    addon = addon,
                    catalog = catalog,
                )
            }
            .distinctBy { it.spec.id }
            .toList()
    }

    private fun LucidStoredSpec.toRuntime(): LucidChannelSpec = LucidChannelSpec(
        id = id,
        name = name,
        catalogName = catalogName ?: seriesTitle ?: name,
        addonName = addonName.orEmpty(),
        addonBaseUrl = addonBaseUrl.orEmpty(),
        addonId = addonId.orEmpty(),
        catalogId = catalogId.orEmpty(),
        contentType = contentType.ifBlank { "movie" },
        kind = kind,
        group = group.ifBlank { GROUP_NAME },
        number = number,
        poster = poster,
        slotMinutes = slotMinutes.coerceAtLeast(1),
        maxStartPercent = maxStartPercent,
        jitter = jitter,
        order = order,
        anchorMs = anchorMs.takeIf { it > 0L } ?: createdAtMs,
        seriesId = seriesId,
        seriesTitle = seriesTitle,
        relayGroupId = relayGroupId,
        relayKeys = relayKeys,
        relaySegmentMinutes = relaySegmentMinutes.coerceAtLeast(1),
        manualItems = manualItems,
        auto = false,
    )

    private suspend fun loadCatalogPool(
        spec: LucidChannelSpec,
        addons: List<Addon>,
    ): List<LucidItem> {
        val catalog = findCatalog(addons, spec)
        val supportsSkip = catalog?.supportsExtra("skip") == true
        val skipStep = catalog?.skipStep() ?: 100
        val extraArgs = catalog?.extra.orEmpty()
            .filterNot { it.name.equals("skip", true) || it.name.equals("search", true) }
            .mapNotNull { extra ->
                extra.defaultValue?.takeIf { it.isNotBlank() }?.let { extra.name to it }
            }
            .toMap()

        val items = ArrayList<MetaPreview>()
        // A series catalog is expanded into episodes; there is no point fetching hundreds of
        // series when only the first few fill the pool.
        val fetchLimit = if (spec.contentType == "series") MAX_SERIES_TO_EXPAND else MAX_POOL_ITEMS
        var skip = 0
        var pages = 0
        while (items.size < fetchLimit && pages < MAX_CATALOG_PAGES) {
            val result = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                runCatching {
                    catalogRepository.getCatalog(
                        addonBaseUrl = spec.addonBaseUrl,
                        addonId = spec.addonId,
                        addonName = spec.addonName,
                        catalogId = spec.catalogId,
                        catalogName = spec.catalogName,
                        type = spec.contentType,
                        skip = skip,
                        skipStep = skipStep,
                        extraArgs = extraArgs,
                        supportsSkip = supportsSkip,
                        posterScreen = CustomPosterScreen.HOME,
                    ).first { it !is NetworkResult.Loading }
                }.onFailure {
                    Log.w(TAG, "catalog page failed for ${spec.name} skip=$skip", it)
                }.getOrNull()
            }
            val row = (result as? NetworkResult.Success)?.data ?: break
            if (row.items.isEmpty()) break
            items += row.items
            val next = row.nextSkip
            if (!row.hasMore || next <= skip || next <= 0) break
            skip = next
            pages++
        }
        Log.d(
            TAG,
            "catalog ${spec.name}: ${items.size} item(s) in ${pages + 1} page(s), " +
                "supportsSkip=$supportsSkip, type=${spec.contentType}",
        )

        val filtered = items.asSequence()
            .filter { it.apiType == spec.contentType }
            .filterNot { item -> item.genres.any { genre -> genre.equals("Adult", ignoreCase = true) } }
            .distinctBy { it.id }
            .toList()

        val out = ArrayList<LucidItem>()
        filtered.filter { it.apiType != "series" }
            .forEach { item -> item.toLucidItem(spec)?.let(out::add) }

        // A catalog can be a list of series (the auto series option) or a mixed list. Expand
        // those into episodes: the player can only search streams for a series with a season and
        // episode, and every queue item needs a real runtime.
        val seriesItems = filtered.filter { it.apiType == "series" }.take(MAX_SERIES_TO_EXPAND)
        if (seriesItems.isNotEmpty()) {
            val permits = Semaphore(META_CONCURRENCY)
            val expanded = coroutineScope {
                seriesItems.map { item ->
                    async { permits.withPermit { loadSeriesEpisodes(spec, item) } }
                }.awaitAll()
            }
            // Interleave one episode from each series per round, so a 120-item pool contains
            // more than the first two or three shows.
            val rounds = expanded.maxOfOrNull { it.size } ?: 0
            for (round in 0 until rounds) {
                expanded.forEach { episodes ->
                    episodes.getOrNull(round)?.let(out::add)
                }
            }
        }

        return enrichMissingRuntimes(spec, out)
            .filter { it.type != "series" || (it.season != null && it.episode != null) }
            .distinctBy { it.id }
            .take(MAX_POOL_ITEMS)
    }

    private suspend fun loadSeriesPool(spec: LucidChannelSpec): List<LucidItem> {
        val seriesId = spec.seriesId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val meta = fetchMeta("series", seriesId, spec.addonBaseUrl) ?: return emptyList()
        return metaEpisodes(spec, meta, spec.poster)
            .take(MAX_POOL_ITEMS)
    }

    private suspend fun loadSeriesEpisodes(
        spec: LucidChannelSpec,
        preview: MetaPreview,
    ): List<LucidItem> {
        val meta = fetchMeta("series", preview.id, spec.addonBaseUrl) ?: return emptyList()
        return metaEpisodes(
            spec = spec,
            meta = meta,
            posterOverride = preview.poster ?: preview.landscapePoster ?: preview.background,
        ).take(MAX_EPISODES_PER_SERIES)
    }

    private fun metaEpisodes(
        spec: LucidChannelSpec,
        meta: com.nuvio.tv.domain.model.Meta,
        posterOverride: String?,
    ): List<LucidItem> {
        val fallback = spec.slotMinutes.coerceAtLeast(1) * 60_000L
        return meta.watchableEpisodes()
            .filter { it.season != null && it.episode != null }
            .map { video ->
                val episodeId = video.id.takeIf { it.isNotBlank() }
                    ?: "${meta.id}:${video.season}:${video.episode}"
                LucidItem(
                    // The stream search id is the episode id ("tt123:1:1"), not the series id:
                    // addons build /stream/series/tt123:1:1.json from it.
                    id = episodeId,
                    streamId = episodeId,
                    type = "series",
                    title = meta.name,
                    poster = video.thumbnail ?: posterOverride ?: spec.poster ?: meta.poster,
                    backdrop = meta.backdropUrl,
                    description = video.overview ?: meta.description,
                    year = parseYear(meta.releaseInfo),
                    runtimeMs = video.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L)
                        ?: parseRuntimeMs(meta.runtime)
                        ?: fallback,
                    addonId = spec.addonId,
                    addonName = spec.addonName,
                    addonBaseUrl = spec.addonBaseUrl,
                    genres = meta.genres,
                    season = video.season,
                    episode = video.episode,
                    episodeTitle = video.title,
                )
            }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend fun fetchMeta(
        type: String,
        id: String,
        addonBaseUrl: String,
    ): com.nuvio.tv.domain.model.Meta? = metaPermits.withPermit {
        val result = withTimeoutOrNull(META_TIMEOUT_MS) {
            runCatching {
                metaRepository.getMetaFromAllAddons(
                    type = type,
                    id = id,
                    sourceAddonBaseUrl = addonBaseUrl.takeIf { it.isNotBlank() },
                ).first { it !is NetworkResult.Loading }
            }.onFailure {
                Log.w(TAG, "meta lookup failed for $type/$id", it)
            }.getOrNull()
        } ?: return@withPermit null
        (result as? NetworkResult.Success)?.data
    }

    private suspend fun fetchRuntimeMs(spec: LucidChannelSpec, item: LucidItem): Long? {
        val meta = fetchMeta(
            type = item.type,
            id = item.id,
            addonBaseUrl = item.addonBaseUrl.ifBlank { spec.addonBaseUrl },
        ) ?: return null
        return parseRuntimeMs(meta.runtime)
    }

    /**
     * Catalog lists often carry a runtime, but not always. Fill the missing ones so the guide
     * does not fall back to a guessed slot for films whose length is knowable. Lookups are
     * bounded: a broken/slow addon must not hold the whole channel for minutes.
     */
    private suspend fun enrichMissingRuntimes(
        spec: LucidChannelSpec,
        items: List<LucidItem>,
    ): List<LucidItem> {
        val budget = AtomicInteger(MAX_RUNTIME_LOOKUPS)
        if (items.none { it.runtimeMs == null && it.type != "live" }) return items
        val permits = Semaphore(META_CONCURRENCY)
        return coroutineScope {
            items.map { item ->
                async {
                    if (item.runtimeMs != null || item.type == "live" || item.type == "series" ||
                        budget.getAndDecrement() <= 0
                    ) {
                        return@async item
                    }
                    val runtime = permits.withPermit { fetchRuntimeMs(spec, item) }
                    if (runtime != null) item.copy(runtimeMs = runtime) else item
                }
            }.awaitAll()
        }
    }

    private fun findCatalog(addons: List<Addon>, spec: LucidChannelSpec): CatalogDescriptor? {
        val addon = addons.firstOrNull { it.baseUrl == spec.addonBaseUrl }
            ?: addons.firstOrNull { spec.addonId.isNotBlank() && it.id == spec.addonId }
            ?: return null
        return addon.catalogs.firstOrNull {
            it.id == spec.catalogId && it.apiType == spec.contentType
        } ?: addon.catalogs.firstOrNull { it.id == spec.catalogId }
    }

    private fun buildRelayPool(spec: LucidChannelSpec, liveChannels: List<LiveChannel>): List<LucidItem> {
        val byKey = liveChannels.associateBy { it.key }
        val selected = when {
            spec.relayKeys.isNotEmpty() -> spec.relayKeys.mapNotNull { byKey[it] }
            spec.relayGroupId != null -> liveChannels.filter { it.groupId == spec.relayGroupId }
            else -> emptyList()
        }
        val segmentMs = spec.relaySegmentMinutes.coerceAtLeast(1) * 60_000L
        return selected.distinctBy { it.key }.map { channel ->
            LucidItem(
                id = channel.key,
                type = "live",
                title = channel.name,
                poster = channel.logo,
                backdrop = null,
                description = "Live relay · ${channel.group}",
                year = null,
                runtimeMs = segmentMs,
                addonId = "",
                addonName = channel.sourceName,
                addonBaseUrl = "",
                streamUrl = channel.url,
                headers = channel.headers,
                drm = channel.drm,
            )
        }
    }

    private fun buildManualPool(spec: LucidChannelSpec): List<LucidItem> =
        spec.manualItems.map { item ->
            LucidItem(
                id = item.id,
                streamId = item.id,
                type = item.type,
                title = item.title,
                poster = item.poster,
                backdrop = item.backdrop,
                description = item.description,
                year = item.year,
                runtimeMs = item.runtimeMs,
                addonId = item.addonId,
                addonName = item.addonName,
                addonBaseUrl = item.addonBaseUrl,
                season = item.season,
                episode = item.episode,
                episodeTitle = item.episodeTitle,
            )
        }

    private fun applyOrder(spec: LucidChannelSpec, pool: List<LucidItem>): List<LucidItem> =
        when (spec.order) {
            LucidOrder.SHUFFLE -> pool.sortedBy { LucidSchedule.stableHash("${spec.id}:${it.id}") }
            LucidOrder.RELEASE -> pool.sortedBy { it.year ?: Int.MAX_VALUE }
            LucidOrder.AIR -> pool.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
            LucidOrder.MANUAL, LucidOrder.SEQUENTIAL -> pool
        }

    private fun MetaPreview.toLucidItem(spec: LucidChannelSpec): LucidItem? {
        val videoId = id.takeIf { it.isNotBlank() } ?: return null
        val name = name.takeIf { it.isNotBlank() } ?: return null
        return LucidItem(
            id = videoId,
            streamId = videoId,
            type = apiType,
            title = name,
            poster = poster ?: landscapePoster ?: background,
            backdrop = background,
            description = description,
            year = parseYear(releaseInfo),
            runtimeMs = parseRuntimeMs(runtime),
            addonId = spec.addonId,
            addonName = spec.addonName,
            addonBaseUrl = spec.addonBaseUrl,
            genres = genres,
        )
    }

    /**
     * The current item has no playable stream: skip it and start the queue at the next item now.
     * The synthetic guide is regenerated from that point, so the guide and the player stay in
     * step instead of quietly drifting apart.
     *
     * @return true when another item is available and the channel can be tuned again.
     */
    fun markUnplayable(channelKey: String, itemId: String): Boolean {
        val spec = specs.firstOrNull { it.channelKey == channelKey } ?: return false
        val pool = availablePool(spec, pools[spec.id].orEmpty())
        if (pool.size <= 1) return false
        val ordered = orderedPool(spec, pool)
        val failedIndex = ordered.indexOfFirst { it.id == itemId }
        val next = ordered.drop((failedIndex + 1).coerceAtLeast(0))
            .firstOrNull { it.id != itemId }
            ?: ordered.firstOrNull { it.id != itemId }
            ?: return false
        unavailable["${spec.id}|$itemId"] = System.currentTimeMillis() + UNPLAYABLE_TTL_MS
        moveQueueStart(spec, next.id)
        _errors.value = _errors.value - channelKey
        Log.d(TAG, "skip ${spec.name}: no stream for ${itemId.take(80)}; next ${next.id.take(80)}")
        publish()
        return true
    }

    /**
     * A media item ended early (a shorter file than its metadata said, or a dead source that
     * played nothing). Move the queue's clock to the item after [itemId] and start it now, so
     * the guide immediately says the next programme is on instead of freezing on a finished
     * item until the old boundary.
     */
    fun advanceAfter(channelKey: String, itemId: String): Boolean {
        val spec = specs.firstOrNull { it.channelKey == channelKey } ?: return false
        val pool = availablePool(spec, pools[spec.id].orEmpty())
        if (pool.size <= 1) return false
        val ordered = orderedPool(spec, pool)
        val index = ordered.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        val next = ordered[(index + 1) % ordered.size]
        if (next.id == itemId) return false
        moveQueueStart(spec, next.id)
        Log.d(TAG, "advance ${spec.name}: ${itemId.take(60)} ended early; next ${next.id.take(60)}")
        publish()
        return true
    }

    private fun moveQueueStart(spec: LucidChannelSpec, itemId: String) {
        queueStart[spec.id] = itemId
        scheduleAnchor[spec.id] = System.currentTimeMillis()
    }

    fun reportError(channelKey: String, message: String) {
        _errors.value = _errors.value + (channelKey to message)
        Log.w(TAG, "$channelKey: $message")
    }

    fun clearError(channelKey: String) {
        if (channelKey in _errors.value) _errors.value = _errors.value - channelKey
    }

    private fun publish() {
        val currentSpecs = specs
        _channels.value = currentSpecs.mapIndexed { index, spec ->
            LiveChannel(
                key = spec.channelKey,
                sourceId = SOURCE_ID,
                sourceName = GROUP_NAME,
                name = spec.name,
                tvgId = null,
                tvgName = null,
                logo = spec.poster ?: availablePool(spec, pools[spec.id].orEmpty()).firstOrNull()?.poster,
                groupId = spec.groupId,
                group = spec.group,
                number = spec.number ?: (FIRST_CHANNEL_NUMBER + index),
                url = spec.channelKey,
            )
        }
        publishPrograms(currentSpecs)
    }

    private fun publishPrograms(currentSpecs: List<LucidChannelSpec>) {
        val now = System.currentTimeMillis()
        val from = now - EPG_PAST_MS
        val to = now + EPG_FUTURE_MS
        val out = HashMap<String, List<EpgProgram>>()
        currentSpecs.forEach { spec ->
            val pool = availablePool(spec, pools[spec.id].orEmpty())
            if (pool.isEmpty()) return@forEach
            if (spec.kind == LucidChannelKind.ADDON_LIVE) {
                val item = pool.firstOrNull() ?: return@forEach
                out[spec.channelKey] = listOf(
                    EpgProgram(
                        startMs = from,
                        stopMs = to,
                        title = item.title,
                        description = item.description,
                        category = "Lucid TV · Live addon",
                        icon = item.poster,
                        year = item.year,
                    ),
                )
                return@forEach
            }
            val ordered = orderedPool(spec, pool)
            val durations = durations(spec, ordered)
            val slots = LucidSchedule.queueSchedule(anchor(spec), durations, from, to)
            out[spec.channelKey] = slots.map { slot ->
                ordered[slot.itemIndex].toProgram(spec, slot.startMs, slot.endMs)
            }
        }
        _programs.value = out
    }

    /**
     * The chosen order, then rotated so a failed item's successor starts the queue after a skip.
     * This keeps the guide's now/next on exactly what the player will tune.
     */
    private fun orderedPool(spec: LucidChannelSpec, pool: List<LucidItem>): List<LucidItem> {
        val ordered = applyOrder(spec, pool)
        val startId = queueStart[spec.id] ?: return ordered
        val index = ordered.indexOfFirst { it.id == startId }
        return if (index <= 0) ordered else ordered.drop(index) + ordered.take(index)
    }

    private fun durations(spec: LucidChannelSpec, pool: List<LucidItem>): List<Long> {
        val fallback = when (spec.kind) {
            LucidChannelKind.RELAY_LOOP -> spec.relaySegmentMinutes.coerceAtLeast(1) * 60_000L
            else -> spec.slotMinutes.coerceAtLeast(1) * 60_000L
        }
        return pool.map { it.runtimeMs?.takeIf { d -> d > 0L } ?: fallback }
    }

    private fun anchor(spec: LucidChannelSpec): Long =
        scheduleAnchor[spec.id] ?: spec.anchorMs.takeIf { it > 0L } ?: 0L

    /** Items skipped after "no playable stream", until the retry window expires. */
    private fun availablePool(spec: LucidChannelSpec, pool: List<LucidItem>): List<LucidItem> {
        if (pool.isEmpty()) return pool
        val now = System.currentTimeMillis()
        val keyPrefix = "${spec.id}|"
        unavailable.keys.removeIf { key ->
            key.startsWith(keyPrefix) && (unavailable[key] ?: 0L) <= now
        }
        return pool.filter { item ->
            val until = unavailable["${spec.id}|${item.id}"]
            until == null || until <= now
        }
    }

    private fun catalogSourceKey(baseUrl: String, catalogId: String, type: String): String =
        "$baseUrl|$catalogId|$type"

    private data class LoadInput(
        val addons: List<Addon>,
        val custom: List<LucidStoredSpec>,
        val enabled: Boolean,
        val autoSeries: Boolean,
        val liveChannels: List<LiveChannel>,
    )

    private data class Discovered(
        val spec: LucidChannelSpec,
        val addon: Addon,
        val catalog: CatalogDescriptor,
    )

    companion object {
        const val TAG = "LucidTv"
        const val SOURCE_ID = "lucid"
        const val GROUP_NAME = "Lucid TV"
        const val FIRST_CHANNEL_NUMBER = 9000
        const val MAX_AUTO_CHANNELS = 60
        const val MAX_POOL_ITEMS = 120
        const val POOL_CONCURRENCY = 4
        const val CATALOG_TIMEOUT_MS = 20_000L
        const val EPG_PAST_MS = 2 * 60 * 60_000L
        const val EPG_FUTURE_MS = 3 * 24 * 60 * 60_000L
        const val MAX_PROGRAMS_PER_CHANNEL = 2000
        const val POOL_CACHE_TTL_MS = 6 * 60 * 60_000L
        const val CACHE_VERSION = 2
        const val LIVE_BLOCK_MS = 12 * 60 * 60_000L
        const val MAX_CATALOG_PAGES = 6
        const val MAX_SERIES_TO_EXPAND = 8
        const val MAX_EPISODES_PER_SERIES = 40
        const val MAX_RUNTIME_LOOKUPS = 24
        const val META_CONCURRENCY = 3
        const val META_TIMEOUT_MS = 12_000L
        const val UNPLAYABLE_TTL_MS = 60 * 60_000L
        const val DEFAULT_PLUGIN_RUNTIME_MS = 90L * 60_000L

        private val HOURS = Regex("(\\d+)\\s*h", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("(\\d+)\\s*m", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("(\\d{4})")

        fun parseYear(releaseInfo: String?): Int? =
            releaseInfo?.let { YEAR.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        fun parseRuntimeMs(runtime: String?): Long? {
            if (runtime.isNullOrBlank()) return null
            val hours = HOURS.find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val minutes = MINUTES.find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val total = hours * 60 + minutes
            return if (total > 0) total * 60_000L else null
        }
    }
}
