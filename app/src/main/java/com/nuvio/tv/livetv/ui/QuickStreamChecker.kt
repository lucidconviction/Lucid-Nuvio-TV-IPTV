package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.ForkHttp
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight stream liveness probe for Quick Ch Search.
 *
 * It is deliberately conservative: a stream is hidden only when there is strong evidence that it
 * is dead (an HTTP 401/403/404/410, an HTML error page, or a textual error response). Slow
 * providers, redirects, range-request rejections, server errors and connection hiccups are kept
 * visible, because hiding a working channel is worse than showing one the player may fail to open.
 */
object QuickStreamChecker {

    private const val TTL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 8_000L
    private const val RETRY_TIMEOUT_MS = 12_000L
    private const val MAX_CONCURRENCY = 8
    private const val SAMPLE_SIZE = 4096

    private val client: OkHttpClient = ForkHttp.builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val htmlPagePrefixes = listOf("<!doctype html", "<!doctype", "<html", "<!", "<?xml")
    private val deadPageTokens = listOf(
        "404", "403", "401", "not found", "forbidden", "unauthorized",
        "invalid user", "invalid username", "invalid password",
        "account disabled", "account suspended", "account expired",
        "credits expired", "subscription expired", "stream expired", "expired",
        "offline", "service unavailable", "bad gateway", "internal server error",
        "error", "failed to open stream", "stream not found", "channel not found",
        "access denied",
    )
    private val mediaContentTypes = listOf(
        "video/", "audio/", "application/vnd.apple.mpegurl", "application/x-mpegurl",
        "application/dash+xml", "application/octet-stream", "mpegurl", "mp2t",
    )

    private data class Entry(val alive: Boolean, val checkedAt: Long)
    private data class ProbeResponse(val status: Int, val contentType: String?, val sample: String)
    private enum class Verdict { Alive, Dead, Unknown }
    private enum class SampleKind { Media, ErrorPage, Unclear }

    private val cache = HashMap<String, Entry>()
    private val cacheLock = Mutex()

    /** Probes each distinct URL and reports every result as it lands. Cached results are instant. */
    suspend fun checkBatch(
        channels: List<LiveChannel>,
        forceRefresh: Boolean = false,
        onResult: (url: String, alive: Boolean) -> Unit = { _, _ -> },
    ): Map<String, Boolean> {
        val unique = channels.filter { it.url.isNotBlank() }.distinctBy { it.url }
        if (unique.isEmpty()) return emptyMap()
        val results = HashMap<String, Boolean>()
        val resultsLock = Mutex()
        val permits = Semaphore(MAX_CONCURRENCY)
        coroutineScope {
            unique.forEach { channel ->
                launch {
                    val alive = permits.withPermit { checkChannel(channel, forceRefresh) }
                    resultsLock.withLock { results[channel.url] = alive }
                    onResult(channel.url, alive)
                }
            }
        }
        return results
    }

    private suspend fun checkChannel(channel: LiveChannel, forceRefresh: Boolean): Boolean {
        // Addon-backed and other resolved-at-play-time channels have no direct address to probe.
        // Keep them (they are not dead), rather than hiding a channel the player can still open.
        if (!channel.url.startsWith("http", ignoreCase = true)) return true
        val key = cacheKey(channel)
        val now = System.currentTimeMillis()
        val cached = cacheLock.withLock { cache[key] }
        if (!forceRefresh && cached != null && now - cached.checkedAt < TTL_MS) return cached.alive
        val alive = probe(channel)
        cacheLock.withLock { cache[key] = Entry(alive, now) }
        return alive
    }

    private suspend fun probe(channel: LiveChannel): Boolean {
        val headers = requestHeaders(channel)
        val ranged = probeOnce(channel.url, headers, "bytes=0-${SAMPLE_SIZE - 1}", TIMEOUT_MS)
        when (ranged?.let(::verdictOf)) {
            Verdict.Alive -> return true
            Verdict.Dead -> return false
            Verdict.Unknown, null -> Unit
        }
        val plain = probeOnce(channel.url, headers, null, RETRY_TIMEOUT_MS)
        return when (plain?.let(::verdictOf)) {
            Verdict.Alive -> true
            Verdict.Dead -> false
            // Inconclusive twice: a slow or temporarily unreachable provider may still play.
            Verdict.Unknown, null -> true
        }
    }

    private fun requestHeaders(channel: LiveChannel): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        channel.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) headers[k] = v }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = LiveTvRepository.DEFAULT_UA
        }
        return headers
    }

    private fun cacheKey(channel: LiveChannel): String {
        if (channel.headers.isEmpty()) return channel.url
        val headerPart = channel.headers.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        return channel.url + "\n" + headerPart
    }

    private suspend fun probeOnce(
        url: String,
        headers: Map<String, String>,
        range: String?,
        timeoutMs: Long,
    ): ProbeResponse? = try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val request = Request.Builder().url(url).get().apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    range?.let { header("Range", it) }
                }.build()
                client.newCall(request).execute().use { response ->
                    val sample = if (response.isSuccessful) {
                        readSample(response.body?.byteStream())
                    } else {
                        ""
                    }
                    ProbeResponse(
                        status = response.code,
                        contentType = response.header("Content-Type"),
                        sample = sample,
                    )
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun readSample(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val bytes = ByteArray(SAMPLE_SIZE)
        var total = 0
        try {
            while (total < SAMPLE_SIZE) {
                val n = stream.read(bytes, total, SAMPLE_SIZE - total)
                if (n <= 0) break
                total += n
            }
        } catch (_: Exception) {
            // Whatever came in before the timeout is still a useful sample.
        }
        return decodeSample(bytes, total)
    }

    /** One character per byte (Latin-1), so binary media keeps its control bytes. */
    internal fun decodeSample(bytes: ByteArray, length: Int): String {
        val chars = CharArray(length.coerceAtMost(bytes.size))
        for (i in chars.indices) chars[i] = (bytes[i].toInt() and 0xFF).toChar()
        return chars.concatToString()
    }

    private fun verdictOf(resp: ProbeResponse): Verdict = when (resp.status) {
        in 200..299 -> when (classifySample(resp.sample)) {
            SampleKind.Media -> Verdict.Alive
            SampleKind.ErrorPage -> Verdict.Dead
            SampleKind.Unclear ->
                if (resp.contentType.isMediaContentType()) Verdict.Alive else Verdict.Unknown
        }
        401, 403, 404, 410 -> Verdict.Dead
        else -> Verdict.Unknown
    }

    private fun String?.isMediaContentType(): Boolean {
        val value = this?.lowercase() ?: return false
        return mediaContentTypes.any { value.contains(it) }
    }

    internal fun looksAlive(sample: String): Boolean {
        if (sample.trimStart('\uFEFF', ' ', '\t', '\r', '\n').isEmpty()) return false
        return classifySample(sample) != SampleKind.ErrorPage
    }

    private fun classifySample(sample: String): SampleKind {
        val trimmed = sample.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return SampleKind.Unclear
        val lower = trimmed.lowercase()
        if (lower.startsWith("#extm3u")) return SampleKind.Media
        if (lower.contains("<mpd") || lower.contains("<smoothstreamingmedia")) return SampleKind.Media
        if (isBinary(trimmed)) return SampleKind.Media
        if (htmlPagePrefixes.any { lower.startsWith(it) }) return SampleKind.ErrorPage
        val check = lower.take(512)
        if (deadPageTokens.any { check.contains(it) }) return SampleKind.ErrorPage
        return SampleKind.Unclear
    }

    private fun isBinary(sample: String): Boolean {
        val probe = sample.take(256)
        if (probe.isEmpty()) return false
        var control = 0
        for (ch in probe) {
            val code = ch.code
            if (code == 0 || code in 1..8 || code in 11..12 || code in 14..31 || code in 127..159) control++
        }
        return control >= 2
    }

    fun clearCache() {
        cache.clear()
    }
}
