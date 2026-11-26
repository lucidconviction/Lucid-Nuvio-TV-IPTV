package com.nuvio.tv.ui.screens.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.nuvioFocusRing
import com.nuvio.tv.ui.screens.webapps.TvMarketApp
import com.nuvio.tv.ui.screens.webapps.TvSavedSite
import com.nuvio.tv.ui.screens.webapps.TvWebAppsStore
import com.nuvio.tv.ui.screens.webapps.TvWebMarket
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.math.roundToInt

private const val START_URL = "https://www.google.com"
private const val LONG_PRESS_MS = 500L
private const val SHIELD_LOG = "TvBrowserShield"

private const val TV_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val MEDIA_URL_RE = Regex(
    "\\.(m3u8|mp4|webm|mpd)(\\?|#|$)",
    RegexOption.IGNORE_CASE,
)

private fun isMediaUrl(url: String): Boolean =
    !url.startsWith("blob:") && !url.startsWith("data:") && MEDIA_URL_RE.containsMatchIn(url)

private fun emptyResourceResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private fun blockedNoContentResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

private data class BrowserTab(
    val id: Long,
    var url: String,
    var title: String = "New tab",
)

private data class BrowserMedia(
    val url: String,
    val headers: Map<String, String>,
    val label: String,
)

private data class RedirectPrompt(val url: String, val host: String)

/** Survives the browser screen being disposed while the Nuvio player is open. */
private object TvBrowserSession {
    @Volatile var lastUrl: String? = null
    @Volatile var autoPlayedUrl: String? = null
    @Volatile var autoPlayedAt: Long = 0L
}

/** Rank the page's media so ads, prerolls and trackers lose to the real stream. */
private fun mediaScore(
    media: BrowserMedia,
    domUrls: Set<String>,
    playingUrl: String?,
    pageHost: String?,
): Int {
    val url = media.url
    val lower = url.lowercase()
    var score = 0
    if (playingUrl != null && url == playingUrl) score += 500
    if (url in domUrls) score += 100
    score += when {
        lower.contains(".m3u8") -> 70
        lower.contains(".mpd") -> 65
        lower.contains(".mp4") -> 50
        lower.contains(".webm") -> 35
        else -> 10
    }
    if (lower.contains("master") || lower.contains("playlist") || lower.contains("manifest")) {
        score += 20
    }
    if (lower.contains("preroll") || lower.contains("midroll") || lower.contains("postroll") ||
        lower.contains("vast") || lower.contains("vmap") ||
        lower.contains("/ads/") || lower.contains("/ad/") ||
        lower.contains("popunder") || lower.contains("banner")
    ) {
        score -= 250
    }
    if (TvWebShield.matchesPatternUrl(url, pageHost, type = "media")) score -= 300
    if (TvWebShield.isBlocked(url, includeExtra = true)) score -= 500
    val host = TvWebShield.hostOf(url)
    if (host != null && pageHost != null && TvWebShield.sameSite(host, pageHost)) score += 10
    score += url.length.coerceAtMost(200) / 20
    return score
}

/** Held D-pad directions and the 60 fps pointer-movement coroutine. */
private class BrowserPointerState {
    val directions = LinkedHashSet<Key>()
    var job: Job? = null
    var startedAt: Long = 0L
    var lastFrameAt: Long = 0L
}

/** Values read by the WebView's background request thread. */
private class BrowserSessionState {
    @Volatile var pageUrl: String = START_URL
    @Volatile var lastUserActionAt: Long = 0L
    @Volatile var pendingUserUrl: String? = null
}

/**
 * TV-first WebView: D-pad moves an on-page cursor, OK clicks, long-press opens the browser
 * menu, and the DOM element under the cursor gets an accent outline. The browser is a normal
 * TV destination; media can be sent to Nuvio's player from the menu.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvBrowserScreen(
    onBack: () -> Unit,
    onPlayInPlayer: (url: String, title: String, headers: Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val browserPrefs = remember {
        context.getSharedPreferences("tv_browser", android.content.Context.MODE_PRIVATE)
    }
    val tokens = NuvioTheme.colors
    val accent = tokens.FocusRing
    val accentHex = "#%06X".format(0xFFFFFF and accent.toArgb())
    val initialUrl = remember { BrowserLaunch.take() ?: TvBrowserSession.lastUrl ?: START_URL }
    val tabs = remember { mutableStateListOf(BrowserTab(id = 1L, url = initialUrl)) }
    var activeTab by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(initialUrl) }
    var pageUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Browser") }
    var pointer by remember { mutableStateOf(Offset(520f, 360f)) }
    var menuOpen by remember { mutableStateOf(false) }
    var addressOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    var mediaItems by remember { mutableStateOf<List<BrowserMedia>>(emptyList()) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var marketOpen by remember { mutableStateOf(false) }
    var marketApps by remember { mutableStateOf<List<TvMarketApp>>(emptyList()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var mouseMode by remember { mutableStateOf(browserPrefs.getBoolean("mouse_mode", true)) }
    var centerHeld by remember { mutableStateOf(false) }
    var centerMoved by remember { mutableStateOf(false) }
    val pointerState = remember { BrowserPointerState() }
    var pageLoadId by remember { mutableIntStateOf(0) }
    var autoPlayHandledLoadId by remember { mutableIntStateOf(-1) }
    var appResumed by remember { mutableStateOf(true) }
    val lastHoverAt = remember { longArrayOf(0L) }
    var browserSettings by remember { mutableStateOf(TvBrowserSettingsStore.load(context)) }
    var redirectPrompt by remember { mutableStateOf<RedirectPrompt?>(null) }
    var blockedCount by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var updatingLists by remember { mutableStateOf(false) }
    val sessionState = remember {
        BrowserSessionState().apply {
            // The first page is a user-requested navigation. Initialising the current
            // host here stops the restored/first URL from looking like a cross-site
            // redirect and asking for permission to open itself.
            pageUrl = initialUrl
            pendingUserUrl = initialUrl
            lastUserActionAt = SystemClock.uptimeMillis()
        }
    }
    val liveSettings = remember { java.util.concurrent.atomic.AtomicReference(browserSettings) }
    val detectedMedia = remember {
        java.util.Collections.synchronizedMap(LinkedHashMap<String, Map<String, String>>())
    }
    val allowedRedirects = remember {
        java.util.Collections.synchronizedSet(TvBrowserSettingsStore.allowedRedirectHosts(context))
    }
    val blockedRedirects = remember {
        java.util.Collections.synchronizedSet(TvBrowserSettingsStore.blockedRedirectHosts(context))
    }
    val bookmarks = remember {
        mutableStateListOf<String>().apply {
            addAll(TvWebAppsStore.loadOrSeed(context).map { it.url })
        }
    }

    fun persistBookmarks() {
        val existing = TvWebAppsStore.load(context).associateBy { it.url }
        TvWebAppsStore.save(
            context,
            bookmarks.map { url ->
                existing[url] ?: TvSavedSite(
                    id = java.util.UUID.randomUUID().toString(),
                    name = TvWebShield.hostOf(url) ?: url,
                    url = url,
                )
            },
        )
    }
    var longPressFired by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val rootFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val tabsFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    val overlayOpen = menuOpen || addressOpen || tabsOpen || mediaOpen || bookmarksOpen ||
        marketOpen || settingsOpen || redirectPrompt != null

    fun refreshTab() {
        tabs.getOrNull(activeTab)?.let {
            it.url = pageUrl
            it.title = pageTitle
        }
    }

    fun injectFocusCss(view: WebView? = webView) {
        val target = view ?: return
        target.evaluateJavascript(
            """
            (function(){
              if (document.getElementById('nuvio-tv-focus-style')) return;
              var s=document.createElement('style');
              s.id='nuvio-tv-focus-style';
              s.innerHTML='.nuvio-tv-focus{outline:3px solid $accentHex !important;outline-offset:3px !important;box-shadow:0 0 0 6px ${accentHex}44 !important;}';
              (document.head||document.documentElement).appendChild(s);
            })()
            """.trimIndent(),
            null,
        )
    }

    fun injectCosmeticFilter(view: WebView? = webView) {
        val target = view ?: return
        if (!liveSettings.get().cosmeticFiltering) return
        target.evaluateJavascript(TvWebShield.cosmeticJavaScript, null)
    }

    fun highlightAtPointer(view: WebView? = webView) {
        val target = view ?: return
        val x = pointer.x.roundToInt()
        val y = pointer.y.roundToInt()
        target.evaluateJavascript(
            """
            (function(){
              var old=document.querySelectorAll('.nuvio-tv-focus');
              for(var i=0;i<old.length;i++) old[i].classList.remove('nuvio-tv-focus');
              var e=document.elementFromPoint($x,$y);
              if(e) e.classList.add('nuvio-tv-focus');
            })()
            """.trimIndent(),
            null,
        )
    }

    fun movePointerToPixels(x: Float, y: Float) {
        val maxX = configuration.screenWidthDp.toFloat() - 26f
        val maxY = configuration.screenHeightDp.toFloat() - 26f
        val dpX = x / density.density
        val dpY = y / density.density
        pointer = Offset(dpX.coerceIn(0f, maxX), dpY.coerceIn(0f, maxY))
    }

    fun scrollPage(deltaPx: Int) {
        val view = webView ?: return
        view.evaluateJavascript(
            """
            (function(){
              var d=$deltaPx;
              try { window.scrollBy({top:d,left:0,behavior:'smooth'}); }
              catch(err) { window.scrollBy(0,d); }
            })()
            """.trimIndent(),
            null,
        )
    }

    fun clickAtPointer() {
        val view = webView ?: return
        val width = configuration.screenWidthDp.toFloat()
        val height = configuration.screenHeightDp.toFloat()
        val centerY = height / 2f
        // D-pad or mouse clicks on the floating scroll buttons.
        if (pointer.x >= width - 72f) {
            if (pointer.y in (centerY - 71f)..(centerY - 5f)) {
                scrollPage(-(view.height * 4 / 5))
                return
            }
            if (pointer.y in (centerY + 5f)..(centerY + 71f)) {
                scrollPage(view.height * 4 / 5)
                return
            }
        }
        // The bottom toolbar is also a click target: OK on it opens the address bar.
        if (pointer.y >= height - 62f && pointer.x <= 760f) {
            addressOpen = true
            scope.launch { delay(100); runCatching { addressFocus.requestFocus() } }
            return
        }
        val x = pointer.x.roundToInt()
        val y = pointer.y.roundToInt()
        sessionState.lastUserActionAt = SystemClock.uptimeMillis()
        view.evaluateJavascript(
            """
            (function(){
              var e=document.elementFromPoint($x,$y);
              if(!e) return;
              try { e.focus(); } catch(err) {}
              try { e.click(); } catch(err) {}
              try {
                var v = e.tagName === 'VIDEO' ? e : (e.closest ? e.closest('video') : null);
                if (v) { v.setAttribute('playsinline',''); if (v.paused) v.play(); }
              } catch(err) {}
            })()
            """.trimIndent(),
            null,
        )
        scope.launch { delay(160); runCatching { rootFocus.requestFocus() } }
    }

    fun stopPointerMovement() {
        pointerState.job?.cancel()
        pointerState.job = null
        pointerState.directions.clear()
    }

    fun startPointerMovement() {
        if (pointerState.job?.isActive == true) return
        pointerState.startedAt = SystemClock.uptimeMillis()
        pointerState.lastFrameAt = pointerState.startedAt - 16L
        pointerState.job = scope.launch {
            val maxX = configuration.screenWidthDp.toFloat() - 20f
            val maxY = configuration.screenHeightDp.toFloat() - 20f
            while (pointerState.directions.isNotEmpty()) {
                val now = SystemClock.uptimeMillis()
                val dt = ((now - pointerState.lastFrameAt).coerceIn(0L, 50L)) / 1000f
                pointerState.lastFrameAt = now
                val held = ((now - pointerState.startedAt) / 700f).coerceIn(0f, 1f)
                val speed = 300f + 1_100f * held
                var dx = 0f
                var dy = 0f
                if (Key.DirectionLeft in pointerState.directions) dx -= 1f
                if (Key.DirectionRight in pointerState.directions) dx += 1f
                if (Key.DirectionUp in pointerState.directions) dy -= 1f
                if (Key.DirectionDown in pointerState.directions) dy += 1f
                if (dx != 0f && dy != 0f) {
                    dx *= 0.7071f
                    dy *= 0.7071f
                }
                if (dt > 0f) {
                    pointer = Offset(
                        (pointer.x + dx * speed * dt).coerceIn(0f, maxX),
                        (pointer.y + dy * speed * dt).coerceIn(0f, maxY),
                    )
                    if (now - lastHoverAt[0] >= 60L) {
                        lastHoverAt[0] = now
                        highlightAtPointer()
                    }
                }
                kotlinx.coroutines.delay(16)
            }
            pointerState.job = null
        }
    }

    fun mediaHeaders(
        url: String,
        referer: String = sessionState.pageUrl,
        captured: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        captured.forEach { (key, value) -> if (value.isNotBlank()) headers[key] = value }
        val keys = headers.keys.map { it.lowercase() }.toSet()
        fun put(key: String, value: String?) {
            if (!value.isNullOrBlank() && key.lowercase() !in keys) headers[key] = value
        }
        put("Referer", referer.takeIf { it.startsWith("http") })
        put(
            "Origin",
            runCatching {
                val u = URI(referer)
                "${u.scheme}://${u.host}"
            }.getOrNull(),
        )
        put("User-Agent", TV_USER_AGENT)
        put("Cookie", runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull())
        return headers
    }

    fun rememberDetectedMedia(url: String, headers: Map<String, String>) {
        if (!isMediaUrl(url)) return
        synchronized(detectedMedia) {
            if (!detectedMedia.containsKey(url)) detectedMedia[url] = headers
        }
    }

    fun userLoad(rawUrl: String) {
        val url = when {
            rawUrl.isBlank() -> return
            rawUrl == "about:blank" -> rawUrl
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            else -> "https://$rawUrl"
        }
        sessionState.lastUserActionAt = SystemClock.uptimeMillis()
        sessionState.pendingUserUrl = url
        address = url
        webView?.loadUrl(url)
    }

    fun loadAddress() {
        if (address.isBlank()) return
        addressOpen = false
        userLoad(address.trim())
        scope.launch { delay(150); rootFocus.requestFocus() }
    }

    fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        address = tabs[index].url
        userLoad(tabs[index].url)
        tabsOpen = false
        scope.launch { delay(120); rootFocus.requestFocus() }
    }

    fun addTab() {
        val newTab = BrowserTab(id = (tabs.maxOfOrNull { it.id } ?: 0L) + 1, url = "about:blank")
        tabs.add(newTab)
        activeTab = tabs.lastIndex
        address = ""
        webView?.loadUrl("about:blank")
        addressOpen = true
        tabsOpen = false
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(index)
        activeTab = activeTab.coerceAtMost(tabs.lastIndex)
        address = tabs[activeTab].url
        userLoad(tabs[activeTab].url)
    }

    fun allowRedirect(prompt: RedirectPrompt, always: Boolean) {
        if (always) {
            allowedRedirects.add(prompt.host)
            blockedRedirects.remove(prompt.host)
            TvBrowserSettingsStore.setAllowedRedirectHosts(context, allowedRedirects)
            TvBrowserSettingsStore.setBlockedRedirectHosts(context, blockedRedirects)
        }
        sessionState.pendingUserUrl = prompt.url
        sessionState.lastUserActionAt = SystemClock.uptimeMillis()
        redirectPrompt = null
        webView?.loadUrl(prompt.url)
    }

    fun blockRedirectHost(host: String) {
        blockedRedirects.add(host)
        allowedRedirects.remove(host)
        TvBrowserSettingsStore.setBlockedRedirectHosts(context, blockedRedirects)
        TvBrowserSettingsStore.setAllowedRedirectHosts(context, allowedRedirects)
        redirectPrompt = null
        notice = "Redirects to $host are blocked"
    }

    fun mediaCandidates(domUrls: List<String>, playingUrl: String?): List<BrowserMedia> {
        val merged = LinkedHashMap<String, BrowserMedia>()
        synchronized(detectedMedia) {
            detectedMedia.forEach { (url, captured) ->
                if (isMediaUrl(url)) {
                    merged[url] = BrowserMedia(
                        url = url,
                        headers = mediaHeaders(url, captured = captured),
                        label = TvWebShield.hostOf(url) ?: "Detected video",
                    )
                }
            }
        }
        domUrls.filter(::isMediaUrl).forEach { url ->
            if (!merged.containsKey(url)) {
                merged[url] = BrowserMedia(
                    url = url,
                    headers = mediaHeaders(url),
                    label = TvWebShield.hostOf(url) ?: "Video",
                )
            }
        }
        val domSet = domUrls.filter(::isMediaUrl).toSet()
        val pageHost = TvWebShield.hostOf(sessionState.pageUrl)
        return merged.values
            .map { media -> media to mediaScore(media, domSet, playingUrl, pageHost) }
            .filter { (_, score) -> score >= 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (media, _) -> media }
    }

    fun finishMediaScan(domUrls: List<String>) {
        mediaItems = mediaCandidates(domUrls, playingUrl = null)
        mediaOpen = true
    }

    fun scanMediaInfo(onReady: (List<String>, String?) -> Unit) {
        val view = webView ?: run {
            onReady(emptyList(), null)
            return
        }
        view.evaluateJavascript(
            """
            (function(){
              var urls=[];
              var playing='';
              document.querySelectorAll('video').forEach(function(v){
                var u=v.currentSrc||v.src||v.getAttribute('src')||'';
                if(u) urls.push(u);
                if(!v.paused && !v.ended && !playing) playing=u;
              });
              document.querySelectorAll('source').forEach(function(e){
                var u=e.src||e.getAttribute('src'); if(u) urls.push(u);
              });
              document.querySelectorAll('a[href]').forEach(function(a){
                var h=a.href; if(/\.(m3u8|mp4|webm|mpd)(\?|#|$)/i.test(h)) urls.push(h);
              });
              try{
                performance.getEntriesByType('resource').forEach(function(e){
                  var u=e.name; if(/\.(m3u8|mp4|webm|mpd)(\?|#|$)/i.test(u)) urls.push(u);
                });
              }catch(err){}
              return JSON.stringify({urls:Array.from(new Set(urls)), playing: playing});
            })()
            """.trimIndent(),
            ValueCallback { value ->
                val raw = runCatching {
                    (JSONTokener(value).nextValue() as? String) ?: value
                }.getOrNull() ?: ""
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val array = json?.optJSONArray("urls") ?: JSONArray()
                val urls = buildList {
                    for (i in 0 until array.length()) {
                        array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                onReady(urls, json?.optString("playing")?.takeIf { it.isNotBlank() })
            },
        )
    }

    fun scanMedia() {
        scanMediaInfo { urls, _ -> finishMediaScan(urls) }
    }

    fun considerAutoPlay(domUrls: List<String>, playingUrl: String?) {
        val mode = liveSettings.get().autoPlayMode
        if (mode == TvAutoPlayMode.Off) return
        if (!appResumed || overlayOpen) return
        if (autoPlayHandledLoadId == pageLoadId) return
        val thisUrl = sessionState.pageUrl
        if (TvBrowserSession.autoPlayedUrl == thisUrl &&
            SystemClock.uptimeMillis() - TvBrowserSession.autoPlayedAt < 30_000L
        ) {
            return
        }
        val candidates = mediaCandidates(domUrls, playingUrl)
        if (candidates.isEmpty()) return
        autoPlayHandledLoadId = pageLoadId
        TvBrowserSession.autoPlayedUrl = thisUrl
        TvBrowserSession.autoPlayedAt = SystemClock.uptimeMillis()
        if (mode == TvAutoPlayMode.Ask) {
            mediaItems = candidates
            mediaOpen = true
            notice = "Choose a video to play in Nuvio"
        } else {
            val best = candidates.first()
            webView?.evaluateJavascript(
                "(function(){document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}});})()",
                null,
            )
            notice = "Opening ${best.label} in Nuvio"
            onPlayInPlayer(best.url, pageTitle.ifBlank { "Web video" }, best.headers)
        }
    }

    /**
     * The shared redirect decision for BrowserClient's shouldOverrideUrlLoading and
     * shouldInterceptRequest. Returning true cancels the navigation before the target loads.
     */
    fun shouldBlockRedirect(url: String, hasGesture: Boolean): Boolean {
        if (url == "about:blank") return false
        if (TvWebShield.isCloudflareInfra(url)) return false
        if (isMediaUrl(url)) return false
        val host = TvWebShield.hostOf(url) ?: return false
        val currentHost = TvWebShield.hostOf(sessionState.pageUrl)
        val config = liveSettings.get()
        android.util.Log.d(
            SHIELD_LOG,
            "check url=$url host=$host current=$currentHost policy=${config.redirectPolicy} " +
                "gesture=$hasGesture pending=${sessionState.pendingUserUrl} " +
                "ageMs=${SystemClock.uptimeMillis() - sessionState.lastUserActionAt}",
        )
        if (host in blockedRedirects) {
            android.util.Log.d(SHIELD_LOG, "block host rule $host")
            webView?.post { notice = "Blocked redirect to $host" }
            return true
        }
        val pending = sessionState.pendingUserUrl
        if (pending != null && pending == url) {
            sessionState.pendingUserUrl = null
            android.util.Log.d(SHIELD_LOG, "allow pending $url")
            return false
        }
        if (host in allowedRedirects || TvWebShield.isAlwaysAllowedRedirect(host)) {
            android.util.Log.d(SHIELD_LOG, "allow host rule $host")
            return false
        }
        if (currentHost != null && TvWebShield.sameSite(host, currentHost)) {
            android.util.Log.d(SHIELD_LOG, "allow same-site $host / $currentHost")
            return false
        }
        if (SystemClock.uptimeMillis() - sessionState.lastUserActionAt <= 2_500L) {
            android.util.Log.d(SHIELD_LOG, "allow recent gesture window $url")
            return false
        }
        if (hasGesture) {
            android.util.Log.d(SHIELD_LOG, "allow gesture flag $url")
            return false
        }
        if (config.redirectPolicy == TvRedirectPolicy.Allow) {
            android.util.Log.d(SHIELD_LOG, "allow policy $url")
            return false
        }
        if (TvWebShield.isAdNavigation(url, currentHost)) {
            android.util.Log.d(SHIELD_LOG, "block ad navigation $url")
            webView?.post {
                blockedCount += 1
                notice = "Shield blocked an ad redirect to $host"
            }
            return true
        }
        when (config.redirectPolicy) {
            TvRedirectPolicy.AlwaysBlock -> {
                android.util.Log.d(SHIELD_LOG, "block always $url")
                webView?.post { notice = "Blocked redirect to $host" }
                return true
            }
            TvRedirectPolicy.Ask -> {
                android.util.Log.d(SHIELD_LOG, "ask prompt $url")
                webView?.post { redirectPrompt = RedirectPrompt(url, host) }
                return true
            }
            TvRedirectPolicy.Allow -> return false
        }
    }

    fun openMenu() {
        stopPointerMovement()
        menuOpen = true
        scope.launch { delay(100); runCatching { menuFocus.requestFocus() } }
    }

    fun closeOverlays() {
        menuOpen = false
        addressOpen = false
        tabsOpen = false
        mediaOpen = false
        bookmarksOpen = false
        marketOpen = false
        settingsOpen = false
        redirectPrompt = null
        centerHeld = false
        centerMoved = false
        stopPointerMovement()
        scope.launch { delay(100); runCatching { rootFocus.requestFocus() } }
    }

    BackHandler {
        when {
            addressOpen -> { addressOpen = false; closeOverlays() }
            tabsOpen -> { tabsOpen = false; closeOverlays() }
            mediaOpen -> { mediaOpen = false; closeOverlays() }
            bookmarksOpen -> { bookmarksOpen = false; closeOverlays() }
            marketOpen -> { marketOpen = false; closeOverlays() }
            settingsOpen -> { settingsOpen = false; closeOverlays() }
            redirectPrompt != null -> { redirectPrompt = null; closeOverlays() }
            menuOpen -> { menuOpen = false; closeOverlays() }
            webView?.canGoBack() == true -> {
                sessionState.lastUserActionAt = SystemClock.uptimeMillis()
                webView?.goBack()
                scope.launch { delay(250); rootFocus.requestFocus() }
            }
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        liveSettings.set(browserSettings)
        TvWebShield.load(context)
        delay(120)
        runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(browserSettings) {
        liveSettings.set(browserSettings)
        TvBrowserSettingsStore.save(context, browserSettings)
        webView?.settings?.javaScriptCanOpenWindowsAutomatically = !browserSettings.popupProtection
        if (browserSettings.cosmeticFiltering) injectCosmeticFilter()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    appResumed = true
                    webView?.let {
                        runCatching { it.resumeTimers() }
                        runCatching { it.onResume() }
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    appResumed = false
                    webView?.let {
                        runCatching {
                            it.evaluateJavascript(
                                "(function(){document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}});})()",
                                null,
                            )
                        }
                        runCatching { it.onPause() }
                        runCatching { it.pauseTimers() }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(overlayOpen, appResumed, webView) {
        val view = webView
        if (view != null) {
            if (overlayOpen || !appResumed) {
                // Freeze the page while the browser chrome is up or the app is in the
                // background: scripts, timers, redirects, popups and video all stop.
                runCatching {
                    view.evaluateJavascript(
                        "(function(){document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}});})()",
                        null,
                    )
                }
                runCatching { view.onPause() }
                runCatching { view.pauseTimers() }
            } else {
                runCatching { view.resumeTimers() }
                runCatching { view.onResume() }
            }
            // The WebView must never own D-pad focus while the virtual cursor is
            // active: Chromium would swallow the keys before the menu/pointer sees
            // them. Touch and mouse clicks still reach the page.
            view.isFocusable = false
            view.isFocusableInTouchMode = false
        }
        if (!overlayOpen) {
            delay(80)
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            notice = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.Background)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                val centerKey = event.key == Key.DirectionCenter || event.key == Key.Enter
                // A long press owns the whole OK cycle. The repeats while holding and the
                // final key-up are consumed here so the freshly focused menu button cannot
                // activate from the same hold and close the menu straight away.
                if (centerHeld && centerKey) {
                    if (!down) {
                        centerHeld = false
                        longPressJob?.cancel()
                        longPressJob = null
                        if (!longPressFired && !centerMoved) clickAtPointer()
                        longPressFired = false
                        centerMoved = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (overlayOpen) return@onPreviewKeyEvent false
                val directionKey = event.key in listOf(
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                )
                when {
                    directionKey -> {
                        if (down) {
                            if (centerHeld) {
                                // Moving while OK is held cancels the menu timer: hold + move
                                // is a drag gesture, not a request for the menu.
                                longPressJob?.cancel()
                                longPressJob = null
                                centerMoved = true
                            }
                            if (pointerState.directions.isEmpty()) {
                                pointerState.startedAt = SystemClock.uptimeMillis()
                            }
                            pointerState.directions += event.key
                            startPointerMovement()
                        } else {
                            pointerState.directions -= event.key
                            if (pointerState.directions.isEmpty()) {
                                pointerState.job?.cancel()
                                pointerState.job = null
                            }
                        }
                        true
                    }
                    down && centerKey -> {
                        centerHeld = true
                        centerMoved = false
                        if (longPressJob?.isActive != true) {
                            longPressFired = false
                            longPressJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                longPressFired = true
                                openMenu()
                            }
                        }
                        true
                    }
                    down && event.key == Key.Menu -> {
                        openMenu()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = TV_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val req = request ?: return null
                            val url = req.url.toString()
                            if (isMediaUrl(url) && !TvWebShield.isBlocked(url, includeExtra = true)) {
                                val captured = LinkedHashMap<String, String>()
                                req.requestHeaders?.forEach { (key, value) ->
                                    if (key.equals("User-Agent", true) || key.equals("Referer", true) ||
                                        key.equals("Origin", true) || key.equals("Cookie", true)
                                    ) {
                                        captured[key] = value
                                    }
                                }
                                rememberDetectedMedia(url, mediaHeaders(url, captured = captured))
                            }
                            if (req.isForMainFrame) {
                                return if (shouldBlockRedirect(url, req.hasGesture())) {
                                    blockedNoContentResponse()
                                } else {
                                    null
                                }
                            }
                            val config = liveSettings.get()
                            if (!config.adBlockEnabled) return null
                            val pageHost = TvWebShield.hostOf(pageUrl)
                            val host = req.url.host?.lowercase()
                            if (host != null && TvWebShield.isAlwaysAllowedHost(host)) return null
                            val type = TvWebShield.requestType(req)
                            val sameParty = host != null && pageHost != null &&
                                TvWebShield.sameSite(host, pageHost)
                            val blocked = runCatching {
                                (!sameParty && TvWebShield.isBlocked(url, includeExtra = true)) ||
                                    TvWebShield.matchesPatternUrl(url, pageHost, type)
                            }.getOrDefault(false)
                            if (blocked) {
                                view?.post { blockedCount += 1 }
                                return emptyResourceResponse()
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageLoadId += 1
                            url?.let { newUrl ->
                                val oldUrl = sessionState.pageUrl
                                if (oldUrl != newUrl && oldUrl.substringBefore('#') != newUrl.substringBefore('#')) {
                                    // Do not carry a previous page's streams into this page's
                                    // auto-play or Play in Nuvio candidate list.
                                    synchronized(detectedMedia) { detectedMedia.clear() }
                                    mediaItems = emptyList()
                                }
                                sessionState.pageUrl = newUrl
                                TvBrowserSession.lastUrl = newUrl
                                pageUrl = newUrl
                                address = newUrl
                                refreshTab()
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let {
                                sessionState.pageUrl = it
                                TvBrowserSession.lastUrl = it
                                pageUrl = it
                                address = it
                                refreshTab()
                            }
                            view?.let {
                                injectFocusCss(it)
                                injectCosmeticFilter(it)
                            }
                            scope.launch { delay(120); runCatching { rootFocus.requestFocus() } }
                            if (liveSettings.get().autoPlayMode != TvAutoPlayMode.Off) {
                                val loadId = pageLoadId
                                scope.launch {
                                    delay(3_000)
                                    if (loadId != pageLoadId) return@launch
                                    scanMediaInfo { urls, playing ->
                                        if (loadId == pageLoadId) considerAutoPlay(urls, playing)
                                    }
                                }
                                scope.launch {
                                    delay(7_000)
                                    if (loadId != pageLoadId) return@launch
                                    scanMediaInfo { urls, playing ->
                                        if (loadId == pageLoadId) considerAutoPlay(urls, playing)
                                    }
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val req = request ?: return false
                            if (!req.isForMainFrame) return false
                            return shouldBlockRedirect(req.url.toString(), req.hasGesture())
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.takeIf { it.isNotBlank() }?.let {
                                pageTitle = it
                                refreshTab()
                            }
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean = false

                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?,
                        ): Boolean {
                            // Page-load alerts are a common popup-ad tactic; accept them silently.
                            if (SystemClock.uptimeMillis() - sessionState.lastUserActionAt > 2_500L) {
                                result?.confirm()
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            update = { view ->
                if (webView !== view) webView = view
            },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
                if (webView === view) webView = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom address / status toolbar.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = tokens.TextPrimary, modifier = Modifier.size(16.dp))
            Text(
                pageTitle.ifBlank { "Browser" },
                color = tokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Text(
                pageUrl,
                color = tokens.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (browserSettings.adBlockEnabled && blockedCount > 0) {
                    "Shield $blockedCount"
                } else {
                    "Hold OK for menu"
                },
                color = tokens.TextTertiary,
                fontSize = 10.sp,
            )
        }

        notice?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(message, color = tokens.TextPrimary, fontSize = 12.sp)
            }
        }

        if (mouseMode && !overlayOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                when (event.type) {
                                    PointerEventType.Move -> {
                                        val position = event.changes.firstOrNull()?.position ?: continue
                                        movePointerToPixels(position.x, position.y)
                                        val now = SystemClock.uptimeMillis()
                                        if (now - lastHoverAt[0] >= 60L) {
                                            lastHoverAt[0] = now
                                            highlightAtPointer()
                                        }
                                    }
                                    PointerEventType.Press -> {
                                        val position = event.changes.firstOrNull()?.position ?: continue
                                        movePointerToPixels(position.x, position.y)
                                        clickAtPointer()
                                    }
                                    PointerEventType.Scroll -> {
                                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        if (delta != 0f) {
                                            scrollPage((delta * 120f).roundToInt())
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }

        if (!overlayOpen) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(pointer.x.roundToInt(), pointer.y.roundToInt()) }
                    .size(30.dp)
                    .border(2.dp, accent, CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrowserScrollButton(up = true) {
                    scrollPage(-(webView?.height ?: 0) * 4 / 5)
                }
                BrowserScrollButton(up = false) {
                    scrollPage((webView?.height ?: 0) * 4 / 5)
                }
            }
        }

        if (menuOpen) {
            BrowserMenu(
                focusRequester = menuFocus,
                onDismiss = { closeOverlays() },
                onBackNavigation = {
                    sessionState.lastUserActionAt = SystemClock.uptimeMillis()
                    webView?.goBack()
                    closeOverlays()
                },
                onForward = {
                    sessionState.lastUserActionAt = SystemClock.uptimeMillis()
                    webView?.goForward()
                    closeOverlays()
                },
                onReload = {
                    sessionState.lastUserActionAt = SystemClock.uptimeMillis()
                    webView?.reload()
                    closeOverlays()
                },
                onScrollUp = {
                    scrollPage(-(webView?.height ?: 0) * 3 / 4)
                    closeOverlays()
                },
                onScrollDown = {
                    scrollPage((webView?.height ?: 0) * 3 / 4)
                    closeOverlays()
                },
                onTabs = { menuOpen = false; tabsOpen = true; scope.launch { delay(80); runCatching { tabsFocus.requestFocus() } } },
                onBookmarks = { menuOpen = false; bookmarksOpen = true },
                onMarket = {
                    menuOpen = false
                    marketOpen = true
                    if (marketApps.isEmpty()) marketApps = TvWebMarket.load(context)
                },
                onHome = {
                    menuOpen = false
                    userLoad(START_URL)
                    closeOverlays()
                },
                onAddress = { menuOpen = false; addressOpen = true },
                onPlayInPlayer = { menuOpen = false; scanMedia() },
                onShield = {
                    menuOpen = false
                    settingsOpen = true
                    scope.launch { delay(100); runCatching { settingsFocus.requestFocus() } }
                },
                mouseMode = mouseMode,
                onToggleMouse = {
                    val next = !mouseMode
                    mouseMode = next
                    browserPrefs.edit().putBoolean("mouse_mode", next).apply()
                },
            )
        }

        if (addressOpen) {
            AddressOverlay(
                value = address,
                focusRequester = addressFocus,
                onValueChange = { address = it },
                onGo = { loadAddress() },
                onDismiss = { addressOpen = false; closeOverlays() },
            )
        }

        if (tabsOpen) {
            TabsOverlay(
                tabs = tabs,
                active = activeTab,
                focusRequester = tabsFocus,
                onSelect = { switchTab(it) },
                onNew = { addTab() },
                onClose = { closeTab(it) },
                onDismiss = { tabsOpen = false; closeOverlays() },
            )
        }

        if (mediaOpen) {
            MediaOverlay(
                media = mediaItems,
                onPlay = { media ->
                    mediaOpen = false
                    // Do not leave the page's own player running under Nuvio's player.
                    webView?.evaluateJavascript(
                        "(function(){document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}});})()",
                        null,
                    )
                    onPlayInPlayer(
                        media.url,
                        pageTitle.ifBlank { "Web video" },
                        media.headers,
                    )
                },
                onDismiss = { mediaOpen = false; closeOverlays() },
            )
        }

        if (settingsOpen) {
            ShieldSettingsOverlay(
                settings = browserSettings,
                blockedCount = blockedCount,
                updating = updatingLists,
                focusRequester = settingsFocus,
                onToggleAdBlock = { browserSettings = browserSettings.copy(adBlockEnabled = it) },
                onToggleCosmetic = { browserSettings = browserSettings.copy(cosmeticFiltering = it) },
                onTogglePopup = { browserSettings = browserSettings.copy(popupProtection = it) },
                onPolicy = { browserSettings = browserSettings.copy(redirectPolicy = it) },
                onAutoPlayMode = { browserSettings = browserSettings.copy(autoPlayMode = it) },
                onUpdateLists = {
                    if (!updatingLists) {
                        updatingLists = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { TvWebShield.update(context) } }
                            updatingLists = false
                            notice = "Shield lists updated (${TvWebShield.ruleCount} rules)"
                        }
                    }
                },
                onDismiss = { settingsOpen = false; closeOverlays() },
            )
        }

        if (marketOpen) {
            BrowserMarketOverlay(
                apps = marketApps,
                savedUrls = bookmarks.toSet(),
                onOpen = { url ->
                    marketOpen = false
                    userLoad(url)
                    closeOverlays()
                },
                onToggleSaved = { url ->
                    if (url in bookmarks) {
                        bookmarks.remove(url)
                    } else if (url.isNotBlank()) {
                        bookmarks.add(url)
                    }
                    persistBookmarks()
                },
                onDismiss = { marketOpen = false; closeOverlays() },
            )
        }

        if (bookmarksOpen) {
            BookmarksOverlay(
                bookmarks = bookmarks,
                onOpen = { url ->
                    bookmarksOpen = false
                    userLoad(url)
                    closeOverlays()
                },
                onAddCurrent = {
                    val url = pageUrl
                    if (url.isNotBlank() && url !in bookmarks) {
                        bookmarks.add(url)
                        persistBookmarks()
                        notice = "Saved to Web-Apps"
                    }
                },
                onRemove = { url ->
                    bookmarks.remove(url)
                    persistBookmarks()
                },
                onDismiss = { bookmarksOpen = false; closeOverlays() },
            )
        }

        redirectPrompt?.let { prompt ->
            RedirectPromptOverlay(
                prompt = prompt,
                onAllowOnce = { allowRedirect(prompt, always = false) },
                onAlwaysAllow = { allowRedirect(prompt, always = true) },
                onBlockHost = { blockRedirectHost(prompt.host) },
                onDismiss = { redirectPrompt = null; closeOverlays() },
            )
        }

    }
}

@Composable
private fun BrowserMenu(
    focusRequester: FocusRequester,
    onDismiss: () -> Unit,
    onBackNavigation: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onTabs: () -> Unit,
    onAddress: () -> Unit,
    onPlayInPlayer: () -> Unit,
    onBookmarks: () -> Unit,
    onHome: () -> Unit,
    onMarket: () -> Unit,
    onShield: () -> Unit,
    mouseMode: Boolean,
    onToggleMouse: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.52f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Browser", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text("D-pad moves the pointer · OK selects · hold OK for this menu", color = tokens.TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowserMenuButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.weight(1f).focusRequester(focusRequester), onBackNavigation)
                    BrowserMenuButton("Forward", Icons.AutoMirrored.Filled.ArrowForward, Modifier.weight(1f), onForward)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowserMenuButton("Reload", Icons.Default.Refresh, Modifier.weight(1f), onReload)
                    BrowserMenuButton("Scroll up", Icons.Default.KeyboardArrowUp, Modifier.weight(1f), onScrollUp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowserMenuButton("Scroll down", Icons.Default.KeyboardArrowDown, Modifier.weight(1f), onScrollDown)
                    BrowserMenuButton("Tabs", Icons.Default.Tab, Modifier.weight(1f), onTabs)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowserMenuButton("Address", Icons.Default.Search, Modifier.weight(1f), onAddress)
                    BrowserMenuButton("Play in player", Icons.Default.PlayArrow, Modifier.weight(1f), onPlayInPlayer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrowserMenuButton("Bookmarks", Icons.Default.Language, Modifier.weight(1f), onBookmarks)
                    BrowserMenuButton("Market", Icons.Default.Add, Modifier.weight(1f), onMarket)
                }
                BrowserMenuButton(
                    "Shield & browser settings",
                    Icons.Default.Settings,
                    Modifier.fillMaxWidth(),
                    onShield,
                )
                BrowserMenuButton(
                    if (mouseMode) "Mouse: ON" else "Mouse: OFF",
                    Icons.Default.Mouse,
                    Modifier.fillMaxWidth(),
                    onToggleMouse,
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Text("Close menu")
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun BrowserScrollButton(up: Boolean, onClick: () -> Unit) {
    val tokens = NuvioTheme.colors
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(54.dp)
            .height(66.dp)
            .nuvioFocusRing(RoundedCornerShape(14.dp)),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.40f),
            contentColor = Color.White.copy(alpha = 0.92f),
            focusedContainerColor = tokens.FocusBackground.copy(alpha = 0.70f),
            focusedContentColor = Color.White,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (up) "Scroll up" else "Scroll down",
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun BrowserMenuButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.nuvioFocusRing(RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddressOverlay(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    var query by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val goFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Address", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; onValueChange(it) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = tokens.TextPrimary, fontSize = 16.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                runCatching { goFocus.requestFocus() }
                                true
                            } else {
                                false
                            }
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.BackgroundCard)
                        .then(if (focused) Modifier.border(2.dp, tokens.FocusRing, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                    decorationBox = { inner ->
                        if (query.isBlank()) Text("https://", color = tokens.TextTertiary)
                        inner()
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onGo,
                        modifier = Modifier.focusRequester(goFocus).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) {
                        Text("Go")
                    }
                    Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun TabsOverlay(
    tabs: List<BrowserTab>,
    active: Int,
    focusRequester: FocusRequester,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onClose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .height(600.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tabs", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tabs.indices.toList(), key = { tabs[it].id }) { index ->
                        val tab = tabs[index]
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onSelect(index) },
                                modifier = Modifier.weight(1f).then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                    .nuvioFocusRing(RoundedCornerShape(12.dp)),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (index == active) tokens.FocusBackground else tokens.BackgroundCard,
                                    contentColor = tokens.TextPrimary,
                                    focusedContainerColor = tokens.FocusBackground,
                                    focusedContentColor = tokens.TextPrimary,
                                ),
                            ) {
                                Icon(Icons.Default.Tab, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tab.title.ifBlank { tab.url }.ifBlank { "New tab" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { onClose(index) },
                                enabled = tabs.size > 1,
                                modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp)),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close tab", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onNew, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New tab")
                    }
                    Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Text("Close")
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun MediaOverlay(
    media: List<BrowserMedia>,
    onPlay: (BrowserMedia) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .height(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Play in Nuvio", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Detected videos and playlists from this page. Choosing one opens the normal Nuvio player with the page Referer and cookies.",
                    color = tokens.TextSecondary,
                    fontSize = 12.sp,
                )
                if (media.isEmpty()) {
                    Text(
                        "No video or playlist link was found. Start the video once in the browser, then open Play in Nuvio again.",
                        color = tokens.TextSecondary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(media, key = { it.url }) { item ->
                            Button(
                                onClick = { onPlay(item) },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (item == media.first()) Modifier.focusRequester(first) else Modifier)
                                    .nuvioFocusRing(RoundedCornerShape(12.dp)),
                                colors = ButtonDefaults.colors(
                                    containerColor = tokens.BackgroundCard,
                                    contentColor = tokens.TextPrimary,
                                    focusedContainerColor = tokens.FocusBackground,
                                    focusedContentColor = tokens.TextPrimary,
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.label,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        item.url,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Text("Close")
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun ShieldSettingsOverlay(
    settings: TvBrowserSettings,
    blockedCount: Int,
    updating: Boolean,
    focusRequester: FocusRequester,
    onToggleAdBlock: (Boolean) -> Unit,
    onToggleCosmetic: (Boolean) -> Unit,
    onTogglePopup: (Boolean) -> Unit,
    onPolicy: (TvRedirectPolicy) -> Unit,
    onAutoPlayMode: (TvAutoPlayMode) -> Unit,
    onUpdateLists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    val rules = TvWebShield.ruleCount
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Shield & browser settings", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${if (settings.adBlockEnabled) "Ad blocking on" else "Ad blocking off"} · $rules rules · $blockedCount requests blocked this session",
                    color = tokens.TextSecondary,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldToggle(
                        label = "uBlock / AdGuard-style ad blocking",
                        value = settings.adBlockEnabled,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    ) { onToggleAdBlock(!settings.adBlockEnabled) }
                    ShieldToggle(
                        label = "Hide ad placeholders",
                        value = settings.cosmeticFiltering,
                        modifier = Modifier.weight(1f),
                    ) { onToggleCosmetic(!settings.cosmeticFiltering) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldToggle(
                        label = "Ask before cross-site popups",
                        value = settings.popupProtection,
                        modifier = Modifier.weight(1f),
                    ) { onTogglePopup(!settings.popupProtection) }
                    Button(
                        onClick = onUpdateLists,
                        enabled = !updating,
                        modifier = Modifier.weight(1f).height(50.dp).nuvioFocusRing(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.colors(
                            containerColor = tokens.BackgroundCard,
                            contentColor = tokens.TextPrimary,
                            focusedContainerColor = tokens.FocusBackground,
                            focusedContentColor = tokens.TextPrimary,
                        ),
                    ) {
                        Text(
                            if (updating) "Updating filter lists…" else "Update filter lists",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("Automatic page & app redirects", color = tokens.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolicyButton(
                        label = "Always block",
                        selected = settings.redirectPolicy == TvRedirectPolicy.AlwaysBlock,
                        modifier = Modifier.weight(1f),
                    ) { onPolicy(TvRedirectPolicy.AlwaysBlock) }
                    PolicyButton(
                        label = "Ask",
                        selected = settings.redirectPolicy == TvRedirectPolicy.Ask,
                        modifier = Modifier.weight(1f),
                    ) { onPolicy(TvRedirectPolicy.Ask) }
                    PolicyButton(
                        label = "Allow",
                        selected = settings.redirectPolicy == TvRedirectPolicy.Allow,
                        modifier = Modifier.weight(1f),
                    ) { onPolicy(TvRedirectPolicy.Allow) }
                }
                Text(
                    "Automatic cross-site redirects are cancelled before the target page loads. Ask offers once, always and block choices; same-site hops, logins and Cloudflare challenges stay allowed.",
                    color = tokens.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text("Auto-play detected videos in Nuvio", color = tokens.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PolicyButton(
                        label = "Off",
                        selected = settings.autoPlayMode == TvAutoPlayMode.Off,
                        modifier = Modifier.weight(1f),
                    ) { onAutoPlayMode(TvAutoPlayMode.Off) }
                    PolicyButton(
                        label = "Ask me",
                        selected = settings.autoPlayMode == TvAutoPlayMode.Ask,
                        modifier = Modifier.weight(1f),
                    ) { onAutoPlayMode(TvAutoPlayMode.Ask) }
                    PolicyButton(
                        label = "Best match",
                        selected = settings.autoPlayMode == TvAutoPlayMode.Best,
                        modifier = Modifier.weight(1f),
                    ) { onAutoPlayMode(TvAutoPlayMode.Best) }
                }
                Text(
                    "Ask opens the detected videos so you can choose. Best match ranks streams, drops ad and preroll paths, and opens the strongest candidate automatically.",
                    color = tokens.TextSecondary,
                    fontSize = 11.sp,
                )
                Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Text("Close")
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
    }
}

@Composable
private fun ShieldToggle(
    label: String,
    value: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp).nuvioFocusRing(RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (value) tokens.FocusBackground else tokens.BackgroundCard,
            contentColor = tokens.TextPrimary,
            focusedContainerColor = tokens.FocusBackground,
            focusedContentColor = tokens.TextPrimary,
        ),
    ) {
        Text(
            "$label: ${if (value) "ON" else "OFF"}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PolicyButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp).nuvioFocusRing(RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) tokens.TextPrimary else tokens.BackgroundCard,
            contentColor = if (selected) tokens.TextInverse else tokens.TextPrimary,
            focusedContainerColor = tokens.FocusBackground,
            focusedContentColor = tokens.TextPrimary,
        ),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RedirectPromptOverlay(
    prompt: RedirectPrompt,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onBlockHost: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.68f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Redirect blocked", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "This page tried to open another site automatically.",
                    color = tokens.TextSecondary,
                )
                Text(prompt.host, color = tokens.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    prompt.url,
                    color = tokens.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAllowOnce,
                        modifier = Modifier.weight(1f).focusRequester(first).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) { Text("Open once", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                        onClick = onAlwaysAllow,
                        modifier = Modifier.weight(1f).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) { Text("Always allow ${prompt.host}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBlockHost,
                        modifier = Modifier.weight(1f).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) { Text("Always block ${prompt.host}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).nuvioFocusRing(RoundedCornerShape(12.dp)),
                    ) { Text("Stay here") }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun BookmarksOverlay(
    bookmarks: List<String>,
    onOpen: (String) -> Unit,
    onAddCurrent: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .height(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bookmarks", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text("Saved pages", color = tokens.TextSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(bookmarks, key = { it }) { url ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onOpen(url) },
                                modifier = Modifier.weight(1f)
                                    .then(if (url == bookmarks.firstOrNull()) Modifier.focusRequester(first) else Modifier)
                                    .nuvioFocusRing(RoundedCornerShape(12.dp)),
                                colors = ButtonDefaults.colors(
                                    containerColor = tokens.BackgroundCard,
                                    contentColor = tokens.TextPrimary,
                                    focusedContainerColor = tokens.FocusBackground,
                                    focusedContentColor = tokens.TextPrimary,
                                ),
                            ) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(onClick = { onRemove(url) }, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                                Icon(Icons.Default.Close, contentDescription = "Remove bookmark", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAddCurrent, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bookmark this page")
                    }
                    Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                        Text("Close")
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
}


@Composable
private fun BrowserMarketOverlay(
    apps: List<TvMarketApp>,
    savedUrls: Set<String>,
    onOpen: (String) -> Unit,
    onToggleSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    val first = remember { FocusRequester() }
    var groupId by remember { mutableStateOf(TvWebMarket.groups.first().id) }
    var categoryId by remember {
        mutableStateOf(TvWebMarket.groups.first().categories.first().id)
    }
    val group = TvWebMarket.groups.firstOrNull { it.id == groupId } ?: TvWebMarket.groups.first()
    val category = group.categories.firstOrNull { it.id == categoryId } ?: group.categories.first()
    val visible = apps.filter { it.categoryId == category.id }
    Box(Modifier.fillMaxSize().focusGroup().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 1200.dp)
                .height(720.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Web app market", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Safe-for-work picks from the mobile market. Bookmark one to add it to Web-Apps.",
                    color = tokens.TextSecondary,
                    fontSize = 12.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TvWebMarket.groups, key = { it.id }) { marketGroup ->
                        BrowserMarketChip(
                            label = marketGroup.label,
                            selected = marketGroup.id == group.id,
                        ) {
                            groupId = marketGroup.id
                            categoryId = marketGroup.categories.first().id
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(group.categories, key = { it.id }) { marketCategory ->
                        BrowserMarketChip(
                            label = marketCategory.label,
                            selected = marketCategory.id == category.id,
                        ) {
                            categoryId = marketCategory.id
                        }
                    }
                }
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No apps in this category.", color = tokens.TextSecondary)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 240.dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        gridItems(visible, key = { it.url }) { app ->
                            val saved = app.url in savedUrls
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Button(
                                    onClick = { onOpen(app.url) },
                                    modifier = Modifier.weight(1f).height(76.dp)
                                        .then(if (app == visible.first()) Modifier.focusRequester(first) else Modifier)
                                        .nuvioFocusRing(RoundedCornerShape(12.dp)),
                                    colors = ButtonDefaults.colors(
                                        containerColor = tokens.BackgroundCard,
                                        contentColor = tokens.TextPrimary,
                                        focusedContainerColor = tokens.FocusBackground,
                                        focusedContentColor = tokens.TextPrimary,
                                    ),
                                ) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text(TvWebMarket.hostOf(app.url), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
                                    }
                                }
                                Button(
                                    onClick = { onToggleSaved(app.url) },
                                    modifier = Modifier.height(76.dp).nuvioFocusRing(RoundedCornerShape(12.dp)),
                                ) {
                                    Icon(
                                        if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        contentDescription = if (saved) "Remove ${app.name} from Web-Apps" else "Save ${app.name}",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Text("Close")
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
}


@Composable
private fun BrowserMarketChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Button(
        onClick = onClick,
        modifier = Modifier.height(38.dp).nuvioFocusRing(RoundedCornerShape(50)),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) tokens.TextPrimary else tokens.BackgroundCard,
            contentColor = if (selected) tokens.TextInverse else tokens.TextPrimary,
            focusedContainerColor = tokens.FocusBackground,
            focusedContentColor = tokens.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
