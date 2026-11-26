package com.nuvio.tv.ui.screens.webapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TvSavedSite(
    val id: String,
    val name: String,
    val url: String,
)

object TvWebAppsStore {
    private const val PREFS = "tv_web_apps"
    private const val KEY = "sites"

    fun defaultSites(): List<TvSavedSite> = listOf(
        TvSavedSite("default-google", "Google", "https://www.google.com"),
        TvSavedSite("default-youtube", "YouTube", "https://www.youtube.com"),
        TvSavedSite("default-wikipedia", "Wikipedia", "https://en.wikipedia.org"),
    )

    /** Shared by the Web-Apps hub and the browser's Bookmarks overlay. */
    fun loadOrSeed(context: Context): List<TvSavedSite> {
        val saved = load(context)
        if (saved.isNotEmpty()) return saved
        val defaults = defaultSites()
        save(context, defaults)
        return defaults
    }

    fun load(context: Context): List<TvSavedSite> = runCatching {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val url = obj.optString("url").trim()
                if (url.isNotBlank()) {
                    add(
                        TvSavedSite(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = name.ifBlank { TvWebMarket.hostOf(url) },
                            url = url,
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, sites: List<TvSavedSite>) {
        val array = JSONArray()
        sites.forEach { site ->
            array.put(
                JSONObject().apply {
                    put("id", site.id)
                    put("name", site.name)
                    put("url", site.url)
                },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
