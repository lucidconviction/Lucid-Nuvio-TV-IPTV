package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.music.YouTubeMusicService
import com.nuvio.tv.music.YouTubeMusicVideo
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.ui.components.glassSurface
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.youtube.YouTubePlaybackSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SportsReplayRow(val title: String, val videos: List<YouTubeMusicVideo>)

@HiltViewModel
class SportsReplaysViewModel @Inject constructor(
    private val youtube: YouTubeMusicService,
) : ViewModel() {
    private val _rows = MutableStateFlow<List<SportsReplayRow>>(emptyList())
    val rows: StateFlow<List<SportsReplayRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadedAt = 0L

    fun load(force: Boolean = false) {
        if (_loading.value) return
        if (!force && _rows.value.isNotEmpty() && System.currentTimeMillis() - loadedAt < 30 * 60_000L) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    SPORTS.map { sport ->
                        async {
                            SportsReplayRow(
                                title = sport,
                                videos = youtube.search("$sport highlights 2026", 8),
                            )
                        }
                    }.map { it.await() }.filter { it.videos.isNotEmpty() }
                }
            }.onSuccess {
                _rows.value = it
                loadedAt = System.currentTimeMillis()
            }.onFailure {
                _error.value = it.message ?: "Couldn't load replays."
            }
            _loading.value = false
        }
    }

    private companion object {
        val SPORTS = listOf(
            "NFL", "NBA", "NHL", "MLB", "Premier League", "Formula 1", "UFC", "BKFC", "Tennis",
        )
    }
}

@Composable
fun SportsReplaysScreen(
    onBack: () -> Unit,
    onPlay: (videoId: String, title: String) -> Unit,
    viewModel: SportsReplaysViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(NuvioTheme.colors.Secondary.copy(alpha = 0.10f), Color.Transparent),
                ),
            ),
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Button(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            Column {
                Text("Sports Replays", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                Text(
                    "Highlights and recaps from YouTube",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        when {
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
            error != null && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = NuvioTheme.colors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { viewModel.load(force = true) }) { Text("Try again") }
                }
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No replays found right now.", color = NuvioTheme.colors.TextSecondary)
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                items(rows, key = { it.title }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(row.title, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(row.videos, key = { it.videoId }) { video ->
                                ReplayCard(video) {
                                    YouTubePlaybackSession.set(
                                        row.videos.map {
                                            YouTubePlaybackSession.Entry(it.videoId, it.title, it.thumbnail, it.author)
                                        },
                                        video.videoId,
                                    )
                                    onPlay(video.videoId, video.title)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ReplayCard(video: YouTubeMusicVideo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(300.dp),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.82f),
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard),
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                video.title,
                style = NuvioTheme.textStyles.cardTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            Text(
                video.author.orEmpty(),
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
            )
        }
    }
}
