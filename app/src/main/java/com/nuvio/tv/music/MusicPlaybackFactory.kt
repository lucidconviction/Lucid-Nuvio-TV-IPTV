package com.nuvio.tv.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import java.io.File

/** Builds the ExoPlayer used by the Music player and the YouTube video screens. */
object MusicPlaybackFactory {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    fun create(context: Context, stream: MusicStream, audioOnly: Boolean): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()
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
