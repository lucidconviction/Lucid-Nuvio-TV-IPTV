package com.nuvio.tv.ui.screens.cloudstream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.lucid.LucidPluginBridge
import com.nuvio.tv.livetv.lucid.LucidPluginDetail
import com.nuvio.tv.livetv.lucid.LucidPluginExtension
import com.nuvio.tv.livetv.lucid.LucidPluginItem
import com.nuvio.tv.livetv.lucid.LucidPluginTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A resolved CloudStream link ready for the normal TV player. */
data class CloudStreamPlayback(
    val url: String,
    val headers: Map<String, String>,
    val title: String,
    val poster: String?,
    val contentType: String,
    val season: Int?,
    val episode: Int?,
    val providerName: String,
)

data class CloudStreamGlobalResult(
    val provider: LucidPluginExtension,
    val item: LucidPluginItem,
)

data class CloudStreamUiState(
    val providers: List<LucidPluginExtension> = emptyList(),
    val providersLoading: Boolean = true,
    val provider: LucidPluginExtension? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val tabs: List<LucidPluginTab> = emptyList(),
    val activeTab: LucidPluginTab? = null,
    val items: List<LucidPluginItem> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val detail: LucidPluginDetail? = null,
    val detailLoading: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<LucidPluginItem> = emptyList(),
    val searching: Boolean = false,
    val globalQuery: String = "",
    val globalSearching: Boolean = false,
    val globalResults: List<CloudStreamGlobalResult> = emptyList(),
)

@HiltViewModel
class CloudStreamViewModel @Inject constructor(
    private val bridge: LucidPluginBridge,
) : ViewModel() {
    private val _state = MutableStateFlow(CloudStreamUiState())
    val state: StateFlow<CloudStreamUiState> = _state.asStateFlow()

    private val _playback = MutableSharedFlow<CloudStreamPlayback>(extraBufferCapacity = 1)
    val playback: SharedFlow<CloudStreamPlayback> = _playback.asSharedFlow()

    init {
        loadProviders()
    }

    fun loadProviders() {
        if (!_state.value.providersLoading && _state.value.providers.isNotEmpty()) return
        _state.value = _state.value.copy(providersLoading = true, error = null)
        viewModelScope.launch {
            val providers = runCatching { bridge.extensions() }.getOrDefault(emptyList())
                .sortedBy { it.name.lowercase() }
            _state.value = _state.value.copy(providers = providers, providersLoading = false)
        }
    }

    fun openProvider(provider: LucidPluginExtension) {
        _state.value = _state.value.copy(
            provider = provider,
            items = emptyList(),
            page = 0,
            hasNext = false,
            tabs = emptyList(),
            activeTab = null,
            detail = null,
            searchQuery = "",
            searchResults = emptyList(),
            error = null,
        )
        loadPage(1, null)
    }

    fun backToProviders() {
        _state.value = _state.value.copy(
            provider = null,
            items = emptyList(),
            tabs = emptyList(),
            activeTab = null,
            detail = null,
            searchQuery = "",
            searchResults = emptyList(),
            error = null,
            loading = false,
        )
    }

    fun selectTab(tab: LucidPluginTab) {
        if (_state.value.activeTab == tab) return
        _state.value = _state.value.copy(
            activeTab = tab,
            items = emptyList(),
            page = 0,
            hasNext = false,
            searchQuery = "",
            searchResults = emptyList(),
        )
        loadPage(1, tab)
    }

    fun loadMore() {
        val value = _state.value
        if (value.loading || !value.hasNext || value.provider == null) return
        loadPage(value.page + 1, value.activeTab)
    }

    private fun loadPage(page: Int, tab: LucidPluginTab?) {
        val provider = _state.value.provider ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { bridge.browseLatest(provider.id, page, tab) }.getOrNull()
            val current = _state.value
            if (current.provider?.id != provider.id) return@launch
            if (result == null) {
                _state.value = current.copy(
                    loading = false,
                    error = "This extension has no browse feed. Try Search.",
                )
                return@launch
            }
            _state.value = current.copy(
                loading = false,
                tabs = if (result.tabs.isNotEmpty()) result.tabs else current.tabs,
                activeTab = result.activeTab ?: tab ?: current.activeTab,
                items = if (page <= 1) result.items else (current.items + result.items).distinctBy { it.url },
                page = page,
                hasNext = result.hasNext,
                error = null,
            )
        }
    }

    fun search(query: String) {
        val provider = _state.value.provider ?: return
        _state.value = _state.value.copy(searchQuery = query, error = null)
        if (query.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        _state.value = _state.value.copy(searching = true)
        viewModelScope.launch {
            val results = runCatching { bridge.browseSearch(provider.id, query) }.getOrDefault(emptyList())
            if (_state.value.provider?.id != provider.id) return@launch
            _state.value = _state.value.copy(searching = false, searchResults = results)
        }
    }

    /** Search every enabled extension (used by Sports and the global CloudStream search). */
    fun searchAll(query: String, disabledProviderIds: Set<String> = emptySet()) {
        val trimmed = query.trim()
        _state.value = _state.value.copy(
            globalQuery = trimmed,
            globalSearching = trimmed.isNotBlank(),
            globalResults = emptyList(),
            error = null,
        )
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val providers = _state.value.providers.ifEmpty {
                runCatching { bridge.extensions() }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }
            }.filterNot { it.id in disabledProviderIds }
            if (providers.isEmpty()) {
                _state.value = _state.value.copy(globalSearching = false, globalResults = emptyList())
                return@launch
            }
            val results = coroutineScope {
                providers.map { provider ->
                    async {
                        runCatching { bridge.browseSearch(provider.id, trimmed) }
                            .getOrDefault(emptyList())
                            .map { CloudStreamGlobalResult(provider, it) }
                    }
                }.awaitAll().flatten()
            }
            _state.value = _state.value.copy(
                globalSearching = false,
                globalResults = results.distinctBy { "${it.provider.id}|${it.item.url}" },
            )
        }
    }

    fun openGlobalResult(result: CloudStreamGlobalResult) {
        // Show the provider's own page behind the detail, but open the selected item now.
        _state.value = _state.value.copy(
            provider = result.provider,
            items = emptyList(),
            page = 0,
            hasNext = false,
            tabs = emptyList(),
            activeTab = null,
            detail = null,
            error = null,
        )
        loadPage(1, null)
        openItem(result.item)
    }

    fun openItem(item: LucidPluginItem) {
        val provider = _state.value.provider ?: return
        _state.value = _state.value.copy(detailLoading = true, error = null)
        viewModelScope.launch {
            val detail = runCatching { bridge.detail(provider.id, item.apiIndex, item.url) }.getOrNull()
            if (_state.value.provider?.id != provider.id) return@launch
            if (detail == null) {
                _state.value = _state.value.copy(
                    detailLoading = false,
                    error = "This provider didn't return the item's details.",
                )
                return@launch
            }
            // Some extensions leave MovieLoadResponse.dataUrl empty. The item URL is the
            // same load() input, so it is the safe fallback for loadLinks().
            val withData = if (detail.isSeries) detail else detail.copy(data = detail.data ?: item.url)
            _state.value = _state.value.copy(detailLoading = false, detail = withData)
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null, detailLoading = false, error = null)
    }

    fun playDetail() {
        val provider = _state.value.provider ?: return
        val detail = _state.value.detail ?: return
        if (detail.isSeries) return
        val data = detail.data
        if (data.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "This provider returned no playable data for the movie.")
            return
        }
        play(provider, detail.apiIndex, data, detail.name, detail.poster, null, null)
    }

    fun playEpisode(episode: LucidPluginItem) {
        val provider = _state.value.provider ?: return
        val detail = _state.value.detail ?: return
        play(
            provider = provider,
            apiIndex = episode.apiIndex,
            data = episode.url,
            title = listOfNotNull(detail.name, episode.name).joinToString(" · "),
            poster = episode.poster ?: detail.poster,
            season = episode.season,
            episode = episode.episode,
        )
    }

    private fun play(
        provider: LucidPluginExtension,
        apiIndex: Int,
        data: String,
        title: String,
        poster: String?,
        season: Int?,
        episode: Int?,
    ) {
        _state.value = _state.value.copy(detailLoading = true, error = null)
        viewModelScope.launch {
            val links = runCatching {
                bridge.streams(provider.id, apiIndex, data, season, episode)
            }.getOrDefault(emptyList())
            if (_state.value.provider?.id != provider.id) return@launch
            val link = links.firstOrNull { it.url.startsWith("http", ignoreCase = true) }
                ?: links.firstOrNull()
            if (link == null) {
                _state.value = _state.value.copy(
                    detailLoading = false,
                    error = "No playable link was returned for this item.",
                )
                return@launch
            }
            _state.value = _state.value.copy(detailLoading = false)
            _playback.tryEmit(
                CloudStreamPlayback(
                    url = link.url,
                    headers = link.headers,
                    title = title,
                    poster = poster,
                    contentType = if (season != null || episode != null) "tv" else "movie",
                    season = season,
                    episode = episode,
                    providerName = provider.name,
                )
            )
        }
    }
}
