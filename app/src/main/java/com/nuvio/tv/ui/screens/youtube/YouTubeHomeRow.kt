@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "Continue watching on YouTube" on Home. A focused card opens the full-screen YouTube player
 * and picks the video up where it stopped.
 */
@Composable
fun YouTubeHomeRow(
    startPadding: Dp = 48.dp,
    titleStyle: TextStyle? = null,
    viewModel: YouTubeHistoryViewModel = hiltViewModel(),
) {
    val entries by viewModel.history.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Continue watching on YouTube",
            style = titleStyle ?: NuvioTheme.textStyles.sectionTitle,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = startPadding, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(start = startPadding, end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(entries, key = { it.videoId }) { entry ->
                Card(
                    onClick = { YouTubeOpen.open?.invoke(entry.videoId, entry.title) },
                    modifier = Modifier.width(300.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.05f),
                ) {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().height(169.dp)
                                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                                .background(NuvioTheme.colors.BackgroundCard),
                        ) {
                            AsyncImage(
                                model = entry.thumbnail
                                    ?.replace("/hqdefault.jpg", "/mqdefault.jpg")
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "https://i.ytimg.com/vi/${entry.videoId}/mqdefault.jpg",
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(169.dp),
                            )
                            Box(
                                Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                                    .fillMaxWidth().height(4.dp)
                                    .background(Color.White.copy(alpha = 0.24f)),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(entry.progress).height(4.dp)
                                        .background(NuvioTheme.colors.TextPrimary),
                                )
                            }
                            if (entry.positionMs > 0L) {
                                Text(
                                    formatHomeTime(entry.positionMs),
                                    style = NuvioTheme.textStyles.badge,
                                    color = Color.White,
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            entry.title,
                            style = NuvioTheme.textStyles.cardTitle,
                            color = NuvioTheme.colors.TextPrimary,
                            // Reserve two lines on every card, so one- and two-line titles make
                            // the same card size instead of a ragged row of thumbnails.
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                        )
                        Text(
                            entry.channel,
                            style = NuvioTheme.textStyles.metadata,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatHomeTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
