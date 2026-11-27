package com.nuvio.tv.livetv.lucid

import kotlinx.serialization.Serializable

/**
 * One installed CloudStream extension, as the Lucid TV source needs it.
 * Kept in the main source set so the Lucid guide can use extensions without linking the
 * CloudStream runtime (which only exists in the full distribution).
 */
@Serializable
data class LucidPluginExtension(
    val id: String,
    val name: String,
    val logo: String? = null,
)

/** A movie, series or live item returned by a CloudStream extension. */
@Serializable
data class LucidPluginItem(
    val name: String,
    val url: String,
    val type: String,
    val poster: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMs: Long? = null,
    val apiIndex: Int = 0,
)

/** One provider tab exposed by a CloudStream extension. */
@Serializable
data class LucidPluginTab(
    val apiIndex: Int,
    val apiName: String,
    val tabName: String,
    val label: String,
)

/** One page of a provider's own latest/home feed. */
@Serializable
data class LucidPluginPage(
    val items: List<LucidPluginItem>,
    val hasNext: Boolean,
    val tabs: List<LucidPluginTab> = emptyList(),
    val activeTab: LucidPluginTab? = null,
)

/** A provider item's detail, with episodes when it is a series. */
@Serializable
data class LucidPluginDetail(
    val name: String,
    val poster: String?,
    val description: String?,
    val isSeries: Boolean,
    val data: String?,
    val episodes: List<LucidPluginItem> = emptyList(),
    val year: Int? = null,
    val apiIndex: Int = 0,
    val backdrop: String? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val actors: List<LucidPluginActor> = emptyList(),
    val trailerUrl: String? = null,
    val trailerHeaders: Map<String, String> = emptyMap(),
    val durationMinutes: Int? = null,
    val status: String? = null,
    val recommendations: List<LucidPluginItem> = emptyList(),
)

@Serializable
data class LucidPluginActor(
    val name: String,
    val image: String? = null,
)

/** A playable link resolved by a CloudStream extension. */
@Serializable
data class LucidPluginLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
)

/**
 * The Lucid TV side of the CloudStream runtime. The full flavor supplies a real bridge;
 * the Play Store flavor supplies an empty one, so both flavors keep the same Lucid model.
 */
interface LucidPluginBridge {
    suspend fun extensions(): List<LucidPluginExtension>
    suspend fun latest(scraperId: String, page: Int = 1): List<LucidPluginItem>
    suspend fun episodes(scraperId: String, url: String): List<LucidPluginItem>
    suspend fun links(scraperId: String, url: String): List<LucidPluginLink>

    /** Provider browsing used by the CloudStream page. */
    suspend fun tabs(scraperId: String): List<LucidPluginTab>
    suspend fun browseLatest(
        scraperId: String,
        page: Int,
        tab: LucidPluginTab? = null,
    ): LucidPluginPage?
    suspend fun browseSearch(scraperId: String, query: String): List<LucidPluginItem>
    suspend fun detail(scraperId: String, apiIndex: Int, url: String): LucidPluginDetail?
    suspend fun streams(
        scraperId: String,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
    ): List<LucidPluginLink>
}

object NoOpLucidPluginBridge : LucidPluginBridge {
    override suspend fun extensions(): List<LucidPluginExtension> = emptyList()
    override suspend fun latest(scraperId: String, page: Int): List<LucidPluginItem> = emptyList()
    override suspend fun episodes(scraperId: String, url: String): List<LucidPluginItem> = emptyList()
    override suspend fun links(scraperId: String, url: String): List<LucidPluginLink> = emptyList()
    override suspend fun tabs(scraperId: String): List<LucidPluginTab> = emptyList()
    override suspend fun browseLatest(scraperId: String, page: Int, tab: LucidPluginTab?): LucidPluginPage? = null
    override suspend fun browseSearch(scraperId: String, query: String): List<LucidPluginItem> = emptyList()
    override suspend fun detail(scraperId: String, apiIndex: Int, url: String): LucidPluginDetail? = null
    override suspend fun streams(
        scraperId: String,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
    ): List<LucidPluginLink> = emptyList()
}
