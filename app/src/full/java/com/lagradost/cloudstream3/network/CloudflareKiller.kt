package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}

/**
 * CloudflareKiller bypasses Cloudflare challenges using WebView.
 * Matches CloudStream's real implementation from RequestsHelper.kt + CloudflareKiller.kt.
 */
@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        // Deliberately do NOT touch CookieManager: getInstance() initializes
        // Android's WebView, and the CloudStream host is a second process.
        // WebView only allows one process at a time, which made the TV browser
        // fail with "no usable WebView" and dropped the browser's own cookies.
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        // Never ask WebViewResolver: it constructs a WebView to read the UA,
        // which would lock WebView away from the TV browser's process.
        val userAgentHeaders = mapOf("user-agent" to USER_AGENT)

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        Log.w(TAG, "Failed cloudflare at: ${request.url}")
        return@runBlocking chain.proceed(request)
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = mapOf("user-agent" to USER_AGENT)

        val requestCookies = request.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()
        val headers = getHeaders(
            request.headers.toMap() + userAgentMap,
            cookies + requestCookies
        )
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).execute()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        // The TV browser and the CloudStream host now share :streamhost, so a
        // clearance cookie earned in the visible browser is available here.
        if (trySolveWithSavedCookies(request)) {
            savedCookies[request.url.host]?.let { cookies ->
                return proceed(request, cookies)
            }
        }
        // No clearance yet. Do not spin up an invisible WebView: it cannot be
        // completed by the user and used to burn CPU in a challenge loop.
        Log.d(TAG, "No cf_clearance for ${request.url}; open the site in the TV browser once: ${request.url}")
        return null
    }

    private fun getWebViewCookie(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (_: Exception) {
        null
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean = try {
        getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) {
                    Log.d(TAG, "Found cf_clearance cookie for ${request.url.host}")
                    savedCookies[request.url.host] = parseCookieMap(cookie)
                }
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun Headers.toMap(): Map<String, String> {
        return buildMap {
            for (i in 0 until size) {
                put(name(i), value(i))
            }
        }
    }
}
