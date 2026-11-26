package com.nuvio.tv.ui.screens.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.nuvioFocusRing
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.math.roundToInt

private const val START_URL = "https://www.google.com"
private const val POINTER_STEP = 30f
private const val LONG_PRESS_MS = 600L

private data class BrowserTab(
    val id: Long,
    var url: String,
    var title: String = "New tab",
)

/**
 * TV-first WebView: D-pad moves an on-page cursor, OK clicks, long-press opens the browser
 * menu, and the DOM element under the cursor gets an accent outline. The browser is a normal
 * TV destination; media can be sent to Nuvio's player from the menu.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvBrowserScreen(
    onBack: () -> Unit,
    onPlayInPlayer: (url: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val tokens = NuvioTheme.colors
    val accent = tokens.FocusRing
    val accentHex = "#%06X".format(0xFFFFFF and accent.toArgb())
    val tabs = remember { mutableStateListOf(BrowserTab(id = 1L, url = START_URL)) }
    var activeTab by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(START_URL) }
    var pageUrl by remember { mutableStateOf(START_URL) }
    var pageTitle by remember { mutableStateOf("Browser") }
    var pointer by remember { mutableStateOf(Offset(520f, 360f)) }
    var menuOpen by remember { mutableStateOf(false) }
    var addressOpen by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    var mediaUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var longPressFired by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val rootFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val tabsFocus = remember { FocusRequester() }

    val overlayOpen = menuOpen || addressOpen || tabsOpen || mediaOpen

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

    fun clickAtPointer() {
        val view = webView ?: return
        val x = pointer.x.roundToInt()
        val y = pointer.y.roundToInt()
        view.evaluateJavascript(
            """
            (function(){
              var e=document.elementFromPoint($x,$y);
              if(!e) return;
              try { e.focus(); } catch(err) {}
              try { e.click(); } catch(err) {}
            })()
            """.trimIndent(),
            null,
        )
        scope.launch { delay(160); runCatching { rootFocus.requestFocus() } }
    }

    fun loadAddress() {
        val view = webView ?: return
        val raw = address.trim()
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        tabs.getOrNull(activeTab)?.url = url
        address = url
        addressOpen = false
        view.loadUrl(url)
        scope.launch { delay(150); rootFocus.requestFocus() }
    }

    fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        address = tabs[index].url
        webView?.loadUrl(tabs[index].url)
        tabsOpen = false
        scope.launch { delay(120); rootFocus.requestFocus() }
    }

    fun addTab() {
        val newTab = BrowserTab(id = (tabs.maxOfOrNull { it.id } ?: 0L) + 1, url = "about:blank")
        tabs.add(newTab)
        activeTab = tabs.lastIndex
        address = ""
        addressOpen = true
        tabsOpen = false
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs.removeAt(index)
        activeTab = activeTab.coerceAtMost(tabs.lastIndex)
        address = tabs[activeTab].url
        webView?.loadUrl(tabs[activeTab].url)
    }

    fun scanMedia() {
        val view = webView ?: return
        view.evaluateJavascript(
            """
            (function(){
              var out=[];
              document.querySelectorAll('video,source').forEach(function(e){
                var u=e.currentSrc||e.src||e.getAttribute('src'); if(u) out.push(u);
              });
              document.querySelectorAll('a[href]').forEach(function(a){
                var h=a.href; if(/\.(m3u8|mp4|webm)(\?|$)/i.test(h)) out.push(h);
              });
              return JSON.stringify(Array.from(new Set(out)));
            })()
            """.trimIndent(),
            ValueCallback { value ->
                val raw = runCatching {
                    (JSONTokener(value).nextValue() as? String) ?: value
                }.getOrNull() ?: return@ValueCallback
                val array = runCatching { JSONArray(raw) }.getOrNull()
                mediaUrls = buildList {
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                mediaOpen = true
            },
        )
    }

    fun openMenu() {
        menuOpen = true
        scope.launch { delay(100); runCatching { menuFocus.requestFocus() } }
    }

    fun closeOverlays() {
        menuOpen = false
        addressOpen = false
        tabsOpen = false
        mediaOpen = false
        scope.launch { delay(100); runCatching { rootFocus.requestFocus() } }
    }

    BackHandler {
        when {
            addressOpen -> { addressOpen = false; closeOverlays() }
            tabsOpen -> { tabsOpen = false; closeOverlays() }
            mediaOpen -> { mediaOpen = false; closeOverlays() }
            menuOpen -> { menuOpen = false; closeOverlays() }
            webView?.canGoBack() == true -> {
                webView?.goBack()
                scope.launch { delay(250); rootFocus.requestFocus() }
            }
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { rootFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.Background)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (overlayOpen) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                when {
                    down && event.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) -> {
                        val maxX = configuration.screenWidthDp.toFloat() - 26f
                        val maxY = configuration.screenHeightDp.toFloat() - 26f
                        pointer = when (event.key) {
                            Key.DirectionLeft -> pointer.copy(x = (pointer.x - POINTER_STEP).coerceAtLeast(0f))
                            Key.DirectionRight -> pointer.copy(x = (pointer.x + POINTER_STEP).coerceAtMost(maxX))
                            Key.DirectionUp -> pointer.copy(y = (pointer.y - POINTER_STEP).coerceAtLeast(0f))
                            else -> pointer.copy(y = (pointer.y + POINTER_STEP).coerceAtMost(maxY))
                        }
                        highlightAtPointer()
                        true
                    }
                    down && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
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
                    !down && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (!longPressFired) clickAtPointer()
                        longPressFired = false
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
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            url?.let { pageUrl = it; address = it; refreshTab() }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            url?.let { pageUrl = it; address = it; refreshTab() }
                            view?.let { injectFocusCss(it) }
                            scope.launch { delay(120); runCatching { rootFocus.requestFocus() } }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = false
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.takeIf { it.isNotBlank() }?.let {
                                pageTitle = it
                                refreshTab()
                            }
                        }
                    }
                    loadUrl(START_URL)
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

        // URL / title pill.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                modifier = Modifier.widthIn(max = 380.dp),
            )
            Text(
                "Hold OK for menu",
                color = tokens.TextTertiary,
                fontSize = 10.sp,
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
        }

        if (menuOpen) {
            BrowserMenu(
                focusRequester = menuFocus,
                onDismiss = { closeOverlays() },
                onBackNavigation = {
                    webView?.goBack()
                    closeOverlays()
                },
                onForward = {
                    webView?.goForward()
                    closeOverlays()
                },
                onReload = {
                    webView?.reload()
                    closeOverlays()
                },
                onScrollUp = {
                    webView?.scrollBy(0, -(webView?.height ?: 0) * 3 / 4)
                    closeOverlays()
                },
                onScrollDown = {
                    webView?.scrollBy(0, (webView?.height ?: 0) * 3 / 4)
                    closeOverlays()
                },
                onTabs = { menuOpen = false; tabsOpen = true; scope.launch { delay(80); runCatching { tabsFocus.requestFocus() } } },
                onAddress = { menuOpen = false; addressOpen = true },
                onPlayInPlayer = { menuOpen = false; scanMedia() },
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
                urls = mediaUrls,
                onPlay = { url ->
                    mediaOpen = false
                    onPlayInPlayer(url, pageTitle.ifBlank { "Web video" })
                },
                onDismiss = { mediaOpen = false; closeOverlays() },
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
) {
    val tokens = NuvioTheme.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)), contentAlignment = Alignment.Center) {
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
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().nuvioFocusRing(RoundedCornerShape(12.dp))) {
                    Text("Close menu")
                }
            }
        }
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
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
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
                    Button(onClick = onGo, modifier = Modifier.nuvioFocusRing(RoundedCornerShape(12.dp))) {
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
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
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
    urls: List<String>,
    onPlay: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NuvioTheme.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .widthIn(max = 900.dp)
                .height(600.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.BackgroundElevated)
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Play in player", color = tokens.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                Text("Detected media on this page", color = tokens.TextSecondary, fontSize = 12.sp)
                if (urls.isEmpty()) {
                    Text(
                        "No video or playlist link was found on this page. Start the video once in the browser, then open this menu again.",
                        color = tokens.TextSecondary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(urls, key = { it }) { url ->
                            Button(
                                onClick = { onPlay(url) },
                                modifier = Modifier.fillMaxWidth().nuvioFocusRing(RoundedCornerShape(12.dp)),
                                colors = ButtonDefaults.colors(
                                    containerColor = tokens.BackgroundCard,
                                    contentColor = tokens.TextPrimary,
                                    focusedContainerColor = tokens.FocusBackground,
                                    focusedContentColor = tokens.TextPrimary,
                                ),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
}
