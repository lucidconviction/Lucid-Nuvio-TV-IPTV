package com.nuvio.tv.ui.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.music.MusicPlaybackController
import com.nuvio.tv.music.MusicPlayerRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicMiniPlayerEntryPoint {
    fun playerRepository(): MusicPlayerRepository
    fun playbackController(): MusicPlaybackController
}

/**
 * TV version of the mobile floating music bar. The whole bar is one D-pad target: OK opens
 * the full player, and the remote's media keys control playback from anywhere. The icons show
 * the current state so the bar stays readable without splitting focus between tiny buttons.
 */
@Composable
fun MusicMiniPlayer(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entry = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MusicMiniPlayerEntryPoint::class.java,
        )
    }
    val player = entry.playerRepository()
    val playback = entry.playbackController()
    val current by player.current.collectAsStateWithLifecycle()
    val session by playback.session.collectAsStateWithLifecycle()
    val track = current ?: return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 980.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.97f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = NuvioTheme.colors.TextPrimary,
                    style = NuvioTheme.textStyles.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    color = NuvioTheme.colors.TextSecondary,
                    style = NuvioTheme.textStyles.metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (session.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = NuvioTheme.colors.TextPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        if (session.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (session.playing) "Pause" else "Play",
                        tint = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        }
    }
}
