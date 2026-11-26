package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.livetv.lucid.LucidPluginBridge
import com.nuvio.tv.livetv.lucid.LucidPluginExtension
import com.nuvio.tv.livetv.lucid.LucidPluginItem
import com.nuvio.tv.livetv.lucid.LucidPluginLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CATALOG_TIMEOUT_MS = 45_000L
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
                .map { LucidPluginExtension(id = it.id, name = it.name) }
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
                .mapNotNull { it.toLucidItem(scraperId) }
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
            episodes.map { it.toLucidItem(scraperId) }
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

    private fun loadApi(scraperId: String) = runCatching {
        PluginRuntimeHooks.ensureCloudstreamInitialized()
        extensionLoader.getApi(scraperId)
    }.getOrNull()

    private fun SearchResponse.toLucidItem(scraperId: String): LucidPluginItem? {
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
        )
    }

    private fun Episode.toLucidItem(scraperId: String): LucidPluginItem = LucidPluginItem(
        name = name.orEmpty().ifBlank { "Episode ${episode ?: ""}" },
        url = data,
        type = "series",
        poster = posterUrl,
        season = season,
        episode = episode,
        runtimeMs = runTime?.takeIf { it > 0 }?.let { it * 60_000L },
    )
}
