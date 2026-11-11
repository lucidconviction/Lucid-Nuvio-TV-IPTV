package com.nuvio.tv.livetv.multiview

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class SavedMultiViewLayout(
    val name: String,
    val layout: MultiViewLayout,
    val streams: List<MultiViewStream>,
)

/** Up to ten Multi-view layouts, saved on the device and restored with their streams. */
object MultiViewSavedLayouts {
    const val MAX_SAVED = 10
    private const val PREFS = "nuvio_multiview_layouts"
    private const val KEY = "layouts"

    private val _saved = mutableStateListOf<SavedMultiViewLayout>()
    val saved: List<SavedMultiViewLayout> get() = _saved

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val layout = runCatching {
                        MultiViewLayout.valueOf(obj.optString("layout"))
                    }.getOrDefault(MultiViewLayout.AUTO)
                    val streamsArray = obj.optJSONArray("streams") ?: JSONArray()
                    val streams = buildList {
                        for (j in 0 until streamsArray.length()) {
                            val s = streamsArray.optJSONObject(j) ?: continue
                            val headers = s.optJSONObject("headers")?.let { h ->
                                buildMap { h.keys().forEach { k -> put(k, h.optString(k)) } }
                            }.orEmpty()
                            add(
                                MultiViewStream(
                                    id = s.optString("id"),
                                    url = s.optString("url"),
                                    title = s.optString("title"),
                                    subtitle = s.optString("subtitle").takeIf { it.isNotBlank() },
                                    logo = s.optString("logo").takeIf { it.isNotBlank() },
                                    headers = headers,
                                    providerName = s.optString("providerName"),
                                )
                            )
                        }
                    }
                    add(SavedMultiViewLayout(obj.optString("name"), layout, streams))
                }
            }
        }.getOrDefault(emptyList())
        _saved.clear()
        _saved.addAll(parsed.take(MAX_SAVED))
    }

    /** Saves a copy of the open layout; false when ten are already saved. */
    fun save(context: Context, layout: MultiViewLayout, streams: List<MultiViewStream>): Boolean {
        if (_saved.size >= MAX_SAVED || streams.isEmpty()) return false
        val entry = SavedMultiViewLayout(
            name = "Layout ${_saved.size + 1}",
            layout = layout,
            streams = streams.toList(),
        )
        _saved.add(entry)
        persist(context)
        return true
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        _saved.forEach { entry ->
            val obj = JSONObject()
            obj.put("name", entry.name)
            obj.put("layout", entry.layout.name)
            val streams = JSONArray()
            entry.streams.forEach { stream ->
                val s = JSONObject()
                s.put("id", stream.id)
                s.put("url", stream.url)
                s.put("title", stream.title)
                s.put("subtitle", stream.subtitle ?: "")
                s.put("logo", stream.logo ?: "")
                s.put("providerName", stream.providerName)
                if (stream.headers.isNotEmpty()) {
                    val headers = JSONObject()
                    stream.headers.forEach { (k, v) -> headers.put(k, v) }
                    s.put("headers", headers)
                }
                streams.put(s)
            }
            obj.put("streams", streams)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
