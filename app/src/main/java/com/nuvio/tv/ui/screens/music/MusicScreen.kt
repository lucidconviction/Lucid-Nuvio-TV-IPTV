@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.music.LyricsData
import com.nuvio.tv.music.MusicAlbum
import com.nuvio.tv.music.MusicAlbumDetails
import com.nuvio.tv.music.MusicArtist
import com.nuvio.tv.music.MusicArtistDetails
import com.nuvio.tv.music.MusicDownloadsRepository
import com.nuvio.tv.music.MusicGenre
import com.nuvio.tv.music.MusicHomeData
import com.nuvio.tv.music.MusicLibraryRepository
import com.nuvio.tv.music.MusicPlaylist
import com.nuvio.tv.music.MusicPlaylistDetails
import com.nuvio.tv.music.MusicRepository
import com.nuvio.tv.music.MusicSearchData
import com.nuvio.tv.music.MusicTrack
import com.nuvio.tv.music.MusicViewModel
import com.nuvio.tv.music.UserMusicPlaylist
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MusicTab(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    BROWSE("Browse"),
    LIBRARY("Library"),
    DOWNLOADS("Downloads"),
}

private sealed interface MusicDestination {
    data class Artist(val id: String) : MusicDestination
    data class Album(val id: String) : MusicDestination
    data class Playlist(val id: String) : MusicDestination
    data class LocalPlaylist(val id: String) : MusicDestination
    data class Genre(val genre: MusicGenre) : MusicDestination
}

/** The Music page: browse, search, your library, downloads and the entry to the player. */
@Composable
fun MusicScreen(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val loading by viewModel.loadingHome.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MusicTab.HOME) }
    var destination by remember { mutableStateOf<MusicDestination?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadHome() }
    BackHandler(enabled = destination != null) { destination = null }

    val detail = destination
    if (detail != null) {
        MusicDetail(
            destination = detail,
            viewModel = viewModel,
            onBack = { destination = null },
            onOpenDestination = { destination = it },
            onOpenPlayer = onOpenPlayer,
            modifier = modifier,
        )
        return
    }

    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(home) {
        if (home != null) {
            delay(150)
            runCatching { firstTabFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Music", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                Text(
                    "Songs, artists, albums and videos",
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            MusicModeToggle(viewModel.player)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MusicTab.entries.forEachIndexed { index, entry ->
                MusicChip(
                    label = entry.label,
                    selected = tab == entry,
                    focusRequester = if (index == 0) firstTabFocus else null,
                    onClick = { tab = entry },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (loading && home == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
        } else if (error != null && home == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = NuvioTheme.colors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.clearError(); viewModel.loadHome(force = true) }) {
                        Text("Try again")
                    }
                }
            }
        } else {
            when (tab) {
                MusicTab.HOME -> MusicHome(home ?: MusicHomeData(), viewModel, onOpenPlayer, onOpenDestination = { destination = it })
                MusicTab.SEARCH -> MusicSearchContent(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    viewModel = viewModel,
                    onOpenDestination = { destination = it },
                    onOpenPlayer = onOpenPlayer,
                )
                MusicTab.BROWSE -> MusicBrowse(home ?: MusicHomeData(), onOpenDestination = { destination = it })
                MusicTab.LIBRARY -> MusicLibraryContent(viewModel, onOpenDestination = { destination = it }, onOpenPlayer = onOpenPlayer)
                MusicTab.DOWNLOADS -> MusicDownloadsContent(viewModel, onOpenPlayer = onOpenPlayer)
            }
        }
    }
}

@Composable
private fun MusicModeToggle(player: com.nuvio.tv.music.MusicPlayerRepository) {
    val audioOnly by player.audioOnly.collectAsStateWithLifecycle()
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MusicChip(label = "Video", selected = !audioOnly, onClick = { player.setAudioOnly(false) })
        MusicChip(label = "Audio", selected = audioOnly, onClick = { player.setAudioOnly(true) })
    }
}

@Composable
private fun MusicChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Button(
        onClick = onClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
            focusedContainerColor = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (selected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, style = NuvioTheme.textStyles.tab)
    }
}

@Composable
private fun MusicHome(
    home: MusicHomeData,
    viewModel: MusicViewModel,
    onOpenPlayer: () -> Unit,
    onOpenDestination: (MusicDestination) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        val featured = home.trending.firstOrNull()
        if (featured != null) {
            item("hero") {
                MusicHero(featured, queue = home.trending) {
                    viewModel.play(featured, home.trending)
                    onOpenPlayer()
                }
            }
        }
        if (home.trending.isNotEmpty()) {
            item("trending") {
                MusicSection("Trending songs") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(home.trending, key = { it.id }) { track ->
                            MusicTrackCard(track) {
                                viewModel.play(track, home.trending)
                                onOpenPlayer()
                            }
                        }
                    }
                }
            }
        }
        if (home.youtubeVideos.isNotEmpty()) {
            item("videos") {
                MusicSection("Music videos") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(home.youtubeVideos, key = { it.id }) { track ->
                            MusicTrackCard(track) {
                                viewModel.play(track, home.youtubeVideos)
                                onOpenPlayer()
                            }
                        }
                    }
                }
            }
        }
        if (home.newReleases.isNotEmpty()) {
            item("releases") {
                MusicSection("New releases") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(home.newReleases, key = { it.id }) { album ->
                            MusicAlbumCard(album) { onOpenDestination(MusicDestination.Album(album.id)) }
                        }
                    }
                }
            }
        }
        if (home.artists.isNotEmpty()) {
            item("artists") {
                MusicSection("Artists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(home.artists, key = { it.id }) { artist ->
                            MusicArtistCard(artist) { onOpenDestination(MusicDestination.Artist(artist.id)) }
                        }
                    }
                }
            }
        }
        if (home.playlists.isNotEmpty()) {
            item("playlists") {
                MusicSection("Playlists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(home.playlists, key = { it.id }) { playlist ->
                            MusicPlaylistCard(playlist) { onOpenDestination(MusicDestination.Playlist(playlist.id)) }
                        }
                    }
                }
            }
        }
        if (home.genres.isNotEmpty()) {
            item("genres") {
                MusicSection("Browse by genre") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home.genres, key = { it.id }) { genre ->
                            MusicGenreCard(genre) { onOpenDestination(MusicDestination.Genre(genre)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicHero(track: MusicTrack, queue: List<MusicTrack>, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth().height(260.dp),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.lg)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.86f), Color.Black.copy(alpha = 0.16f)),
                    ),
                ),
            )
            Column(
                Modifier.align(Alignment.CenterStart).padding(32.dp).fillMaxWidth(0.6f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("FEATURED", style = NuvioTheme.textStyles.badge, color = NuvioTheme.colors.TextSecondary)
                Text(
                    track.title,
                    style = NuvioTheme.textStyles.headline,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(track.artist, track.album.takeIf { it.isNotBlank() && it != "Single" })
                        .filterNotNull().joinToString(" · "),
                    style = NuvioTheme.textStyles.body,
                    color = NuvioTheme.colors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
            }
        }
    }
}

@Composable
private fun MusicSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
        content()
    }
}

@Composable
private fun MusicTrackCard(track: MusicTrack, onClick: () -> Unit) {
    MusicCard(
        title = track.title,
        subtitle = track.artist,
        imageUrl = track.thumbnail,
        badge = track.displayDuration,
        wide = true,
        onClick = onClick,
    )
}

@Composable
private fun MusicAlbumCard(album: MusicAlbum, onClick: () -> Unit) {
    MusicCard(
        title = album.title,
        subtitle = album.artistName,
        imageUrl = album.coverUrl,
        wide = false,
        onClick = onClick,
    )
}

@Composable
private fun MusicArtistCard(artist: MusicArtist, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        shape = CardDefaults.shape(CircleShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = artist.pictureUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(180.dp).clip(CircleShape),
            )
            Text(
                artist.name,
                style = NuvioTheme.textStyles.cardTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MusicPlaylistCard(playlist: MusicPlaylist, onClick: () -> Unit) {
    MusicCard(
        title = playlist.title,
        subtitle = playlist.creatorName.ifBlank { "${playlist.trackCount} songs" },
        imageUrl = playlist.coverUrl,
        wide = false,
        onClick = onClick,
    )
}

@Composable
private fun MusicGenreCard(genre: MusicGenre, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(150.dp).height(56.dp),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                genre.name,
                style = NuvioTheme.textStyles.cardTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun MusicCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    badge: String? = null,
    wide: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(if (wide) 280.dp else 190.dp),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (wide) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.BackgroundCard),
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!badge.isNullOrBlank()) {
                    Text(
                        badge,
                        style = NuvioTheme.textStyles.badge,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                title,
                style = NuvioTheme.textStyles.cardTitle,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MusicTrackRow(track: MusicTrack, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        scale = CardDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(NuvioTheme.colors.BackgroundCard),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = NuvioTheme.textStyles.cardTitle,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = NuvioTheme.textStyles.metadata,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track.displayDuration.isNotBlank()) {
                Text(track.displayDuration, style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextTertiary)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun MusicSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    viewModel: MusicViewModel,
    onOpenDestination: (MusicDestination) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<MusicSearchData?>(null) }
    var searching by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fieldFocus.requestFocus() } }

    fun search() {
        if (query.isBlank()) return
        searching = true
        scope.launch {
            results = runCatching { viewModel.repository.search(query) }.getOrDefault(MusicSearchData())
            searching = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search songs, artists, albums or playlists") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
                    unfocusedContainerColor = NuvioTheme.colors.BackgroundElevated,
                    focusedTextColor = NuvioTheme.colors.TextPrimary,
                    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                    cursorColor = NuvioTheme.colors.TextPrimary,
                ),
            )
            Button(onClick = ::search) { Text("Search") }
        }
        if (searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
            }
        } else {
            val data = results
            if (data == null) {
                Text("Search the catalogue for a song, artist, album or playlist.", color = NuvioTheme.colors.TextSecondary)
            } else if (data.isEmpty) {
                Text("Nothing found for \"$query\".", color = NuvioTheme.colors.TextSecondary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    if (data.tracks.isNotEmpty()) {
                        item { MusicSection("Songs") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(data.tracks, key = { it.id }) { track -> MusicTrackCard(track) { viewModel.play(track, data.tracks); onOpenPlayer() } } } } }
                    }
                    if (data.artists.isNotEmpty()) {
                        item { MusicSection("Artists") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(data.artists, key = { it.id }) { artist -> MusicArtistCard(artist) { onOpenDestination(MusicDestination.Artist(artist.id)) } } } } }
                    }
                    if (data.albums.isNotEmpty()) {
                        item { MusicSection("Albums") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(data.albums, key = { it.id }) { album -> MusicAlbumCard(album) { onOpenDestination(MusicDestination.Album(album.id)) } } } } }
                    }
                    if (data.playlists.isNotEmpty()) {
                        item { MusicSection("Playlists") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(data.playlists, key = { it.id }) { playlist -> MusicPlaylistCard(playlist) { onOpenDestination(MusicDestination.Playlist(playlist.id)) } } } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicBrowse(home: MusicHomeData, onOpenDestination: (MusicDestination) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { MusicSection("Genres") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(home.genres, key = { it.id }) { genre -> MusicGenreCard(genre) { onOpenDestination(MusicDestination.Genre(genre)) } } } } }
        item { MusicSection("New releases") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(home.newReleases, key = { it.id }) { album -> MusicAlbumCard(album) { onOpenDestination(MusicDestination.Album(album.id)) } } } } }
        item { MusicSection("Playlists") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(home.playlists, key = { it.id }) { playlist -> MusicPlaylistCard(playlist) { onOpenDestination(MusicDestination.Playlist(playlist.id)) } } } } }
    }
}

@Composable
private fun MusicLibraryContent(
    viewModel: MusicViewModel,
    onOpenDestination: (MusicDestination) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val favorites by viewModel.library.favorites.collectAsStateWithLifecycle()
    val artists by viewModel.library.favoriteArtists.collectAsStateWithLifecycle()
    val albums by viewModel.library.favoriteAlbums.collectAsStateWithLifecycle()
    val playlists by viewModel.library.playlists.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your library", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.library.createPlaylist("My Playlist ${playlists.size + 1}") }) {
                    Text("New playlist")
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { MusicSection("Liked songs") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(favorites, key = { it.id }) { track -> MusicTrackCard(track) { viewModel.play(track, favorites); onOpenPlayer() } } } } }
        }
        if (playlists.isNotEmpty()) {
            item {
                MusicSection("Your playlists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            MusicCard(playlist.title, "${playlist.tracks.size} songs", playlist.tracks.firstOrNull()?.thumbnail, wide = false) {
                                onOpenDestination(MusicDestination.LocalPlaylist(playlist.id))
                            }
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { MusicSection("Artists") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(artists, key = { it.id }) { artist -> MusicArtistCard(artist) { onOpenDestination(MusicDestination.Artist(artist.id)) } } } } }
        }
        if (albums.isNotEmpty()) {
            item { MusicSection("Albums") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(albums, key = { it.id }) { album -> MusicAlbumCard(album) { onOpenDestination(MusicDestination.Album(album.id)) } } } } }
        }
        if (favorites.isEmpty() && playlists.isEmpty() && artists.isEmpty() && albums.isEmpty()) {
            item { Text("Like songs or save artists and albums to build your library.", color = NuvioTheme.colors.TextSecondary) }
        }
    }
}

@Composable
private fun MusicDownloadsContent(viewModel: MusicViewModel, onOpenPlayer: () -> Unit) {
    val downloads by viewModel.library.downloads.collectAsStateWithLifecycle()
    val message by viewModel.downloads.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { if (message != null) viewModel.downloads.clearMessage() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Downloads", style = NuvioTheme.textStyles.sectionTitle, color = NuvioTheme.colors.TextPrimary)
        Text("Downloaded songs play without a connection.", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
        if (downloads.isEmpty()) {
            Text("No downloads yet. Press the download button in the player.", color = NuvioTheme.colors.TextSecondary)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(downloads, key = { it.track.id }) { entry ->
                    MusicTrackRow(entry.track, onClick = { viewModel.play(entry.track, downloads.map { it.track }); onOpenPlayer() }) {
                        Button(onClick = { viewModel.library.removeDownloaded(entry.track.id) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicDetail(
    destination: MusicDestination,
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onOpenDestination: (MusicDestination) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
            Text("Music", style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
        }
        Spacer(Modifier.height(16.dp))
        when (destination) {
            is MusicDestination.Artist -> ArtistDetail(destination.id, viewModel, onOpenDestination, onOpenPlayer)
            is MusicDestination.Album -> AlbumDetail(destination.id, viewModel, onOpenPlayer)
            is MusicDestination.Playlist -> PlaylistDetail(destination.id, viewModel, onOpenPlayer)
            is MusicDestination.LocalPlaylist -> LocalPlaylistDetail(destination.id, viewModel, onOpenPlayer)
            is MusicDestination.Genre -> GenreDetail(destination.genre, viewModel, onOpenPlayer)
        }
    }
}

@Composable
private fun ArtistDetail(
    artistId: String,
    viewModel: MusicViewModel,
    onOpenDestination: (MusicDestination) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val data by produceState<MusicArtistDetails?>(null, artistId) { value = viewModel.repository.artist(artistId) }
    val details = data
    if (details == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                AsyncImage(
                    model = details.artist.pictureUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(CircleShape).background(NuvioTheme.colors.BackgroundCard),
                )
                Column {
                    Text(details.artist.name, style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                    Text("${details.artist.fanCount} fans · ${details.artist.albumCount} albums", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        details.topTracks.firstOrNull()?.let { viewModel.play(it, details.topTracks); onOpenPlayer() }
                    }) { Text("Play top songs") }
                }
            }
        }
        if (details.topTracks.isNotEmpty()) {
            item { MusicSection("Top songs") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(details.topTracks, key = { it.id }) { track -> MusicTrackCard(track) { viewModel.play(track, details.topTracks); onOpenPlayer() } } } } }
        }
        if (details.albums.isNotEmpty()) {
            item { MusicSection("Albums") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(details.albums, key = { it.id }) { album -> MusicAlbumCard(album) { onOpenDestination(MusicDestination.Album(album.id)) } } } } }
        }
        if (details.relatedArtists.isNotEmpty()) {
            item { MusicSection("Related artists") { LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(details.relatedArtists, key = { it.id }) { artist -> MusicArtistCard(artist) { onOpenDestination(MusicDestination.Artist(artist.id)) } } } } }
        }
    }
}

@Composable
private fun AlbumDetail(albumId: String, viewModel: MusicViewModel, onOpenPlayer: () -> Unit) {
    val data by produceState<MusicAlbumDetails?>(null, albumId) { value = viewModel.repository.album(albumId) }
    val details = data
    if (details == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                AsyncImage(
                    model = details.album.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column {
                    Text(details.album.title, style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                    Text(details.album.artistName, style = NuvioTheme.textStyles.body, color = NuvioTheme.colors.TextSecondary)
                    Text(
                        listOf(details.album.releaseDate, "${details.album.trackCount} songs").filter { it.isNotBlank() }.joinToString(" · "),
                        style = NuvioTheme.textStyles.metadata,
                        color = NuvioTheme.colors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.playPlaylist(details.tracks); onOpenPlayer() }) { Text("Play album") }
                }
            }
        }
        itemsIndexed(details.tracks, key = { _, track -> track.id }) { index, track ->
            MusicTrackRow(track, onClick = {
                viewModel.player.play(track, details.tracks)
                onOpenPlayer()
            })
        }
    }
}

@Composable
private fun PlaylistDetail(playlistId: String, viewModel: MusicViewModel, onOpenPlayer: () -> Unit) {
    val data by produceState<MusicPlaylistDetails?>(null, playlistId) { value = viewModel.repository.playlist(playlistId) }
    val details = data
    if (details == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                AsyncImage(
                    model = details.playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column {
                    Text(details.playlist.title, style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                    Text(details.playlist.creatorName, style = NuvioTheme.textStyles.body, color = NuvioTheme.colors.TextSecondary)
                    Text("${details.playlist.trackCount} songs", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextTertiary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.playPlaylist(details.tracks); onOpenPlayer() }) { Text("Play playlist") }
                }
            }
        }
        itemsIndexed(details.tracks, key = { _, track -> track.id }) { _, track ->
            MusicTrackRow(track, onClick = { viewModel.player.play(track, details.tracks); onOpenPlayer() })
        }
    }
}

@Composable
private fun LocalPlaylistDetail(playlistId: String, viewModel: MusicViewModel, onOpenPlayer: () -> Unit) {
    val playlists by viewModel.library.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        Text("Playlist not found.", color = NuvioTheme.colors.TextSecondary)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.title, style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary)
                    Text("${playlist.tracks.size} songs", style = NuvioTheme.textStyles.metadata, color = NuvioTheme.colors.TextSecondary)
                }
                Button(onClick = { viewModel.playPlaylist(playlist.tracks); onOpenPlayer() }) { Text("Play") }
                Button(onClick = { viewModel.library.deletePlaylist(playlist.id) }) { Text("Delete") }
            }
        }
        itemsIndexed(playlist.tracks, key = { _, track -> track.id }) { _, track ->
            MusicTrackRow(track, onClick = { viewModel.player.play(track, playlist.tracks); onOpenPlayer() })
        }
    }
}

@Composable
private fun GenreDetail(genre: MusicGenre, viewModel: MusicViewModel, onOpenPlayer: () -> Unit) {
    val tracks by produceState<List<MusicTrack>>(emptyList(), genre.id) { value = viewModel.repository.genreTracks(genre) }
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Text(genre.name, style = NuvioTheme.textStyles.headline, color = NuvioTheme.colors.TextPrimary) }
        itemsIndexed(tracks, key = { _, track -> track.id }) { _, track ->
            MusicTrackRow(track, onClick = { viewModel.player.play(track, tracks); onOpenPlayer() })
        }
    }
}
