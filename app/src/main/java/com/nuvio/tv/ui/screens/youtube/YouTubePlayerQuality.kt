package com.nuvio.tv.ui.screens.youtube

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/** One resolution the current YouTube DASH stream offers. */
internal data class YouTubeQuality(
    val height: Int,
    val width: Int,
    val bitrate: Int,
    val frameRate: Float,
    val codec: String?,
    val trackGroup: TrackGroup,
    val trackIndex: Int,
) {
    val label: String
        get() = if (height > 0) "${height}p" else "Video"

    /** The bitrate in Mbps, for the quality list. */
    val bitrateText: String
        get() = if (bitrate <= 0) {
            ""
        } else {
            String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f)
        }

    /** The second line under the resolution. */
    val summary: String
        get() = listOfNotNull(
            bitrateText.takeIf { it.isNotBlank() },
            codec?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Video" }
}

/** The distinct resolutions in the current stream, best first. */
internal fun ExoPlayer.youtubeQualities(): List<YouTubeQuality> {
    val best = LinkedHashMap<Int, YouTubeQuality>()
    currentTracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val height = format.height.takeIf { it > 0 } ?: continue
            val candidate = YouTubeQuality(
                height = height,
                width = format.width,
                bitrate = format.bitrate.coerceAtLeast(0),
                frameRate = format.frameRate,
                codec = format.codecs,
                trackGroup = group.mediaTrackGroup,
                trackIndex = i,
            )
            val current = best[height]
            if (current == null || candidate.bitrate > current.bitrate) best[height] = candidate
        }
    }
    return best.values.sortedByDescending { it.height }
}

/**
 * Pins the video to [height], or hands it back to ABR with [height] = 0. Picks the requested size
 * when it exists, otherwise the next smaller one, then the nearest. Returns false until the
 * stream's video tracks are ready, so the caller can try again.
 */
internal fun ExoPlayer.applyYouTubeQuality(height: Int): Boolean {
    val hasVideo = currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
    if (!hasVideo) return false

    val builder = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
    if (height <= 0) {
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        trackSelectionParameters = builder.build()
        return true
    }

    val options = youtubeQualities()
    val target = options.firstOrNull { it.height == height }
        ?: options.firstOrNull { it.height < height }
        ?: options.minByOrNull { abs(it.height - height) }
        ?: return false
    builder.setOverrideForType(TrackSelectionOverride(target.trackGroup, target.trackIndex))
    trackSelectionParameters = builder.build()
    return true
}
