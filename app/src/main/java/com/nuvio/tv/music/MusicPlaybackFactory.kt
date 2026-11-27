package com.nuvio.tv.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.io.File

/** Builds the ExoPlayer used by the Music player and the YouTube video screens. */
object MusicPlaybackFactory {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    fun create(
        context: Context,
        stream: MusicStream,
        audioOnly: Boolean,
        capVideoForTv: Boolean = false,
    ): ExoPlayer {
        // Music videos should not chase 4K/AV1 on weak TV decoders. H.264 1080p is the
        // most compatible shape for Amlogic and similar boxes, and the bigger read buffer
        // absorbs the bursts from YouTube's CDN. Other video screens keep their own cap.
        val lowRamDevice = com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
            .getDevicePhysicalRamGb(context) <= 2.3
        val trackSelector = DefaultTrackSelector(context).apply {
            val parameters = buildUponParameters()
                .setMaxAudioBitrate(256_000)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
            if (capVideoForTv || lowRamDevice) {
                parameters
                    .setMaxVideoSize(1_920, 1_080)
                    .setMaxVideoBitrate(8_000_000)
                    .setMaxVideoFrameRate(60)
                    .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264)
                    .setExceedRendererCapabilitiesIfNecessary(false)
            }
            setParameters(parameters)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 256 * 1024))
            .setBufferDurationsMs(20_000, 60_000, 3_000, 8_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (audioOnly) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // Two players overlap during the music crossfade, so neither holds exclusive audio focus.
        player.setAudioAttributes(attrs, !audioOnly)
        player.setHandleAudioBecomingNoisy(true)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val factory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, http),
        )
        val videoUrl = stream.videoUrl
        val audioUrl = stream.audioUrl
        // A DASH manifest already carries its own audio: merging another audio source breaks it.
        val isManifest = videoUrl.endsWith(".mpd", ignoreCase = true) ||
            videoUrl.contains(".m3u8", ignoreCase = true)
        if (audioOnly || audioUrl.isNullOrBlank() || audioUrl == videoUrl || isManifest) {
            val url = if (audioOnly) audioUrl ?: videoUrl else videoUrl
            player.setMediaSource(factory.createMediaSource(mediaItem(url)))
        } else {
            val videoSource = factory.createMediaSource(mediaItem(videoUrl))
            val audioSource = factory.createMediaSource(mediaItem(audioUrl))
            player.setMediaSource(MergingMediaSource(videoSource, audioSource))
        }
        return player
    }

    private fun mediaItem(url: String): MediaItem =
        if (url.startsWith("http://") || url.startsWith("https://")) {
            MediaItem.fromUri(url)
        } else {
            MediaItem.fromUri(Uri.fromFile(File(url)))
        }
}
