package com.nuvio.tv.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import java.io.File

/** Builds the ExoPlayer used by the Music player and the YouTube video screens. */
object MusicPlaybackFactory {
    fun create(context: Context, stream: MusicStream, audioOnly: Boolean): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(if (audioOnly) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // Two players overlap during the music crossfade, so neither holds exclusive audio focus.
        player.setAudioAttributes(attrs, !audioOnly)
        player.setHandleAudioBecomingNoisy(true)
        val factory = DefaultMediaSourceFactory(context)
        val videoUrl = stream.videoUrl
        val audioUrl = stream.audioUrl
        if (audioOnly || audioUrl.isNullOrBlank() || audioUrl == videoUrl) {
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
