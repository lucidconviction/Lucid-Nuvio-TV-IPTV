package com.nuvio.tv.livetv.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class IptvOrgListType { COUNTRY, CATEGORY, REGION }

/** One iptv-org playlist the user can add, with the country/category/region title it belongs to. */
data class IptvOrgList(
    val type: IptvOrgListType,
    val code: String,
    val name: String,
    val flag: String? = null,
    val subtitle: String,
    val url: String
)

data class IptvOrgCatalogData(
    val countries: List<IptvOrgList> = emptyList(),
    val categories: List<IptvOrgList> = emptyList(),
    val regions: List<IptvOrgList> = emptyList(),
    val fromCache: Boolean = false
)

/**
 * The iptv-org playlist index: the small JSON lists the project publishes at
 * iptv-org.github.io/api, turned into the country/category/region M3U URLs.
 * Lists are cached for a day; a failed refresh falls back to the cached copy.
 */
@Singleton
class IptvOrgCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = ForkHttp.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(force: Boolean = false): IptvOrgCatalogData = withContext(Dispatchers.IO) {
        coroutineScope {
            val countries = async { loadList("countries", force, ::parseCountries) }
            val categories = async { loadList("categories", force, ::parseCategories) }
            val regions = async { loadList("regions", force, ::parseRegions) }
            val countryList = countries.await()
            val categoryList = categories.await()
            val regionList = regions.await()
            IptvOrgCatalogData(
                countries = countryList.first,
                categories = categoryList.first,
                regions = regionList.first,
                fromCache = countryList.second || categoryList.second || regionList.second
            )
        }
    }

    private suspend fun loadList(
        name: String,
        force: Boolean,
        parse: (String) -> List<IptvOrgList>
    ): Pair<List<IptvOrgList>, Boolean> {
        val file = File(context.cacheDir, "iptv-org-$name.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS
        if (!force && fresh) {
            runCatching { return parse(file.readText()) to true }
            file.delete()
        }
        val fetched = runCatching { fetch("https://iptv-org.github.io/api/$name.json") }.getOrNull()
        if (fetched != null) {
            runCatching { file.writeText(fetched) }
            return parse(fetched) to false
        }
        if (file.exists()) {
            return parse(file.readText()) to true
        }
        throw IOException("Could not load the iptv-org $name list")
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", LiveTvRepository.DEFAULT_UA)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("iptv-org returned HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("iptv-org returned an empty list")
        }
    }
}

private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

internal fun parseCountries(json: String): List<IptvOrgList> {
    val array = JSONArray(json)
    return buildList {
        add(
            IptvOrgList(
                type = IptvOrgListType.COUNTRY,
                code = "ALL",
                name = "All channels",
                flag = "🌍",
                subtitle = "iptv-org · all countries · very large list",
                url = "https://iptv-org.github.io/iptv/index.m3u"
            )
        )
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val code = o.optString("code").trim()
            val name = o.optString("name").trim()
            if (code.isBlank() || name.isBlank()) continue
            add(
                IptvOrgList(
                    type = IptvOrgListType.COUNTRY,
                    code = code,
                    name = name,
                    flag = o.optString("flag").trim().takeIf { it.isNotEmpty() },
                    subtitle = "iptv-org · country playlist · $code",
                    url = "https://iptv-org.github.io/iptv/countries/${code.lowercase()}.m3u"
                )
            )
        }
    }.sortedWith(compareBy({ it.code != "ALL" }, { it.name.lowercase() }))
}

internal fun parseCategories(json: String): List<IptvOrgList> {
    val array = JSONArray(json)
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            val description = o.optString("description").trim()
            add(
                IptvOrgList(
                    type = IptvOrgListType.CATEGORY,
                    code = id,
                    name = name,
                    subtitle = description.ifBlank { "iptv-org · category playlist · $id" },
                    url = "https://iptv-org.github.io/iptv/categories/$id.m3u"
                )
            )
        }
    }.sortedBy { it.name.lowercase() }
}

internal fun parseRegions(json: String): List<IptvOrgList> {
    val array = JSONArray(json)
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val code = o.optString("code").trim()
            val name = o.optString("name").trim()
            if (code.isBlank() || name.isBlank()) continue
            add(
                IptvOrgList(
                    type = IptvOrgListType.REGION,
                    code = code,
                    name = name,
                    subtitle = "iptv-org · region playlist · $code",
                    url = "https://iptv-org.github.io/iptv/regions/${code.lowercase()}.m3u"
                )
            )
        }
    }.sortedBy { it.name.lowercase() }
}
