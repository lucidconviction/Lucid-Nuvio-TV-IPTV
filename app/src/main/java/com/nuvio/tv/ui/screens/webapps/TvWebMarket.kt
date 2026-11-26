package com.nuvio.tv.ui.screens.webapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TvMarketApp(
    val name: String,
    val url: String,
    val categoryId: String,
    val rank: Int = 0,
)

data class TvMarketCategory(
    val id: String,
    val groupId: String,
    val label: String,
)

data class TvMarketGroup(
    val id: String,
    val label: String,
    val categories: List<TvMarketCategory>,
)

/** The SFW FMHY sections bundled by the mobile Web App Market. */
object TvWebMarket {
    private fun category(id: String, group: String, label: String) =
        TvMarketCategory(id = id, groupId = group, label = label)

    val groups: List<TvMarketGroup> = listOf(
        TvMarketGroup(
            "movies", "Movies & TV", listOf(
                category("stream-aggregators", "movies", "Stream Aggregators"),
                category("p-stream-forks", "movies", "P-Stream Forks"),
                category("dedicated-server", "movies", "Dedicated Servers"),
                category("multi-server", "movies", "Multi-Server"),
                category("stream-lounges", "movies", "Stream Lounges"),
                category("free-w-ads", "movies", "Free with Ads"),
                category("video-streaming", "movies", "Video Streaming"),
                category("tv-streaming", "movies", "TV Streaming"),
                category("drama-streaming", "movies", "Drama Streaming"),
            ),
        ),
        TvMarketGroup(
            "anime", "Anime & Cartoons", listOf(
                category("anime-streaming", "anime", "Anime Streaming"),
                category("cartoon-streaming", "anime", "Cartoon Streaming"),
            ),
        ),
        TvMarketGroup(
            "live", "Live TV", listOf(
                category("live-tv", "live", "Live TV"),
            ),
        ),
        TvMarketGroup(
            "sports", "Sports", listOf(
                category("live-sports", "sports", "Live Sports"),
                category("sports-replays", "sports", "Sports Replays"),
            ),
        ),
        TvMarketGroup(
            "archives", "Archives", listOf(
                category("classics-public-domain", "archives", "Classics / Public Domain"),
                category("film-archives", "archives", "Film Archives"),
            ),
        ),
    )

    fun category(id: String): TvMarketCategory? =
        groups.flatMap { it.categories }.firstOrNull { it.id == id }

    fun load(context: Context): List<TvMarketApp> = runCatching {
        val text = context.assets.open("webapp_market_seed.json").bufferedReader().use { it.readText() }
        val apps = JSONObject(text).optJSONArray("apps") ?: JSONArray()
        buildList {
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                if (app.optBoolean("adult", false)) continue
                val name = app.optString("name").trim()
                val url = app.optString("url").trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    add(
                        TvMarketApp(
                            name = name,
                            url = url,
                            categoryId = app.optString("categoryId"),
                            rank = app.optInt("rank", 0),
                        ),
                    )
                }
            }
        }.distinctBy { it.url }
    }.getOrDefault(emptyList())

    fun hostOf(url: String): String =
        url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .removePrefix("www.")
            .lowercase()

    fun matches(query: String, app: TvMarketApp): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val category = category(app.categoryId)?.label.orEmpty()
        return app.name.contains(q, ignoreCase = true) ||
            hostOf(app.url).contains(q, ignoreCase = true) ||
            category.contains(q, ignoreCase = true)
    }
}
