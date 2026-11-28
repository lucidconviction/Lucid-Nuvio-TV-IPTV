package com.nuvio.tv.livetv.lucid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Main-process client for [CloudStreamHostService]. The CloudStream classes, DEX class
 * loaders and extension instances live only in the host process.
 */
@Singleton
class RemoteLucidPluginBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) : LucidPluginBridge {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val bindLock = Mutex()

    @Volatile private var host: ICloudStreamHost? = null
    private var connection: ServiceConnection? = null

    private suspend fun host(): ICloudStreamHost {
        host?.let { return it }
        return bindLock.withLock {
            host?.let { return@withLock it }
            suspendCancellableCoroutine { continuation ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val api = ICloudStreamHost.Stub.asInterface(service)
                        host = api
                        if (continuation.isActive) continuation.resume(api)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        host = null
                    }
                }
                connection = conn
                Handler(Looper.getMainLooper()).post {
                    val bound = runCatching {
                        context.bindService(
                            Intent(context, CloudStreamHostService::class.java),
                            conn,
                            Context.BIND_AUTO_CREATE,
                        )
                    }.getOrDefault(false)
                    if (!bound && continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("CloudStream host service did not start"),
                        )
                    }
                }
            }
        }
    }

    private suspend inline fun <reified T> call(
        method: String,
        args: JSONObject = JSONObject(),
    ): T {
        val raw = withContext(Dispatchers.IO) {
            var last: Throwable? = null
            var attempt = 0
            while (attempt < 3) {
                try {
                    val api = host()
                    return@withContext api.call(method, args.toString())
                } catch (t: Throwable) {
                    val remote = t is RemoteException ||
                        t is android.os.DeadObjectException
                    if (!remote) throw t
                    host = null
                    last = t
                    android.util.Log.w(
                        "RemoteLucidPluginBridge",
                        "host died during $method (try ${attempt + 1}); rebinding",
                    )
                    delay(350L * (attempt + 1))
                }
                attempt++
            }
            throw last ?: IllegalStateException("CloudStream host did not answer $method")
        }
        if (raw.startsWith("ERR:")) {
            throw IllegalStateException(raw.removePrefix("ERR:"))
        }
        val payload = raw.removePrefix("OK:")
        return json.decodeFromString(payload)
    }

    override suspend fun extensions(): List<LucidPluginExtension> = call("extensions")

    override suspend fun latest(scraperId: String, page: Int): List<LucidPluginItem> =
        call("latest", JSONObject().put("scraperId", scraperId).put("page", page))

    override suspend fun episodes(scraperId: String, url: String): List<LucidPluginItem> =
        call("episodes", JSONObject().put("scraperId", scraperId).put("url", url))

    override suspend fun links(scraperId: String, url: String): List<LucidPluginLink> =
        call("links", JSONObject().put("scraperId", scraperId).put("url", url))

    override suspend fun tabs(scraperId: String): List<LucidPluginTab> =
        call("tabs", JSONObject().put("scraperId", scraperId))

    override suspend fun browseLatest(
        scraperId: String,
        page: Int,
        tab: LucidPluginTab?,
    ): LucidPluginPage? = call(
        "browseLatest",
        JSONObject()
            .put("scraperId", scraperId)
            .put("page", page)
            .put(
                "tab",
                tab?.let {
                    JSONObject()
                        .put("apiIndex", it.apiIndex)
                        .put("apiName", it.apiName)
                        .put("tabName", it.tabName)
                        .put("label", it.label)
                } ?: JSONObject.NULL,
            ),
    )

    override suspend fun browseSearch(scraperId: String, query: String): List<LucidPluginItem> =
        call("browseSearch", JSONObject().put("scraperId", scraperId).put("query", query))

    override suspend fun detail(
        scraperId: String,
        apiIndex: Int,
        url: String,
    ): LucidPluginDetail? = call(
        "detail",
        JSONObject().put("scraperId", scraperId).put("apiIndex", apiIndex).put("url", url),
    )

    override suspend fun streams(
        scraperId: String,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
    ): List<LucidPluginLink> = call(
        "streams",
        JSONObject()
            .put("scraperId", scraperId)
            .put("apiIndex", apiIndex)
            .put("data", data)
            .put("season", season ?: JSONObject.NULL)
            .put("episode", episode ?: JSONObject.NULL),
    )
}
