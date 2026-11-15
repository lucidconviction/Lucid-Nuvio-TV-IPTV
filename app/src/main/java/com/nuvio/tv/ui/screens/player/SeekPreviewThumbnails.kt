package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * On-demand seek previews for direct (progressive) streams: a small frame at the position the
 * user is scrubbing to, decoded off the main thread and kept in a tiny in-memory cache. HLS/DASH
 * can't be read this way (their frames only exist inside segments the player has parsed), so
 * those simply get no image and keep the timestamp.
 */
internal object SeekPreviewThumbnails {
    private const val BUCKET_MS = 10_000L
    private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    private const val FRAME_WIDTH = 320
    private const val FRAME_HEIGHT = 180
    private const val MAX_FAILURES = 512

    private val cache = object : android.util.LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // A missing keyframe at one position doesn't mean the whole stream is unreadable, but a
    // failed read is expensive enough to remember. The set is bounded so channel/stream
    // hopping can't grow it without limit.
    private val failed: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val lock = Mutex()

    suspend fun frame(
        context: Context,
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): Bitmap? {
        if (url.isBlank() || !canExtract(url)) return null
        val bucket = (positionMs.coerceAtLeast(0L) / BUCKET_MS) * BUCKET_MS
        val key = "$url#$bucket"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        return lock.withLock {
            cache.get(key)?.let { return@withLock it }
            if (key in failed) return@withLock null
            val bitmap = withContext(Dispatchers.IO) { extract(context, url, headers, bucket) }
            if (bitmap == null) {
                if (failed.size >= MAX_FAILURES) failed.clear()
                failed.add(key)
                null
            } else {
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    private fun canExtract(url: String): Boolean {
        val readable = url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("file://", ignoreCase = true) ||
            url.startsWith("content://", ignoreCase = true) ||
            url.startsWith("/")
        if (!readable) return false
        val lower = url.substringBefore('?').lowercase()
        return !lower.endsWith(".m3u8") &&
            !lower.endsWith(".mpd") &&
            !lower.contains(".m3u8/") &&
            !lower.contains(".mpd/")
    }

    private fun extract(
        context: Context,
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                url.startsWith("content://", ignoreCase = true) ->
                    retriever.setDataSource(context, Uri.parse(url))
                url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true) ->
                    if (headers.isEmpty()) retriever.setDataSource(url)
                    else retriever.setDataSource(url, headers)
                url.startsWith("file://", ignoreCase = true) ->
                    retriever.setDataSource(Uri.parse(url).path ?: url)
                else -> retriever.setDataSource(url)
            }
            val timeUs = positionMs.coerceAtLeast(0L) * 1_000L
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    FRAME_WIDTH,
                    FRAME_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            raw?.let(::shrink)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun shrink(source: Bitmap): Bitmap {
        if (source.width <= FRAME_WIDTH) return source
        val height = (source.height.toFloat() * FRAME_WIDTH / source.width)
            .toInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, FRAME_WIDTH, height, true)
    }
}
