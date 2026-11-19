package com.nuvio.tv.livetv.data

import android.util.Log
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class StalkerPortalChannel(
    val id: String,
    val name: String,
    val command: String,
    val logo: String?,
    val genreId: String?,
)

data class StalkerPlayback(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * A small Stalker/MAG portal client: handshake, profile, genres and the live channel list.
 * Channels are written into a normal M3U file so the rest of Live TV (keys, groups, favourites,
 * reordering, parental controls) works unchanged. Playback asks the portal for a fresh link with
 * `create_link`, because many portals mint short-lived URLs per tune.
 */
@Singleton
class StalkerPortalClient @Inject constructor(
    private val prefs: LiveTvPreferences,
) {
    companion object {
        private const val TAG = "StalkerPortal"
        private const val SESSION_TTL_MS = 10 * 60_000L
        private const val CHANNEL_TTL_MS = 5 * 60_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val X_USER_AGENT = "Model: MAG250; Link: WiFi"

        /** Strip /c/, /portal.php, a pasted query and a missing scheme. */
        fun normalizePortal(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                value = "http://$value"
            }
            value = value.substringBefore('?').trimEnd('/')
            value = value.removeSuffix("/portal.php").removeSuffix("/server/load.php").removeSuffix("/c")
            return value.trimEnd('/')
        }

        fun normalizeMac(raw: String): String =
            raw.trim().uppercase().replace('-', ':')

        fun randomMac(): String {
            val r = java.security.SecureRandom()
            return "00:1A:79:" + (0 until 3).joinToString(":") {
                String.format("%02X", r.nextInt(256))
            }
        }

        /** Portals prefix the command with a player name ("ffmpeg http…", "ffrt http…"). */
        fun stripCommandPrefix(command: String): String {
            var value = command.trim()
            listOf("ffmpeg ", "ffrt ", "auto ", "mpegts ").forEach { prefix ->
                if (value.startsWith(prefix, ignoreCase = true)) value = value.substring(prefix.length)
            }
            return value.trim()
        }

        fun buildM3u(sourceId: String, channels: List<StalkerPortalChannel>): String = buildString {
            appendLine("#EXTM3U")
            channels.forEach { channel ->
                val name = channel.name.replace('\n', ' ').replace('\r', ' ').trim()
                if (name.isBlank() || channel.id.isBlank()) return@forEach
                val group = channel.genreId?.takeIf { it.isNotBlank() }.orEmpty()
                append("#EXTINF:-1 tvg-id=\"")
                append(channel.id.replace("\"", ""))
                append("\" tvg-name=\"")
                append(name.replace("\"", ""))
                append("\"")
                channel.logo?.takeIf { it.isNotBlank() }?.let {
                    append(" tvg-logo=\"")
                    append(it.replace("\"", ""))
                    append("\"")
                }
                if (group.isNotBlank()) {
                    append(" group-title=\"")
                    append(group.replace("\"", ""))
                    append("\"")
                }
                append(",")
                appendLine(name)
                appendLine("stalker:$sourceId:${channel.id}")
            }
        }
    }

    private data class Session(
        val token: String?,
        val headers: Map<String, String>,
        val atMs: Long,
    )

    private val http = ForkHttp.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val sessions = ConcurrentHashMap<String, Session>()
    private val channelCache = ConcurrentHashMap<String, Pair<Long, List<StalkerPortalChannel>>>()
    private val generatedMacs = ConcurrentHashMap<String, String>()

    suspend fun fetchChannels(source: PlaylistSource, force: Boolean = false): List<StalkerPortalChannel> =
        withContext(Dispatchers.IO) {
            val cached = channelCache[source.id]
            if (!force && cached != null && System.currentTimeMillis() - cached.first < CHANNEL_TTL_MS) {
                return@withContext cached.second
            }
            val session = session(source) ?: return@withContext emptyList()
            val genres = fetchGenres(source, session)
            val channels = fetchAllChannels(source, session, genres)
            if (channels.isNotEmpty()) {
                channelCache[source.id] = System.currentTimeMillis() to channels
            }
            channels
        }

    suspend fun createLink(sourceId: String, channelId: String): StalkerPlayback? =
        withContext(Dispatchers.IO) {
            val source = prefs.currentPlaylists().firstOrNull { it.id == sourceId } ?: return@withContext null
            val channel = fetchChannels(source).firstOrNull { it.id == channelId } ?: return@withContext null
            val session = session(source) ?: return@withContext null
            val command = URLEncoder.encode(channel.command, "UTF-8")
            val url = portalUrl(
                source,
                "type=itv&action=create_link&cmd=$command&series=0&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0",
            )
            val response = get(url, session.headers)
            val js = response?.opt("js")
            val selected = when (js) {
                is JSONObject -> js.optString("cmd").takeIf { it.isNotBlank() }
                is String -> js.takeIf { it.isNotBlank() }
                else -> null
            }
            val resolved = stripCommandPrefix(selected ?: channel.command)
            if (resolved.isBlank()) return@withContext null
            StalkerPlayback(url = resolved, headers = session.headers)
        }

    private fun fetchGenres(source: PlaylistSource, session: Session): Map<String, String> {
        val url = portalUrl(source, "type=itv&action=get_genres")
        val response = get(url, session.headers) ?: return emptyMap()
        val array = response.opt("js") as? JSONArray ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                put(id, title)
            }
        }
    }

    private fun fetchAllChannels(
        source: PlaylistSource,
        session: Session,
        genres: Map<String, String>,
    ): List<StalkerPortalChannel> {
        val all = ArrayList<StalkerPortalChannel>()
        var page = 1
        while (page <= 50) {
            val url = portalUrl(source, "type=itv&action=get_ordered_list&p=$page")
            val response = get(url, session.headers) ?: break
            val js = response.opt("js") ?: break
            val data = when (js) {
                is JSONArray -> js
                is JSONObject -> js.optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = (item.optString("name").ifBlank { item.optString("title") })
                    .takeIf { it.isNotBlank() } ?: continue
                val command = item.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                val logo = item.optString("logo").ifBlank { item.optString("logo_uri") }
                    .takeIf { it.isNotBlank() }
                val genreId = item.optString("tv_genre_id").takeIf { it.isNotBlank() }
                all += StalkerPortalChannel(
                    id = id,
                    name = name,
                    command = command,
                    logo = logo,
                    genreId = genreId?.let { genres[it] ?: it },
                )
            }
            val total = (js as? JSONObject)?.optInt("total_items", 0) ?: 0
            val perPage = (js as? JSONObject)?.optInt("max_page_items", 0) ?: 0
            if (total <= 0 || perPage <= 0 || page * perPage >= total) break
            page++
        }
        if (all.isNotEmpty()) return all
        // Older portals only answer get_all_channels.
        val fallback = get(portalUrl(source, "type=itv&action=get_all_channels"), session.headers) ?: return emptyList()
        val fallbackJs = fallback.opt("js")
        val array = when (fallbackJs) {
            is JSONArray -> fallbackJs
            is JSONObject -> fallbackJs.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val command = item.optString("cmd").takeIf { it.isNotBlank() } ?: continue
                add(
                    StalkerPortalChannel(
                        id = id,
                        name = name,
                        command = command,
                        logo = item.optString("logo").ifBlank { item.optString("logo_uri") }.takeIf { it.isNotBlank() },
                        genreId = item.optString("tv_genre_id").takeIf { it.isNotBlank() }?.let { genres[it] ?: it },
                    )
                )
            }
        }
    }

    private fun session(source: PlaylistSource): Session? {
        sessions[source.id]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMs < SESSION_TTL_MS) return cached
        }
        val baseHeaders = baseHeaders(source)
        val handshake = get(
            portalUrl(source, "type=stb&action=handshake&token=&prehash=0"),
            baseHeaders,
        )
        val js = handshake?.opt("js")
        val token = when (js) {
            is JSONObject -> js.optString("token").takeIf { it.isNotBlank() }
            is String -> js.takeIf { it.isNotBlank() }
            else -> null
        }
        val headers = if (token != null) {
            baseHeaders + ("Authorization" to "Bearer $token")
        } else {
            baseHeaders
        }
        // Establishes the portal's profile/cookies; a failure here is not fatal.
        runCatching { get(portalUrl(source, "type=stb&action=get_profile"), headers) }
        val session = Session(token = token, headers = headers, atMs = System.currentTimeMillis())
        sessions[source.id] = session
        return session
    }

    private fun baseHeaders(source: PlaylistSource): Map<String, String> {
        val portal = normalizePortal(source.stalkerPortal)
        val mac = normalizeMac(source.stalkerMac).ifBlank {
            generatedMacs.getOrPut(source.id) { randomMac() }
        }
        val timezone = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("X-User-Agent", X_USER_AGENT)
            put("Referer", "$portal/c/")
            put("Cookie", "mac=$mac; stb_lang=en; timezone=$timezone")
            source.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
    }

    private fun portalUrl(source: PlaylistSource, params: String): String {
        val portal = normalizePortal(source.stalkerPortal)
        val separator = if (params.contains("?")) "&" else "?"
        return "$portal/portal.php$separator$params&JsHttpRequest=1-xml"
    }

    private fun get(url: String, headers: Map<String, String>): JSONObject? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from $url")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body)
            }
        }.onFailure { Log.w(TAG, "Portal request failed: ${it.message}") }.getOrNull()
    }
}
