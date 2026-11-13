package com.nuvio.tv.livetv.lucid

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lucid TV: addon catalogs, series, hand-picked films and live-channel relays become always-on
 * channels.
 *
 * A channel is a definition plus a lazy pool, never a stream URL. CATALOG_ROTATE uses fixed
 * slots, joined in progress, like Debrify's Stremio TV. The LOOP kinds use a continuous queue
 * with real media runtimes, so a 24/7 marathon advances exactly when each film or episode ends.
 *
 * Custom channels are saved by the studio; catalog channels are discovered from the installed
 * addons. Both are merged here, and streams are resolved only when a channel is tuned.
 */
@Singleton
class LucidTvRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: MetaRepository,
    private val liveTvRepository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    @Volatile private var specs: List<LucidChannelSpec> = emptyList()
    private val pools = ConcurrentHashMap<String, List<LucidItem>>()
    private val generation = AtomicInteger()
    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        scope.launch {
            combine(
                addonRepository.getInstalledAddons().debounce(400),
                prefs.lucidChannels,
                prefs.settings.map { it.lucidTvEnabled }.distinctUntilChanged(),
                liveTvRepository.channels,
            ) { addons, custom, enabled, liveChannels ->
                LoadInput(addons, custom, enabled, liveChannels)
            }.collectLatest { input ->
                if (!input.enabled) {
                    generation.incrementAndGet()
                    specs = emptyList()
                    pools.clear()
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
        slotNumber: Long? = null,
    ): LucidTune? {
        val spec = specs.firstOrNull { it.channelKey == channelKey } ?: return null
        val pool = pools[spec.id].orEmpty()
        if (pool.isEmpty()) return null
        return if (spec.isLoop) tuneLoop(spec, pool, atMs) else tuneRotate(spec, pool, atMs, slotNumber)
    }

    private fun tuneRotate(
        spec: LucidChannelSpec,
        pool: List<LucidItem>,
        atMs: Long,
        slotNumber: Long?,
    ): LucidTune? {
        val slotMs = spec.slotMinutes.coerceAtLeast(1) * 60_000L
        val slot = if (slotNumber != null) {
            LucidSchedule.slotForNumber(slotNumber, spec.id, slotMs)
        } else {
            LucidSchedule.slotAt(atMs, spec.id, slotMs)
        }
        val item = pool[LucidSchedule.itemIndexFor(spec.id, slot.number, pool.size)]
        val rawStart = if (slotNumber != null) {
            0L
        } else {
            LucidSchedule.joinOffsetMs(
                channelId = spec.id,
                slot = slot,
                nowMs = atMs,
                maxStartPercent = spec.maxStartPercent,
                jitter = spec.jitter,
            )
        }
        val latestUsefulStart = item.runtimeMs?.let { (it - 30_000L).coerceAtLeast(0L) }
        val start = if (latestUsefulStart != null) rawStart.coerceAtMost(latestUsefulStart) else rawStart
        return LucidTune(
            channelKey = spec.channelKey,
            slotNumber = slot.number,
            slotStartMs = slot.startMs,
            slotEndMs = slot.endMs,
            item = item,
            startOffsetMs = start,
        )
    }

    private fun tuneLoop(spec: LucidChannelSpec, pool: List<LucidItem>, atMs: Long): LucidTune? {
        val ordered = orderedPool(spec, pool)
        val durations = durations(spec, ordered)
        val slot = LucidSchedule.queueAt(atMs, anchor(spec), durations) ?: return null
        val item = ordered[slot.itemIndex]
        val rawStart = (atMs - slot.startMs).coerceAtLeast(0L)
        val start = if (item.type == "live") {
            0L
        } else {
            val latestUsefulStart = item.runtimeMs?.let { (it - 30_000L).coerceAtLeast(0L) }
            if (latestUsefulStart != null) rawStart.coerceAtMost(latestUsefulStart) else rawStart
        }
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
        val discovered = discover(input.addons, customCatalogSources)
        val allSpecs = discovered.map { it.spec } + custom.map { it.toRuntime() }
        specs = allSpecs
        val liveIds = allSpecs.mapTo(HashSet()) { it.id }
        pools.keys.retainAll(liveIds)
        publish()

        val permits = Semaphore(POOL_CONCURRENCY)
        coroutineScope {
            allSpecs.map { spec ->
                async {
                    permits.withPermit {
                        // Catalog/series pools are expensive; keep them while the source is the
                        // same. Saving a spec or another addon change only loads what's new.
                        val needsLoad = spec.kind == LucidChannelKind.RELAY_LOOP || !pools.containsKey(spec.id)
                        if (!needsLoad) return@withPermit
                        val items = runCatching {
                            when (spec.kind) {
                                LucidChannelKind.CATALOG_ROTATE,
                                LucidChannelKind.CATALOG_LOOP -> loadCatalogPool(spec)
                                LucidChannelKind.SERIES_LOOP -> loadSeriesPool(spec)
                                LucidChannelKind.RELAY_LOOP -> buildRelayPool(spec, input.liveChannels)
                                LucidChannelKind.MANUAL_LOOP -> buildManualPool(spec)
                            }
                        }.getOrDefault(emptyList())
                        if (run != generation.get()) return@withPermit
                        if (items.isEmpty()) pools.remove(spec.id) else pools[spec.id] = items
                        publish()
                    }
                }
            }.awaitAll()
        }
    }

    private fun discover(addons: List<Addon>, excludedSources: Set<String>): List<Discovered> =
        addons.asSequence()
            .filter { it.enabled }
            .flatMap { addon ->
                addon.catalogs.asSequence()
                    .filter {
                        it.apiType == "movie" &&
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
                Discovered(
                    spec = LucidChannelSpec(
                        id = id,
                        name = "${addon.displayName} · ${catalog.name}",
                        catalogName = catalog.name,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        catalogId = catalog.id,
                        contentType = catalog.apiType,
                        auto = true,
                    ),
                    addon = addon,
                    catalog = catalog,
                )
            }
            .distinctBy { it.spec.id }
            .toList()

    private fun LucidStoredSpec.toRuntime(): LucidChannelSpec = LucidChannelSpec(
        id = id,
        name = name,
        catalogName = catalogName ?: seriesTitle ?: name,
        addonName = addonName.orEmpty(),
        addonBaseUrl = addonBaseUrl.orEmpty(),
        addonId = addonId.orEmpty(),
        catalogId = catalogId.orEmpty(),
        contentType = contentType,
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

    private suspend fun loadCatalogPool(spec: LucidChannelSpec): List<LucidItem> {
        val result = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
            runCatching {
                catalogRepository.getCatalog(
                    addonBaseUrl = spec.addonBaseUrl,
                    addonId = spec.addonId,
                    addonName = spec.addonName,
                    catalogId = spec.catalogId,
                    catalogName = spec.catalogName,
                    type = spec.contentType,
                    posterScreen = CustomPosterScreen.HOME,
                ).first { it !is NetworkResult.Loading }
            }.getOrNull()
        } ?: return emptyList()
        val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
        val pool = items.asSequence()
            .filter { it.apiType == spec.contentType }
            .filterNot { item -> item.genres.any { genre -> genre.equals("Adult", ignoreCase = true) } }
            .distinctBy { it.id }
            .take(MAX_POOL_ITEMS)
            .mapNotNull { it.toLucidItem(spec) }
            .toList()
        return pool
    }

    private suspend fun loadSeriesPool(spec: LucidChannelSpec): List<LucidItem> {
        val seriesId = spec.seriesId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val result = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
            runCatching {
                metaRepository.getMetaFromAllAddons(
                    type = "series",
                    id = seriesId,
                    sourceAddonBaseUrl = spec.addonBaseUrl.takeIf { it.isNotBlank() },
                ).first { it !is NetworkResult.Loading }
            }.getOrNull()
        } ?: return emptyList()
        val meta = (result as? NetworkResult.Success)?.data ?: return emptyList()
        val fallback = spec.slotMinutes.coerceAtLeast(1) * 60_000L
        val episodes = meta.watchableEpisodes()
            .filter { it.season != null && it.episode != null }
            .map { video ->
                LucidItem(
                    id = "${meta.id}:${video.season}:${video.episode}",
                    streamId = meta.id,
                    type = "series",
                    title = meta.name,
                    poster = video.thumbnail ?: spec.poster ?: meta.poster,
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
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
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
                logo = spec.poster ?: pools[spec.id]?.firstOrNull()?.poster,
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
            val pool = pools[spec.id].orEmpty()
            if (pool.isEmpty()) return@forEach
            if (!spec.isLoop) {
                val slotMs = spec.slotMinutes.coerceAtLeast(1) * 60_000L
                var slot = LucidSchedule.slotAt(from, spec.id, slotMs)
                val list = ArrayList<EpgProgram>()
                var guard = 0
                while (slot.startMs < to && guard++ < MAX_PROGRAMS_PER_CHANNEL) {
                    val index = LucidSchedule.itemIndexFor(spec.id, slot.number, pool.size)
                    list += pool[index].toProgram(spec, slot.startMs, slot.endMs)
                    slot = LucidSchedule.slotForNumber(slot.number + 1, spec.id, slotMs)
                }
                out[spec.channelKey] = list
            } else {
                val ordered = orderedPool(spec, pool)
                val durations = durations(spec, ordered)
                val slots = LucidSchedule.queueSchedule(anchor(spec), durations, from, to)
                out[spec.channelKey] = slots.map { slot ->
                    ordered[slot.itemIndex].toProgram(spec, slot.startMs, slot.endMs)
                }
            }
        }
        _programs.value = out
    }

    /** Catalog rotation uses the slot hash; 24/7 queues apply the chosen order at use time. */
    private fun orderedPool(spec: LucidChannelSpec, pool: List<LucidItem>): List<LucidItem> =
        if (spec.kind == LucidChannelKind.CATALOG_ROTATE) pool else applyOrder(spec, pool)

    private fun durations(spec: LucidChannelSpec, pool: List<LucidItem>): List<Long> {
        val fallback = when (spec.kind) {
            LucidChannelKind.RELAY_LOOP -> spec.relaySegmentMinutes.coerceAtLeast(1) * 60_000L
            LucidChannelKind.SERIES_LOOP -> spec.slotMinutes.coerceAtLeast(1) * 60_000L
            else -> spec.slotMinutes.coerceAtLeast(1) * 60_000L
        }
        return pool.map { it.runtimeMs?.takeIf { d -> d > 0L } ?: fallback }
    }

    private fun anchor(spec: LucidChannelSpec): Long = spec.anchorMs.takeIf { it > 0L } ?: 0L

    private fun catalogSourceKey(baseUrl: String, catalogId: String, type: String): String =
        "$baseUrl|$catalogId|$type"

    private data class LoadInput(
        val addons: List<Addon>,
        val custom: List<LucidStoredSpec>,
        val enabled: Boolean,
        val liveChannels: List<LiveChannel>,
    )

    private data class Discovered(
        val spec: LucidChannelSpec,
        val addon: Addon,
        val catalog: CatalogDescriptor,
    )

    private companion object {
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
