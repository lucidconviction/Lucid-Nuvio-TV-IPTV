package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.livetv.lucid.LucidPluginBridge
import com.nuvio.tv.livetv.lucid.LucidPluginDetail
import com.nuvio.tv.livetv.lucid.LucidPluginExtension
import com.nuvio.tv.livetv.lucid.LucidPluginItem
import com.nuvio.tv.livetv.lucid.LucidPluginLink
import com.nuvio.tv.livetv.lucid.LucidPluginPage
import com.nuvio.tv.livetv.lucid.LucidPluginTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CATALOG_TIMEOUT_MS = 25_000L
private const val LINKS_TIMEOUT_MS = 60_000L

/**
 * Lucid TV's CloudStream source. It only exists in the full flavor; the Play Store flavor
 * binds [com.nuvio.tv.livetv.lucid.NoOpLucidPluginBridge] instead.
 */
@Singleton
class CloudStreamLucidPluginBridge @Inject constructor(
    private val pluginManager: PluginManager,
    private val extensionLoader: ExternalExtensionLoader,
) : LucidPluginBridge {

    override suspend fun extensions(): List<LucidPluginExtension> = withContext(Dispatchers.IO) {
        runCatching {
            PluginRuntimeHooks.ensureCloudstreamInitialized()
            pluginManager.enabledScrapers.first()
                .filter { it.type == RepositoryType.EXTERNAL_DEX }
                .map { LucidPluginExtension(id = it.id, name = it.name, logo = it.logo) }
        }.getOrDefault(emptyList())
    }

    override suspend fun latest(scraperId: String, page: Int): List<LucidPluginItem> =
        withContext(Dispatchers.IO) {
            val api = loadApi(scraperId) ?: return@withContext emptyList()
            val home = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                runCatching { api.getMainPage(page, MainPageRequest("", "", false)) }.getOrNull()
            }
            val homeItems = home?.items.orEmpty().flatMap { it.list }
            // Search-only extensions have no home feed. Try a broad query once so their
            // Lucid channel isn't permanently empty.
            val searchItems = if (homeItems.isEmpty() && page == 1) {
                withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                    runCatching { api.search("new", 1) }.getOrNull()
                }?.items.orEmpty()
            } else {
                emptyList()
            }
            (homeItems + searchItems)
                .mapNotNull { it.toLucidItem(0) }
                .distinctBy { it.url }
        }

    override suspend fun episodes(scraperId: String, url: String): List<LucidPluginItem> =
        withContext(Dispatchers.IO) {
            val api = loadApi(scraperId) ?: return@withContext emptyList()
            val loaded = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                runCatching { api.load(url) }.getOrNull()
            } ?: return@withContext emptyList()
            val episodes = when (loaded) {
                is TvSeriesLoadResponse -> loaded.episodes
                is AnimeLoadResponse -> loaded.episodes.values.flatten()
                else -> emptyList()
            }
            episodes.map { it.toLucidItem(0) }
        }

    override suspend fun links(scraperId: String, url: String): List<LucidPluginLink> =
        withContext(Dispatchers.IO) {
            val api = loadApi(scraperId) ?: return@withContext emptyList()
            val links = mutableListOf<LucidPluginLink>()
            withTimeoutOrNull(LINKS_TIMEOUT_MS) {
                runCatching {
                    api.loadLinks(
                        url,
                        false,
                        { },
                        { link ->
                            if (link.url.isNotBlank()) {
                                links += LucidPluginLink(
                                    url = link.url,
                                    headers = link.headers.orEmpty(),
                                    quality = link.quality.toString(),
                                )
                            }
                        },
                    )
                }
            }
            links.distinctBy { it.url }
        }

    override suspend fun tabs(scraperId: String): List<LucidPluginTab> = withContext(Dispatchers.IO) {
        val apis = apis(scraperId)
        buildList {
            apis.forEachIndexed { index, api ->
                cloudStreamTabs(api).forEach { tab ->
                    val name = tab.name.ifBlank { tab.data }
                    if (name.isNotBlank()) {
                        add(
                            LucidPluginTab(
                                apiIndex = index,
                                apiName = api.name,
                                tabName = name,
                                label = if (apis.size > 1) "${api.name} · $name" else name,
                            ),
                        )
                    }
                }
            }
        }.distinctBy { it.apiIndex to it.tabName }
    }

    override suspend fun browseLatest(
        scraperId: String,
        page: Int,
        tab: LucidPluginTab?,
    ): LucidPluginPage? = withContext(Dispatchers.IO) {
        val apis = apis(scraperId)
        if (apis.isEmpty()) return@withContext null
        val tabsByApi = apis.map(::cloudStreamTabs)
        val allTabs = buildList {
            apis.forEachIndexed { index, api ->
                tabsByApi[index].forEach { data ->
                    val name = data.name.ifBlank { data.data }
                    if (name.isNotBlank()) {
                        add(
                            LucidPluginTab(
                                apiIndex = index,
                                apiName = api.name,
                                tabName = name,
                                label = if (apis.size > 1) "${api.name} · $name" else name,
                            ),
                        )
                    }
                }
            }
        }.distinctBy { it.apiIndex to it.tabName }

        val targets = if (tab != null) listOf(tab) else {
            apis.indices.mapNotNull { index ->
                val data = tabsByApi[index].firstOrNull {
                    it.name.contains("latest", ignoreCase = true) ||
                        it.data.contains("latest", ignoreCase = true)
                } ?: tabsByApi[index].firstOrNull()
                data?.let {
                    LucidPluginTab(
                        apiIndex = index,
                        apiName = apis[index].name,
                        tabName = it.name.ifBlank { it.data },
                        label = it.name.ifBlank { it.data },
                    )
                }
            }
        }

        val pages = targets.mapNotNull { target ->
            val api = apis.getOrNull(target.apiIndex) ?: return@mapNotNull null
            val data = tabsByApi[target.apiIndex].firstOrNull {
                it.name == target.tabName || (it.name.isBlank() && it.data == target.tabName)
            } ?: return@mapNotNull null
            val response = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                runCatching {
                    api.getMainPage(
                        page,
                        MainPageRequest(data.name, data.data, data.horizontalImages),
                    )
                }.getOrNull()
            } ?: return@mapNotNull null
            Triple(target, response, api)
        }

        val items = pages.flatMap { (target, response, _) ->
            response.items.flatMap { it.list }.mapNotNull { it.toLucidItem(target.apiIndex) }
        }.distinctBy { it.url }
        if (items.isEmpty() && pages.isEmpty()) return@withContext null
        LucidPluginPage(
            items = items,
            hasNext = pages.any { it.second.hasNext },
            tabs = allTabs,
            activeTab = pages.firstOrNull()?.first ?: tab,
        )
    }

    override suspend fun browseSearch(scraperId: String, query: String): List<LucidPluginItem> =
        withContext(Dispatchers.IO) {
            val apis = apis(scraperId)
            if (apis.isEmpty() || query.isBlank()) return@withContext emptyList()
            apis.flatMapIndexed { index, api ->
                withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                    runCatching { api.search(query, 1) }.getOrNull()
                }?.items.orEmpty().mapNotNull { it.toLucidItem(index) }
            }.distinctBy { it.url }
        }

    override suspend fun detail(scraperId: String, apiIndex: Int, url: String): LucidPluginDetail? =
        withContext(Dispatchers.IO) {
            val api = apis(scraperId).getOrNull(apiIndex) ?: return@withContext null
            val loaded = withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                runCatching { api.load(url) }.getOrNull()
            } ?: return@withContext null
            when (loaded) {
                is TvSeriesLoadResponse -> LucidPluginDetail(
                    name = loaded.name,
                    poster = loaded.posterUrl,
                    description = loaded.plot,
                    isSeries = true,
                    data = null,
                    episodes = loaded.episodes.map { it.toLucidItem(apiIndex) },
                    year = loaded.year,
                    apiIndex = apiIndex,
                )
                is AnimeLoadResponse -> LucidPluginDetail(
                    name = loaded.name,
                    poster = loaded.posterUrl,
                    description = loaded.plot,
                    isSeries = true,
                    data = null,
                    episodes = loaded.episodes.values.flatten().map { it.toLucidItem(apiIndex) },
                    year = loaded.year,
                    apiIndex = apiIndex,
                )
                is MovieLoadResponse -> LucidPluginDetail(
                    name = loaded.name,
                    poster = loaded.posterUrl,
                    description = loaded.plot,
                    isSeries = false,
                    data = loaded.dataUrl,
                    year = loaded.year,
                    apiIndex = apiIndex,
                )
                else -> null
            }
        }

    override suspend fun streams(
        scraperId: String,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
    ): List<LucidPluginLink> = withContext(Dispatchers.IO) {
        val api = apis(scraperId).getOrNull(apiIndex) ?: return@withContext emptyList()
        val links = java.util.Collections.synchronizedList(mutableListOf<LucidPluginLink>())
        withTimeoutOrNull(LINKS_TIMEOUT_MS) {
            runCatching {
                api.loadLinks(
                    data,
                    false,
                    { },
                    { link ->
                        if (link.url.isNotBlank()) {
                            links += LucidPluginLink(
                                url = link.url,
                                headers = link.headers.orEmpty(),
                                quality = link.quality.toString(),
                            )
                        }
                    },
                )
            }
        }
        // Some extensions resolve extractors after loadLinks() has already returned.
        delay(1_200L)
        links.toList().distinctBy { it.url }
    }

    private fun apis(scraperId: String): List<com.lagradost.cloudstream3.MainAPI> =
        runCatching {
            PluginRuntimeHooks.ensureCloudstreamInitialized()
            extensionLoader.getApis(scraperId)
        }.getOrDefault(emptyList())

    /** Tabs a provider exposes. Some declare tabs without setting hasMainPage. */
    private fun cloudStreamTabs(api: com.lagradost.cloudstream3.MainAPI): List<MainPageData> {
        val tabs = runCatching { api.mainPage }.getOrDefault(emptyList())
        val named = tabs.filter { it.name.isNotBlank() || it.data.isNotBlank() }
        if (named.isNotEmpty()) return named
        return if (runCatching { api.hasMainPage }.getOrDefault(false)) tabs else emptyList()
    }

    private fun loadApi(scraperId: String) = runCatching {
        PluginRuntimeHooks.ensureCloudstreamInitialized()
        extensionLoader.getApi(scraperId)
    }.getOrNull()

    private fun SearchResponse.toLucidItem(apiIndex: Int): LucidPluginItem? {
        if (url.isBlank()) return null
        val kind = when (type) {
            TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama -> "series"
            TvType.Live -> "live"
            else -> "movie"
        }
        return LucidPluginItem(
            name = name,
            url = url,
            type = kind,
            poster = posterUrl,
            apiIndex = apiIndex,
        )
    }

    private fun Episode.toLucidItem(apiIndex: Int): LucidPluginItem = LucidPluginItem(
        name = name.orEmpty().ifBlank { "Episode ${episode ?: ""}" },
        url = data,
        type = "series",
        poster = posterUrl,
        season = season,
        episode = episode,
        runtimeMs = runTime?.takeIf { it > 0 }?.let { it * 60_000L },
        apiIndex = apiIndex,
    )
}
