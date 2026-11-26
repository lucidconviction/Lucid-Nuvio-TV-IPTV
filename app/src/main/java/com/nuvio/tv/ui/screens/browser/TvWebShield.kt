package com.nuvio.tv.ui.screens.browser

import android.content.Context
import android.webkit.WebResourceRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** How the TV browser treats a navigation the page starts without a remote click. */
enum class TvRedirectPolicy { AlwaysBlock, Ask, Allow }

/** What to do when the page exposes a playable video. */
enum class TvAutoPlayMode { Off, Ask, Best }

data class TvBrowserSettings(
    val adBlockEnabled: Boolean = true,
    val cosmeticFiltering: Boolean = true,
    val popupProtection: Boolean = true,
    val redirectPolicy: TvRedirectPolicy = TvRedirectPolicy.AlwaysBlock,
    val autoPlayMode: TvAutoPlayMode = TvAutoPlayMode.Off,
)

object TvBrowserSettingsStore {
    private const val PREFS = "tv_browser"
    private const val KEY_AD_BLOCK = "ad_block_enabled"
    private const val KEY_COSMETIC = "cosmetic_filtering"
    private const val KEY_POPUP = "popup_protection"
    private const val KEY_REDIRECT = "redirect_policy"
    private const val KEY_AUTO_PLAY = "auto_play_mode"
    private const val KEY_ALLOWED_REDIRECTS = "allowed_redirect_hosts"
    private const val KEY_BLOCKED_REDIRECTS = "blocked_redirect_hosts"

    fun load(context: Context): TvBrowserSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TvBrowserSettings(
            adBlockEnabled = prefs.getBoolean(KEY_AD_BLOCK, true),
            cosmeticFiltering = prefs.getBoolean(KEY_COSMETIC, true),
            popupProtection = prefs.getBoolean(KEY_POPUP, true),
            redirectPolicy = runCatching {
                TvRedirectPolicy.valueOf(
                    prefs.getString(KEY_REDIRECT, null) ?: TvRedirectPolicy.AlwaysBlock.name,
                )
            }.getOrDefault(TvRedirectPolicy.AlwaysBlock),
            autoPlayMode = runCatching {
                TvAutoPlayMode.valueOf(
                    prefs.getString(KEY_AUTO_PLAY, null) ?: TvAutoPlayMode.Off.name,
                )
            }.getOrDefault(TvAutoPlayMode.Off),
        )
    }

    fun save(context: Context, settings: TvBrowserSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AD_BLOCK, settings.adBlockEnabled)
            .putBoolean(KEY_COSMETIC, settings.cosmeticFiltering)
            .putBoolean(KEY_POPUP, settings.popupProtection)
            .putString(KEY_REDIRECT, settings.redirectPolicy.name)
            .putString(KEY_AUTO_PLAY, settings.autoPlayMode.name)
            .apply()
    }

    fun allowedRedirectHosts(context: Context): MutableSet<String> =
        HashSet(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_ALLOWED_REDIRECTS, null).orEmpty(),
        )

    fun blockedRedirectHosts(context: Context): MutableSet<String> =
        HashSet(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_BLOCKED_REDIRECTS, null).orEmpty(),
        )

    fun setAllowedRedirectHosts(context: Context, hosts: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_ALLOWED_REDIRECTS, hosts).apply()
    }

    fun setBlockedRedirectHosts(context: Context, hosts: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_BLOCKED_REDIRECTS, hosts).apply()
    }
}

/**
 * The TV port of the mobile browser's shield: built-in ad hosts, an EasyList/uBO-style
 * network-rule subset, cached downloaded lists, cosmetic filtering and redirect decisions.
 */
object TvWebShield {
    private val builtInHosts = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "google-analytics.com", "adservice.google.com", "2mdn.net", "adf.ly",
        "taboola.com", "outbrain.com", "adnxs.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "moatads.com", "quantserve.com", "scorecardresearch.com", "amazon-adsystem.com", "adform.net",
        "smartadserver.com", "openx.net", "pubmatic.com", "sovrn.com", "casalemedia.com", "lijit.com",
        "adsafeprotected.com", "popads.net", "popunder.ru", "adsterra.com", "propellerads.com",
        "adroll.com", "media.net", "yieldmo.com", "adcolony.com", "inmobi.com", "admarvel.com",
        "flurry.com", "startappservice.com", "smartyads.com", "admob.com", "applovin.com",
        "vungle.com", "chartboost.com", "mopub.com", "supersonicads.com", "revcontent.com",
        "adpushup.com", "snigelweb.com", "adthrive.com", "ezoic.net", "mediavine.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com", "popcash.net", "hilltopads.net",
    )

    private val hostsListUrls = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://adaway.org/hosts.txt",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    )

    private val filterListUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_2_Base/filter.txt",
    )

    private const val MAX_FILTER_RULES = 12_000

    @Volatile private var extraHosts: Set<String> = emptySet()
    @Volatile private var filterRules: List<PatternRule> = emptyList()
    @Volatile private var combinedRules: List<PatternRule> = emptyList()
    @Volatile private var loaded = false
    @Volatile var ruleCount: Int = 0
        private set
    @Volatile var lastUpdated: Long = 0L
        private set

    private val builtInRules: List<PatternRule> by lazy {
        BUILTIN_PATTERNS.mapNotNull(::parsePattern)
    }

    private fun compiledPatterns(): List<PatternRule> =
        combinedRules.ifEmpty { builtInRules }

    private fun hostsFile(context: Context) = File(context.filesDir, "tv_web_shield_hosts.txt")
    private fun filterDir(context: Context) = File(context.filesDir, "tv_web_shield_filters").apply { mkdirs() }
    private fun filterCacheFile(context: Context, url: String) =
        File(filterDir(context), url.hashCode().toLong().toString(16) + ".txt")

    fun load(context: Context) {
        val file = hostsFile(context)
        if (file.exists()) {
            extraHosts = runCatching { file.readLines().filter { it.isNotBlank() }.toSet() }
                .getOrDefault(emptySet())
        }
        lastUpdated = runCatching { file.lastModified() }.getOrDefault(0L)
        if (ruleCount == 0) ruleCount = builtInRules.size
        if (loaded) return
        loaded = true
        Thread {
            val rules = readCachedFilters(context)
            filterRules = rules
            combinedRules = builtInRules + rules
            ruleCount = combinedRules.size
        }.start()
    }

    /** Downloads the host lists and filter lists. Runs synchronously; call from IO. */
    fun update(context: Context) {
        val hosts = HashSet<String>()
        for (url in hostsListUrls) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "LucidTV")
                }
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.substringBefore('#').trim()
                        if (clean.isBlank()) return@forEach
                        val parts = clean.split(Regex("\\s+"))
                        val host = when {
                            parts.firstOrNull()?.let(::isIp) == true -> parts.getOrNull(1)
                            else -> parts.firstOrNull()
                        }?.trim()?.trimEnd('.')?.lowercase()
                        if (host != null && host.contains('.') &&
                            !host.startsWith("localhost") && !host.endsWith(".local")
                        ) {
                            hosts += host
                        }
                    }
                }
                connection.disconnect()
            }
        }
        if (hosts.isNotEmpty()) {
            extraHosts = hosts
            runCatching { hostsFile(context).writeText(hosts.joinToString("\n")) }
        }
        for (url in filterListUrls) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 40_000
                    setRequestProperty("User-Agent", "LucidTV")
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                if (text.isNotBlank()) filterCacheFile(context, url).writeText(text)
            }
        }
        val rules = readCachedFilters(context)
        filterRules = rules
        combinedRules = builtInRules + rules
        ruleCount = combinedRules.size
        lastUpdated = System.currentTimeMillis()
    }

    private fun readCachedFilters(context: Context): List<PatternRule> {
        val rules = ArrayList<PatternRule>()
        filterDir(context).listFiles()?.forEach { cached ->
            if (!cached.name.endsWith(".txt")) return@forEach
            runCatching {
                parseFilterList(cached.readText(), MAX_FILTER_RULES - rules.size).forEach { rules += it }
            }
        }
        return rules
    }

    private fun isIp(value: String): Boolean =
        value == "0.0.0.0" || value == "127.0.0.1" || value == "::1" || value.startsWith("::ffff:")

    private val essentialHosts = setOf(
        "cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "ajax.googleapis.com", "fonts.googleapis.com", "fonts.gstatic.com",
        "challenges.cloudflare.com", "cloudflare.com",
    )

    private fun isEssentialHost(host: String): Boolean = isBlockedHost(host, essentialHosts)

    /** Video CDNs and player hosts a host list must never take down. */
    private val alwaysAllowedHosts = setOf(
        "dmcdn.net", "googlevideo.com", "ytimg.com", "ggpht.com",
        "fbcdn.net", "twimg.com", "cdninstagram.com",
        "akamaihd.net", "akamaized.net", "edgesuite.net", "llnwd.net",
        "cloudfront.net", "fastly.net", "azureedge.net", "b-cdn.net",
        "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "mux.com", "jwplayer.com", "jwplatform.com", "vimeocdn.com",
        "cloudflarestream.com", "vidyoda.com",
    )

    fun isAlwaysAllowedHost(host: String): Boolean =
        host.isNotBlank() && isBlockedHost(host, alwaysAllowedHosts)

    private fun isBlockedHost(host: String, list: Set<String>): Boolean {
        if (list.isEmpty()) return false
        var current = host
        while (true) {
            if (current in list) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    fun isBlocked(url: String, includeExtra: Boolean = true): Boolean {
        val host = hostOf(url) ?: return false
        if (isEssentialHost(host) || isAlwaysAllowedHost(host)) return false
        return isBlockedHost(host, builtInHosts) ||
            (includeExtra && isBlockedHost(host, extraHosts))
    }

    data class PatternRule(
        val raw: String,
        val isException: Boolean,
        val regex: Regex,
        val thirdParty: Boolean?,
        val domains: List<DomainOpt>?,
        val types: Set<String>?,
        val keyword: String?,
        val fromFilterList: Boolean = false,
    )

    data class DomainOpt(val domain: String, val negated: Boolean)

    private val BUILTIN_PATTERNS = listOf(
        "||popads.net^", "||popcash.net^", "||popunder.net^",
        "||adsterra.com^", "||propellerads.com^", "||propeller-tracking.com^",
        "||adcash.com^", "||exoclick.com^", "||trafficjunky.net^",
        "||juicyads.com^", "||adxpansion.com^", "||hilltopads.net^",
        "||clickadu.com^", "||mgid.com^", "||revcontent.com^",
        "||outbrain.com^", "||taboola.com^", "||criteo.com^",
        "||adnxs.com^", "||rubiconproject.com^", "||pubmatic.com^",
        "||doubleclick.net^", "||googlesyndication.com^",
        "||googleadservices.com^", "||amazon-adsystem.com^",
        "||imasdk.googleapis.com^",
        "/popunder", "/pop-under", "/popup-ad", "/popad.", "/pop.js",
        "/popunder.js", "/popup.js", "/popcash.js", "/pst.js",
        "/ads/banner", "/ads/pop", "/advertising/", "/adserver/",
        "/promo-redirect", "/redirect-ads", "/out.php?",
        "||vast.com^", "/vast.xml", "/vmap", "imasdk",
        "/jwplayer-ads", "/video-ads/", "/preroll",
        "||moatads.com^", "||scorecardresearch.com^", "||quantserve.com^",
        "@@||accounts.google.com^", "@@||paypal.com^", "@@||stripe.com^",
        "@@||cloudflare.com^", "@@||challenges.cloudflare.com^",
    )

    private val optionsRe = Regex("^[a-z~\\-]+(=[^,]*)?(,[a-z~\\-]+(=[^,]*)?)*$")

    fun parsePattern(raw: String): PatternRule? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return null
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#$#")) return null
        if (s.startsWith("/") && s.endsWith("/") && s.length > 2) return null
        val isException = s.startsWith("@@")
        if (isException) s = s.substringAfter("@@")
        var thirdParty: Boolean? = null
        var domains: List<DomainOpt>? = null
        var types: Set<String>? = null
        val optIdx = s.lastIndexOf('$')
        if (optIdx > 0) {
            val opts = s.substring(optIdx + 1)
            if (optionsRe.matches(opts)) {
                s = s.substring(0, optIdx)
                for (o in opts.split(',')) {
                    val name = o.substringBefore('=')
                    val value = o.substringAfter('=', "")
                    when (name) {
                        "third-party" -> thirdParty = true
                        "~third-party" -> thirdParty = false
                        "domain" -> domains = value.split('|')
                            .filter { it.isNotBlank() }
                            .map { DomainOpt(it.trimStart('~').lowercase(), it.startsWith("~")) }
                            .takeIf { it.isNotEmpty() }
                        "script", "stylesheet", "image", "font", "media",
                        "xmlhttprequest", "subdocument", "other", "object",
                        "ping", "websocket" -> types = (types ?: emptySet()) + name
                        "~script", "~stylesheet", "~image", "~font", "~media",
                        "~xmlhttprequest", "~subdocument", "~other" -> Unit
                        "match-case", "document", "elemhide", "generichide", "important",
                        "redirect", "csp", "removeparam", "cookie" -> Unit
                        else -> return null
                    }
                }
            }
        }
        if (s.isEmpty()) return null
        val startAnchor = s.startsWith("|") && !s.startsWith("||")
        val domainAnchor = s.startsWith("||")
        var endAnchor = false
        var core = when {
            domainAnchor -> s.substring(2)
            startAnchor -> s.substring(1)
            else -> s
        }
        if (core.endsWith("|") && !core.endsWith("\\|")) {
            endAnchor = true
            core = core.dropLast(1)
        }
        val keyword = Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{3,}")
            .find(core)?.value?.lowercase()?.takeIf { it.length >= 4 }
        val body = buildString {
            for (c in core) {
                when (c) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        val pattern = when {
            domainAnchor -> "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]*\\.)?" + body
            startAnchor -> "^" + body
            else -> body
        } + if (endAnchor) "$" else ""
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        return PatternRule(raw, isException, regex, thirdParty, domains, types, keyword)
    }

    private fun parseFilterList(text: String, limit: Int): List<PatternRule> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<PatternRule>(minOf(limit, 2_000))
        for (line in text.lineSequence()) {
            if (out.size >= limit) break
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[")) continue
            if (raw.contains("##") || raw.contains("#@#") || raw.contains("#?#") || raw.contains("#$#")) continue
            parsePattern(raw)?.copy(fromFilterList = true)?.let { out += it }
        }
        return out
    }

    fun requestType(request: WebResourceRequest): String = requestType(request.url.toString(), request)

    fun requestType(url: String, request: WebResourceRequest? = null): String {
        val u = url.lowercase()
        val path = u.substringBefore('?')
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") || path.endsWith(".bmp") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> "font"
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                path.endsWith(".ts") || path.endsWith(".webm") || path.endsWith(".m4s") -> "media"
            else -> {
                val accept = request?.requestHeaders?.entries
                    ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value?.lowercase().orEmpty()
                when {
                    accept.contains("javascript") || accept.contains("ecmascript") -> "script"
                    accept.contains("css") -> "stylesheet"
                    accept.startsWith("image/") -> "image"
                    accept.contains("font") -> "font"
                    accept.contains("video/") || accept.contains("audio/") ||
                        accept.contains("mpegurl") || accept.contains("mpd") -> "media"
                    request?.method.equals("POST", ignoreCase = true) -> "xmlhttprequest"
                    else -> "other"
                }
            }
        }
    }

    private fun registrableDomain(host: String): String {
        val parts = host.lowercase().trimEnd('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    fun sameSite(a: String, b: String): Boolean {
        val first = a.lowercase().trimEnd('.')
        val second = b.lowercase().trimEnd('.')
        if (first.isEmpty() || second.isEmpty()) return false
        if (first == second || first.endsWith(".$second") || second.endsWith(".$first")) return true
        return registrableDomain(first) == registrableDomain(second)
    }

    private fun ruleApplies(
        rule: PatternRule,
        url: String,
        reqHost: String,
        pageHost: String?,
        type: String,
    ): Boolean {
        if (rule.keyword != null && !url.contains(rule.keyword, ignoreCase = true)) return false
        if (!rule.regex.containsMatchIn(url)) return false
        if (rule.thirdParty != null && pageHost != null) {
            val third = !sameSite(reqHost, pageHost)
            if (rule.thirdParty != third) return false
        }
        rule.domains?.let { opts ->
            val ph = (pageHost ?: "").lowercase()
            var included = opts.none { !it.negated }
            for (o in opts) {
                if (ph == o.domain || ph.endsWith("." + o.domain)) {
                    if (o.negated) return false
                    included = true
                }
            }
            if (!included) return false
        }
        rule.types?.let { if (type !in it) return false }
        return true
    }

    fun matchesPatternUrl(
        url: String,
        pageHost: String?,
        type: String = "other",
        includeFilterRules: Boolean = true,
    ): Boolean {
        val reqHost = hostOf(url) ?: return false
        if (isEssentialHost(reqHost)) return false
        val patterns = compiledPatterns()
        if (patterns.isEmpty()) return false
        var blocked = false
        for (rule in patterns) {
            if (!includeFilterRules && rule.fromFilterList) continue
            if (!ruleApplies(rule, url, reqHost, pageHost, type)) continue
            if (rule.isException) return false
            blocked = true
        }
        return blocked
    }

    fun matchesPattern(request: WebResourceRequest, pageHost: String?): Boolean =
        matchesPatternUrl(request.url.toString(), pageHost, requestType(request))

    /** True when an automatic navigation is a known ad redirect. */
    fun isAdNavigation(url: String, pageHost: String?): Boolean =
        isBlocked(url, includeExtra = true) ||
            matchesPatternUrl(url, pageHost, type = "document")

    fun isCloudflareInfra(url: String?): Boolean {
        if (url == null) return false
        if (url.contains("/cdn-cgi/")) return true
        val host = hostOf(url) ?: return false
        return host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com") ||
            host == "cloudflare.com" || host.endsWith(".cloudflare.com") ||
            host == "cloudflareinsights.com" || host.endsWith(".cloudflareinsights.com")
    }

    /** Login/payment/challenge hosts that redirect protection must always allow. */
    private val alwaysAllowedRedirectHosts = setOf(
        "accounts.google.com", "accounts.youtube.com", "login.microsoftonline.com",
        "appleid.apple.com", "paypal.com", "stripe.com", "checkout.stripe.com",
        "challenges.cloudflare.com", "recaptcha.net", "hcaptcha.com",
    )

    fun isAlwaysAllowedRedirect(host: String): Boolean =
        isBlockedHost(host, alwaysAllowedRedirectHosts)

    fun hostOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.host != null) uri.host
        else URL(url).host
    }.getOrNull()?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotBlank() }

    /** Narrow cosmetic rules; never hide players, menus or forms. */
    val cosmeticJavaScript: String = """
        (function(){
          if(window.__lucidTvClean)return;
          window.__lucidTvClean=true;
          var SEL=[
            'ins.adsbygoogle','.adsbygoogle','[id^="google_ads"]',
            'iframe[src*="doubleclick.net"]','iframe[src*="googlesyndication.com"]',
            'iframe[src*="googleads"]','iframe[src*="2mdn.net"]',
            'iframe[src*="popads.net"]','iframe[src*="popcash.net"]',
            'iframe[src*="propellerads.com"]','iframe[src*="adsterra.com"]',
            'iframe[src*="exoclick.com"]','iframe[src*="juicyads.com"]',
            'iframe[src*="trafficjunky.net"]','iframe[src*="adnxs.com"]'
          ];
          function safe(el){
            if(!el)return false;
            try{
              if(el.querySelector('video,audio,iframe,[class*="player"],[class*="menu"],[role="dialog"],[role="menu"],form,input[type="password"]'))return false;
              var style=getComputedStyle(el);
              if(style.position==='fixed'&&el.querySelector('video,iframe'))return false;
              return true;
            }catch(e){return false}
          }
          function clean(){
            if(document.hidden)return;
            for(var i=0;i<SEL.length;i++){
              try{
                document.querySelectorAll(SEL[i]).forEach(function(el){
                  if(safe(el))el.style.setProperty('display','none','important');
                });
              }catch(e){}
            }
          }
          var queued=false;
          function queueClean(){
            if(queued)return;
            queued=true;
            setTimeout(function(){queued=false;clean()},600);
          }
          function start(){
            clean();
            setInterval(clean,4000);
            try{new MutationObserver(queueClean).observe(document.documentElement||document.body,{childList:true,subtree:true});}catch(e){}
          }
          try{
            if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',start,{once:true});}
            else{start();}
          }catch(e){}
        })();
    """.trimIndent()
}
