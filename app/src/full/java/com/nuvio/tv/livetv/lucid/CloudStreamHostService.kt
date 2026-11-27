package com.nuvio.tv.livetv.lucid

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.nuvio.tv.core.plugin.cloudstream.CloudStreamLucidPluginBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import javax.inject.Inject

/**
 * Hosts the CloudStream extension runtime in its own process (`:streamhost`). The main app
 * only exchanges small serialized results, so an extension/player memory spike cannot take
 * down the TV UI with a LOW_MEMORY kill.
 */
@AndroidEntryPoint
class CloudStreamHostService : Service() {
    @Inject lateinit var bridge: CloudStreamLucidPluginBridge

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val binder = object : ICloudStreamHost.Stub() {
        override fun call(method: String, argsJson: String): String =
            runBlocking(Dispatchers.IO) {
                runCatching { "OK:" + handle(method, JSONObject(argsJson)) }
                    .getOrElse { error ->
                        "ERR:" + (error.message ?: error.javaClass.simpleName).take(400)
                    }
            }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private suspend fun handle(method: String, args: JSONObject): String = when (method) {
        "extensions" -> json.encodeToString(bridge.extensions())
        "latest" -> json.encodeToString(
            bridge.latest(args.getString("scraperId"), args.optInt("page", 1)),
        )
        "episodes" -> json.encodeToString(
            bridge.episodes(args.getString("scraperId"), args.getString("url")),
        )
        "links" -> json.encodeToString(
            bridge.links(args.getString("scraperId"), args.getString("url")),
        )
        "tabs" -> json.encodeToString(bridge.tabs(args.getString("scraperId")))
        "browseLatest" -> {
            val tab = args.optJSONObject("tab")?.let {
                LucidPluginTab(
                    apiIndex = it.optInt("apiIndex"),
                    apiName = it.optString("apiName"),
                    tabName = it.optString("tabName"),
                    label = it.optString("label"),
                )
            }
            json.encodeToString(
                bridge.browseLatest(args.getString("scraperId"), args.optInt("page", 1), tab),
            )
        }
        "browseSearch" -> json.encodeToString(
            bridge.browseSearch(args.getString("scraperId"), args.getString("query")),
        )
        "detail" -> json.encodeToString(
            bridge.detail(
                args.getString("scraperId"),
                args.optInt("apiIndex"),
                args.getString("url"),
            ),
        )
        "streams" -> json.encodeToString(
            bridge.streams(
                scraperId = args.getString("scraperId"),
                apiIndex = args.optInt("apiIndex"),
                data = args.getString("data"),
                season = args.optNullableInt("season"),
                episode = args.optNullableInt("episode"),
            ),
        )
        else -> error("Unknown CloudStream host method: $method")
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
}
