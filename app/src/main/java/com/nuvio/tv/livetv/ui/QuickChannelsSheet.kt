@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** A short remote-friendly channel list for the player and the guide: favorites, recent, then all. */
@Composable
fun QuickChannelsSheet(
    viewModel: LiveTvViewModel,
    currentKey: String?,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val channels by viewModel.displayChannels.collectAsStateWithLifecycle()
    val byKey = remember(channels) { channels.associateBy { it.key } }
    val ordered = remember(channels, user.favorites, user.recent) {
        val favorites = user.favorites.mapNotNull { byKey[it] }
        val recent = user.recent.mapNotNull { byKey[it] }
        (favorites + recent + channels).distinctBy { it.key }.take(200)
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.8f)
                .glassSurface(RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick channels", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                    Text(
                        "${ordered.size} channels · Back to close",
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
                Text(
                    "Favorites first, then recently watched",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextTertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(ordered, key = { it.key }) { channel ->
                    val playing = channel.key == currentKey
                    Button(
                        onClick = { onPick(channel) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (channel.key == ordered.firstOrNull()?.key) Modifier.focusRequester(firstFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (playing) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (playing) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Text(
                            "${channel.number}  ${channel.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (playing) "Playing" else channel.group,
                            style = NuvioTheme.textStyles.metadata,
                            color = if (playing) NuvioTheme.colors.TextInverse.copy(alpha = 0.7f) else NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
