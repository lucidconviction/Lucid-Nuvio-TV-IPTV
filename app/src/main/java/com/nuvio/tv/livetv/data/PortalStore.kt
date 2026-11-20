package com.nuvio.tv.livetv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portals found by the generator, kept separate from installed playlists until the user adds
 * one. A generated account can be verified without downloading its whole channel list; adding it
 * goes through the normal Xtream playlist path.
 */
object PortalStore {
    private const val PREFS = "nuvio_portal_search"
    private const val KEY = "portals"

    data class Saved(
        val baseUrl: String,
        val username: String,
        val password: String,
        val name: String,
        val expiry: String?,
        val maxConnections: String?,
        val source: String = "Portal search",
        val added: Boolean = false,
    ) {
        val key: String get() = baseUrl.lowercase() + "|" + username.lowercase()
        val m3uUrl: String
            get() = PortalXtreamCredentials(baseUrl, username, password).m3uUrl()
        val host: String get() = baseUrl.substringAfter("://").substringBefore('/')
    }

    private val _portals = MutableStateFlow<List<Saved>>(emptyList())
    val portals: StateFlow<List<Saved>> = _portals.asStateFlow()

    fun init(context: Context) {
        if (_portals.value.isNotEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _portals.value = parse(prefs.getString(KEY, null))
    }

    fun addAll(context: Context, found: List<PortalGenerator.Generated>): Int {
        init(context)
        if (found.isEmpty()) return 0
        val current = _portals.value.toMutableList()
        val known = current.mapTo(HashSet()) { it.key }
        var added = 0
        found.forEach { portal ->
            val saved = Saved(
                baseUrl = portal.baseUrl,
                username = portal.username,
                password = portal.password,
                name = portal.name,
                expiry = portal.expiry,
                maxConnections = portal.maxConnections,
                source = portal.source,
            )
            if (known.add(saved.key)) {
                current += saved
                added++
            }
        }
        _portals.value = current
        persist(context, current)
        return added
    }

    fun markAdded(context: Context, portal: Saved) {
        init(context)
        _portals.value = _portals.value.map {
            if (it.key == portal.key) it.copy(added = true) else it
        }
        persist(context, _portals.value)
    }

    fun remove(context: Context, portal: Saved) {
        init(context)
        _portals.value = _portals.value.filterNot { it.key == portal.key }
        persist(context, _portals.value)
    }

    fun clear(context: Context) {
        _portals.value = emptyList()
        persist(context, emptyList())
    }

    private fun persist(context: Context, list: List<Saved>) {
        val array = JSONArray()
        list.forEach { portal ->
            array.put(
                JSONObject()
                    .put("baseUrl", portal.baseUrl)
                    .put("username", portal.username)
                    .put("password", portal.password)
                    .put("name", portal.name)
                    .put("expiry", portal.expiry.orEmpty())
                    .put("maxConnections", portal.maxConnections.orEmpty())
                    .put("source", portal.source)
                    .put("added", portal.added),
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    private fun parse(raw: String?): List<Saved> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val base = o.optString("baseUrl")
                val user = o.optString("username")
                val pass = o.optString("password")
                if (base.isBlank() || user.isBlank() || pass.isBlank()) continue
                add(
                    Saved(
                        baseUrl = base,
                        username = user,
                        password = pass,
                        name = o.optString("name").ifBlank { "Xtream portal" },
                        expiry = o.optString("expiry").takeIf { it.isNotBlank() },
                        maxConnections = o.optString("maxConnections").takeIf { it.isNotBlank() },
                        source = o.optString("source").ifBlank { "Portal search" },
                        added = o.optBoolean("added", false),
                    )
                )
            }
        }
    }
}
