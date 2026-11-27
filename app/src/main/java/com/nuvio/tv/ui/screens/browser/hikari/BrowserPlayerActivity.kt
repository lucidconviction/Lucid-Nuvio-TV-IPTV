package com.nuvio.tv.ui.screens.browser.hikari

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The browser's native playback surface. The web page has already chosen the
 * source; this activity keeps the page's credentials (cookies, Referer,
 * User-Agent) on every request, including HLS playlists and segments, and
 * buffers far ahead (128 MB / 60–120 s) so a slow CDN does not stall playback.
 */
@UnstableApi
class BrowserPlayerActivity : ComponentActivity() {

    private data class Source(
        val name: String,
        val url: String,
        val headers: Map<String, String>,
        val isM3u8: Boolean,
        val isMpd: Boolean,
    )

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var errorView: TextView? = null
    private var statusView: TextView? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ticker.postDelayed(this, 1000L)
        }
    }
    private var currentIndex = 0
    private var sources: List<Source> = emptyList()
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra("title").orEmpty().ifBlank { "Web video" }
        sources = parseSources(intent.getStringExtra("sources"))
        if (sources.isEmpty()) {
            // Direct-URL form, for callers that do not build the source list.
            val direct = intent.getStringExtra("url")
            if (!direct.isNullOrBlank()) {
                val headers = LinkedHashMap<String, String>()
                intent.getStringExtra("headers")?.let { raw ->
                    runCatching {
                        val obj = JSONObject(raw)
                        for (key in obj.keys()) {
                            val value = obj.optString(key)
                            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                        }
                    }
                }
                sources = listOf(
                    Source(
                        name = "Web · ${hostOf(direct)}",
                        url = direct,
                        headers = headers,
                        isM3u8 = direct.contains(".m3u8", true),
                        isMpd = direct.contains(".mpd", true),
                    ),
                )
            }
        }
        if (sources.isEmpty()) {
            Toast.makeText(this, "No playable video source was passed", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            // The local Media3 UI fork ships without the stock controller; the
            // TV player draws its own, remote-first controls (OK / arrows).
            useController = false
            isFocusable = true
            isFocusableInTouchMode = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(view)
        playerView = view

        val header = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(dp(22), dp(16), dp(22), dp(10))
            gravity = Gravity.START
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        }
        root.addView(header)

        val error = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(0xCC000000.toInt())
            visibility = android.view.View.GONE
            isClickable = true
            setOnClickListener { playCurrent() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(error)
        errorView = error

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(28) }
        }
        root.addView(status)
        statusView = status

        setContentView(root)
        hideSystemUi()

        val exo = buildPlayer()
        player = exo
        view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The page may expose an advert first and the real stream second;
                // try the next detected source before showing an error.
                if (sources.size > 1 && currentIndex < sources.lastIndex) {
                    currentIndex++
                    playCurrent()
                    return
                }
                val message = error.errorCodeName + "\n" + (error.cause?.message ?: error.message.orEmpty()) +
                    "\n\nTap to retry"
                errorView?.text = message
                errorView?.visibility = android.view.View.VISIBLE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorView?.visibility = android.view.View.GONE
                }
            }
        })
        playCurrent()
        ticker.post(tick)
        view.requestFocus()
    }

    private fun buildPlayer(): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(BrowserHttp.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(currentSource().headers)

        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(128 * 1024 * 1024)
            .setBufferDurationsMs(60_000, 120_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
    }

    private fun currentSource(): Source = sources[currentIndex.coerceIn(0, sources.lastIndex)]

    private fun playCurrent() {
        val exo = player ?: return
        val source = currentSource()
        errorView?.visibility = android.view.View.GONE
        val mime = when {
            source.isM3u8 || source.url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            source.isMpd || source.url.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            source.url.contains(".mp4", true) -> MimeTypes.VIDEO_MP4
            else -> null
        }
        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply { mime?.let { setMimeType(it) } }
            .build()
        // Rebuild the data source when the selected source changes so its
        // Referer/Cookie headers are the ones the player sends.
        exo.setMediaSource(DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(BrowserHttp.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(source.headers),
        ).createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showSourceChooser() {
        val labels = sources.mapIndexed { index, source ->
            "${if (index == currentIndex) "● " else "  "}${source.name}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Play a source")
            .setItems(labels) { _, which ->
                currentIndex = which
                player?.stop()
                playCurrent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseSources(raw: String?): List<Source> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isBlank()) continue
                    val headers = LinkedHashMap<String, String>()
                    o.optJSONObject("headers")?.let { h ->
                        for (key in h.keys()) {
                            val value = h.optString(key)
                            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
                        }
                    }
                    add(
                        Source(
                            name = o.optString("name").ifBlank { "Web · ${hostOf(url)}" },
                            url = url,
                            headers = headers,
                            isM3u8 = o.optBoolean("isM3u8", url.contains(".m3u8", true)),
                            isMpd = o.optBoolean("isMpd", url.contains(".mpd", true)),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull().orEmpty().take(48)

    private fun updateStatus() {
        val exo = player ?: return
        val status = statusView ?: return
        val duration = exo.duration.takeIf { it > 0L }
        val position = exo.currentPosition.coerceAtLeast(0L)
        val state = when (exo.playbackState) {
            Player.STATE_BUFFERING -> "Buffering"
            Player.STATE_ENDED -> "Ended"
            Player.STATE_IDLE -> "Idle"
            else -> if (exo.isPlaying) "Playing" else "Paused"
        }
        val progress = if (duration != null) {
            "${format(position)} / ${format(duration)}"
        } else {
            format(position)
        }
        val sourcesHint = if (sources.size > 1) "   MENU sources (${sources.size})" else ""
        status.text = "$state   $progress\nOK play/pause   ◀ ▶ ±10 s   Back exits$sourcesHint"
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    updateStatus()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                    updateStatus()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    exo.seekTo(exo.currentPosition + 10_000L)
                    updateStatus()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_MENU,
                android.view.KeyEvent.KEYCODE_INFO -> {
                    if (sources.size > 1) showSourceChooser() else updateStatus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
