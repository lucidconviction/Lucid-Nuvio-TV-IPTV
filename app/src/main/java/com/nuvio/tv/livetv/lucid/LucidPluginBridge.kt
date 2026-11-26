package com.nuvio.tv.livetv.lucid

/**
 * One installed CloudStream extension, as the Lucid TV source needs it.
 * Kept in the main source set so the Lucid guide can use extensions without linking the
 * CloudStream runtime (which only exists in the full distribution).
 */
data class LucidPluginExtension(
    val id: String,
    val name: String,
)

/** A movie, series or live item returned by a CloudStream extension. */
data class LucidPluginItem(
    val name: String,
    val url: String,
    val type: String,
    val poster: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMs: Long? = null,
)

/** A playable link resolved by a CloudStream extension. */
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
}

object NoOpLucidPluginBridge : LucidPluginBridge {
    override suspend fun extensions(): List<LucidPluginExtension> = emptyList()
    override suspend fun latest(scraperId: String, page: Int): List<LucidPluginItem> = emptyList()
    override suspend fun episodes(scraperId: String, url: String): List<LucidPluginItem> = emptyList()
    override suspend fun links(scraperId: String, url: String): List<LucidPluginLink> = emptyList()
}
