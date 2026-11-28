package com.nuvio.tv.ui.screens.browser.hikari

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.WebSettings
import com.nuvio.tv.ui.screens.webapps.TvSavedSite
import com.nuvio.tv.ui.screens.webapps.TvWebAppsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** A user-added website opened in the ad-free web view. */
data class BrowserSite(val name: String, val url: String)

/** A Tampermonkey-style userscript that runs inside the WebView only. */
data class BrowserUserscript(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val code: String,
)

/**
 * The settings the ported Hikari browser expects from its AppStore, backed by a
 * small SharedPreferences file. Keeping the names synchronous is deliberate:
 * the WebView's ad and redirect gates answer before the first page request.
 */
object BrowserStore {
    private const val PREFS = "lucid_hikari_browser"
    private const val REDIRECT = "webview_redirect"
    private const val POPUP = "webview_popup"
    private const val AUTOPLAY_BEST = "webview_autoplay_best"
    private const val REDIRECT_ALLOW = "webview_redirect_allow"
    private const val POPUP_ALLOW = "webview_popup_allow"
    private const val POPUP_BLOCK = "webview_popup_block"
    private const val AD_ENABLED = "ad_enabled"
    private const val AD_LISTS = "ad_lists"
    private const val AD_BLOCK = "ad_block"
    private const val AD_WHITE = "ad_white"
    private const val ELEMENT_BLOCKS = "element_blocks"
    private const val DESKTOP = "desktop"
    private const val MOUSE = "mouse_mode"
    private const val USERS = "userscripts"

    private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences {
        prefs?.let { return it }
        val context = BrowserHost.appContext
        val created = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = created
        return created
    }

    fun webviewRedirect(): Boolean = prefs().getBoolean(REDIRECT, true)

    fun setWebviewRedirect(enabled: Boolean) {
        prefs().edit().putBoolean(REDIRECT, enabled).apply()
    }

    fun webviewPopup(): Boolean = prefs().getBoolean(POPUP, true)

    fun setWebviewPopup(enabled: Boolean) {
        prefs().edit().putBoolean(POPUP, enabled).apply()
    }

    fun webviewAutoplayBest(): Boolean = prefs().getBoolean(AUTOPLAY_BEST, false)

    fun setWebviewAutoplayBest(enabled: Boolean) {
        prefs().edit().putBoolean(AUTOPLAY_BEST, enabled).apply()
    }

    fun desktopMode(): Boolean = prefs().getBoolean(DESKTOP, false)

    fun setDesktopMode(enabled: Boolean) {
        prefs().edit().putBoolean(DESKTOP, enabled).apply()
    }

    /** TV remotes get the pointer by default: it can reach page elements and
     *  the whole browser toolbar. Turning it off restores D-pad page scrolling. */
    fun mouseMode(): Boolean = prefs().getBoolean(MOUSE, true)

    fun setMouseMode(enabled: Boolean) {
        prefs().edit().putBoolean(MOUSE, enabled).apply()
    }

    fun webviewRedirectAllow(): List<String> = readList(prefs(), REDIRECT_ALLOW)

    fun setWebviewRedirectAllow(list: List<String>) {
        writeList(prefs(), REDIRECT_ALLOW, list)
        BrowserRedirectAllow.set(list)
    }

    fun webviewPopupAllow(): List<String> = readList(prefs(), POPUP_ALLOW)

    fun setWebviewPopupAllow(list: List<String>) = writeList(prefs(), POPUP_ALLOW, list)

    fun webviewPopupBlock(): List<String> = readList(prefs(), POPUP_BLOCK)

    fun setWebviewPopupBlock(list: List<String>) = writeList(prefs(), POPUP_BLOCK, list)

    fun adEnabled(): Boolean = prefs().getBoolean(AD_ENABLED, true)

    fun setAdEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(AD_ENABLED, enabled).apply()
    }

    /** Built-ins are always active; a small curated list is cached on first open. */
    fun adLists(): List<AdBlocker.HostList> {
        val raw = prefs().getString(AD_LISTS, null)
        if (raw.isNullOrBlank()) {
            val seed = listOf(AdBlocker.PRESETS.first())
            setAdLists(seed)
            return seed
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isNotBlank()) add(AdBlocker.HostList(o.optString("name").ifBlank { url }, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setAdLists(lists: List<AdBlocker.HostList>) {
        val array = JSONArray()
        lists.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs().edit().putString(AD_LISTS, array.toString()).apply()
    }

    fun adBlock(): List<String> = readList(prefs(), AD_BLOCK)

    fun setAdBlock(list: List<String>) = writeList(prefs(), AD_BLOCK, list)

    fun adWhite(): List<String> = readList(prefs(), AD_WHITE)

    fun setAdWhite(list: List<String>) = writeList(prefs(), AD_WHITE, list)

    fun elementBlocks(): List<String> = readList(prefs(), ELEMENT_BLOCKS)

    fun addElementBlock(selector: String) {
        val clean = selector.trim()
        if (clean.isEmpty()) return
        val cur = elementBlocks()
        if (clean in cur) return
        val next = (cur + clean).take(200)
        writeList(prefs(), ELEMENT_BLOCKS, next)
        BrowserHost.elementBlocks = next
    }

    fun removeLastElementBlock(): String? {
        val cur = elementBlocks()
        if (cur.isEmpty()) return null
        val last = cur.last()
        val next = cur.dropLast(1)
        writeList(prefs(), ELEMENT_BLOCKS, next)
        BrowserHost.elementBlocks = next
        return last
    }

    fun clearElementBlocks() {
        writeList(prefs(), ELEMENT_BLOCKS, emptyList())
        BrowserHost.elementBlocks = emptyList()
    }

    /** Auto-translation providers are not surfaced in the TV settings yet. */
    fun translateProviders(): Set<String> = emptySet()

    fun userscripts(): List<BrowserUserscript> {
        val raw = prefs().getString(USERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val code = o.optString("code")
                    if (code.isBlank()) continue
                    add(
                        BrowserUserscript(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = o.optString("name").ifBlank { "Userscript" },
                            enabled = o.optBoolean("enabled", true),
                            code = code,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addSite(site: BrowserSite) {
        val context = BrowserHost.appContext
        val url = site.url.trim()
        if (url.isBlank()) return
        val sites = TvWebAppsStore.load(context).toMutableList()
        if (sites.any { it.url.trim().equals(url, ignoreCase = true) }) return
        sites.add(
            TvSavedSite(
                id = "browser-" + UUID.randomUUID().toString(),
                name = site.name.trim().ifBlank { runCatching { URI(url).host }.getOrNull().orEmpty() },
                url = url,
            ),
        )
        TvWebAppsStore.save(context, sites)
    }

    private fun readList(p: SharedPreferences, key: String): List<String> =
        runCatching {
            val raw = p.getString(key, null) ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun writeList(p: SharedPreferences, key: String, list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        p.edit().putString(key, array.toString()).apply()
    }
}

/**
 * Process-wide browser host. It replaces HikariApp's store/UA/element-block
 * surface; the Android Application stays NuvioApplication.
 */
object BrowserHost {
    @Volatile private var contextRef: Context? = null

    val appContext: Context
        get() = contextRef ?: error("BrowserHost.init() was not called")

    val isReady: Boolean get() = contextRef != null

    @Volatile
    var elementBlocks: List<String> = emptyList()

    val homeTabRequest = MutableStateFlow(0)
    val musicTabRequest = MutableStateFlow(0)
    val webAppsTabRequest = MutableStateFlow(0)

    fun init(context: Context) {
        if (contextRef != null) return
        contextRef = context.applicationContext
        elementBlocks = BrowserStore.elementBlocks()
        BrowserRedirectAllow.set(BrowserStore.webviewRedirectAllow())
        WebViewLinkGuard.init(context)
    }

    fun effectiveWebViewUa(context: Context): String {
        val stock = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault(BrowserHttp.UA)
        return stock
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .replace("  ", " ")
            .trim()
    }
}

/** A tiny English dictionary: the ported browser's literals stay readable. */
object BrowserI18n {
    fun t(en: String): String = en
}

object BrowserLogs {
    private const val TAG = "LucidBrowser"
    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
    }
    fun logError(tag: String, message: String, t: Throwable? = null) {
        Log.e(TAG, "[$tag] $message", t)
    }
}

/** Synchronous mirror of the browser's allowed redirects. */
object BrowserRedirectAllow {
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile private var words: List<String> = emptyList()

    fun now(): Set<String> = hosts

    fun set(list: List<String>) {
        val h = HashSet<String>(list.size)
        val w = ArrayList<String>(list.size)
        for (raw in list) {
            val e = normalizeEntry(raw)
            if (e.isBlank()) continue
            if (e.contains('.')) h.add(e) else w.add(e)
        }
        hosts = h
        words = w
    }

    fun normalizeEntry(raw: String): String =
        AdBlocker.normalizeDomain(raw).substringBefore(':').trim()

    fun allowsIn(url: String?, list: Iterable<String>): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim().lowercase()
        val host = runCatching { URI(u).host?.lowercase() }.getOrNull().orEmpty()
        for (raw in list) {
            val e = normalizeEntry(raw)
            if (e.isBlank()) continue
            if (e.contains('.')) {
                if (host.isNotEmpty() && (host == e || host.endsWith(".$e"))) return true
            } else if (u.contains(e)) {
                return true
            }
        }
        return false
    }

    fun allows(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim().lowercase()
        val host = runCatching { URI(u).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isNotEmpty() && AdBlocker.matches(host, hosts)) return true
        for (w in words) if (u.contains(w)) return true
        return false
    }
}

/** The TV build always uses the remote-first browser chrome. */
object BrowserTv {
    const val isTv: Boolean = true

    object MouseMode {
        @Volatile var enabled: Boolean = false
    }

    object Input {
        fun noteTouch() = Unit
        fun noteKey(event: android.view.KeyEvent) = Unit
    }
}

/** Translation is a phone-app feature; the TV browser leaves pages untouched. */
object BrowserTranslator {
    fun enable(providerId: String, enabled: Boolean) = Unit
}

/** The Cloudflare verifier lives in the main app; the browser alone needs no callback. */
object BrowserCloudflare {
    fun onVerifyViewClosed(host: String?) = Unit
}

/** Small image/translation HTTP client used by the browser preview and translator. */
object BrowserHttp {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val builder = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching {
            get(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()

    fun fetchStringCapped(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        readTimeoutSec: Long = 30,
    ): Result<String> = runCatching {
        val builder = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val call = if (readTimeoutSec > 30) {
            client.newBuilder().readTimeout(readTimeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
        } else {
            client
        }
        call.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            val body = r.body ?: return@use ""
            val cap = maxBytes.coerceAtLeast(1)
            val buf = ByteArray(minOf(cap, 64 * 1024))
            val out = java.io.ByteArrayOutputStream(minOf(cap, 1024 * 1024))
            var total = 0
            body.byteStream().use { input ->
                while (total < cap) {
                    val want = minOf(buf.size, cap - total)
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}

/** Cross-process Music strip. MainActivity binds the real controller. */
object BrowserMusic {
    data class Track(val title: String, val artist: String)
    data class State(val track: Track? = null, val playing: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var toggleAction: () -> Unit = {}
    @Volatile private var nextAction: () -> Unit = {}
    @Volatile private var previousAction: () -> Unit = {}

    fun bind(
        onToggle: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        this.toggleAction = onToggle
        this.nextAction = onNext
        this.previousAction = onPrevious
    }

    fun update(session: com.nuvio.tv.music.MusicSession) {
        val track = session.track
        _state.value = State(
            track = track?.let { Track(it.title, it.artist) },
            playing = session.playing,
        )
    }

    fun toggle() = toggleAction()
    fun next() = nextAction()
    fun previous() = previousAction()
}
