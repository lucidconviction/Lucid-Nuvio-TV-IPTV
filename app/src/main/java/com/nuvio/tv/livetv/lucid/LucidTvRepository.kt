package com.nuvio.tv.livetv.lucid

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
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
 * Lucid TV: installed addon movie catalogs become always-on channels.
 *
 * A channel is a definition plus a lazy pool, never a stream URL. The schedule is deterministic,
 * so the guide and playback agree without a server. Streams are resolved only when a channel is
 * tuned, and playback joins the film part-way through like a broadcast already in progress.
 *
 * This is the first slice of the design in docs/stremio-tv-virtual-channels.md. It covers
 * ADDON_ROTATE movie channels, the synthetic guide and join-in-progress playback. Custom
 * channels, 24/7 LOOP marathons and relay channels are later phases.
 */
@Singleton
class LucidTvRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
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
                prefs.settings.map { it.lucidTvEnabled }.distinctUntilChanged(),
            ) { addons, enabled -> addons to enabled }
                .collectLatest { (addons, enabled) ->
                    if (!enabled) {
                        generation.incrementAndGet()
                        specs = emptyList()
                        pools.clear()
                        publish()
                        return@collectLatest
                    }
                    load(addons)
                }
        }
        // The guide window moves with the clock. The schedule itself is deterministic; only the
        // window needs to be extended, so half-hourly is plenty.
        scope.launch {
            while (true) {
                delay(30 * 60_000L)
                publish()
            }
        }
    }

    /** What a channel should play now. Null when the channel or its pool isn't ready. */
    fun tune(
        channelKey: String,
        nowMs: Long = System.currentTimeMillis(),
        slotNumber: Long? = null,
    ): LucidTune? {
        val spec = specs.firstOrNull { it.channelKey == channelKey } ?: return null
        val pool = pools[spec.id].orEmpty()
        if (pool.isEmpty()) return null
        val slotMs = spec.slotMinutes.coerceAtLeast(1) * 60_000L
        val slot = if (slotNumber != null) {
            LucidSchedule.slotForNumber(slotNumber, spec.id, slotMs)
        } else {
            LucidSchedule.slotAt(nowMs, spec.id, slotMs)
        }
        val item = pool[LucidSchedule.itemIndexFor(spec.id, slot.number, pool.size)]
        val rawStart = if (slotNumber != null) {
            0L
        } else {
            LucidSchedule.joinOffsetMs(
                channelId = spec.id,
                slot = slot,
                nowMs = nowMs,
                maxStartPercent = spec.maxStartPercent,
                jitter = spec.jitter,
            )
        }
        // Don't join so late that there is nothing left to watch.
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

    private suspend fun load(addons: List<Addon>) {
        val run = generation.incrementAndGet()
        val discovered = discover(addons)
        specs = discovered.map { it.spec }
        val liveIds = specs.mapTo(HashSet()) { it.id }
        pools.keys.retainAll(liveIds)
        publish()

        val permits = Semaphore(POOL_CONCURRENCY)
        coroutineScope {
            discovered.map { channel ->
                async {
                    permits.withPermit {
                        val items = runCatching { loadPool(channel.addon, channel.catalog) }.getOrDefault(emptyList())
                        if (run != generation.get()) return@withPermit
                        if (items.isEmpty()) pools.remove(channel.spec.id) else pools[channel.spec.id] = items
                        publish()
                    }
                }
            }.awaitAll()
        }
    }

    private fun discover(addons: List<Addon>): List<Discovered> =
        addons.asSequence()
            .filter { it.enabled }
            .flatMap { addon ->
                addon.catalogs.asSequence()
                    .filter {
                        it.apiType == "movie" &&
                            !it.isSearchOnlyCatalog() &&
                            it.extra.none { extra -> extra.isRequired }
                    }
                    .map { catalog -> addon to catalog }
            }
            .distinctBy { (addon, catalog) -> "${addon.baseUrl}|${addon.id}|${catalog.apiType}|${catalog.id}" }
            .take(MAX_CHANNELS)
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
                        contentType = catalog.apiType,
                    ),
                    addon = addon,
                    catalog = catalog,
                )
            }
            .distinctBy { it.spec.id }
            .toList()

    private suspend fun loadPool(addon: Addon, catalog: CatalogDescriptor): List<LucidItem> {
        val result = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
            runCatching {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    posterScreen = CustomPosterScreen.HOME,
                ).first { it !is NetworkResult.Loading }
            }.getOrNull()
        } ?: return emptyList()
        val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
        return items.asSequence()
            .filter { it.apiType == "movie" }
            .filterNot { item -> item.genres.any { genre -> genre.equals("Adult", ignoreCase = true) } }
            .distinctBy { it.id }
            .take(MAX_POOL_ITEMS)
            .mapNotNull { it.toLucidItem(addon) }
            .toList()
    }

    private fun MetaPreview.toLucidItem(addon: Addon): LucidItem? {
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
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
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
                logo = pools[spec.id]?.firstOrNull()?.poster,
                groupId = GROUP_ID,
                group = GROUP_NAME,
                number = FIRST_CHANNEL_NUMBER + index,
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
        }
        _programs.value = out
    }

    private data class Discovered(
        val spec: LucidChannelSpec,
        val addon: Addon,
        val catalog: CatalogDescriptor,
    )

    private companion object {
        const val SOURCE_ID = "lucid"
        const val GROUP_ID = "virtual:lucid"
        const val GROUP_NAME = "Lucid TV"
        const val FIRST_CHANNEL_NUMBER = 9000
        const val MAX_CHANNELS = 60
        const val MAX_POOL_ITEMS = 120
        const val POOL_CONCURRENCY = 4
        const val CATALOG_TIMEOUT_MS = 20_000L
        const val EPG_PAST_MS = 2 * 60 * 60_000L
        const val EPG_FUTURE_MS = 3 * 24 * 60 * 60_000L
        const val MAX_PROGRAMS_PER_CHANNEL = 120

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
