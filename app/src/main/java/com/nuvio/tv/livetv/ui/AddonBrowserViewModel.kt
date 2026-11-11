package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The guide's Addons group: catalogs from the installed addons that carry live, TV, other or
 * sports content, and the playable items in the selected catalog.
 */
@HiltViewModel
class AddonBrowserViewModel @Inject constructor(
    addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {

    data class CatalogEntry(
        val addon: Addon,
        val catalog: CatalogDescriptor,
    ) {
        val key: String = "${addon.id}|${catalog.apiType}|${catalog.id}"
    }

    data class UiState(
        val catalogs: List<CatalogEntry> = emptyList(),
        val loaded: Boolean = false,
        val selectedKey: String? = null,
        val items: List<MetaPreview> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { installed ->
                val catalogs = installed
                    .enabledAddons()
                    .flatMap { addon ->
                        addon.catalogs
                            .filter { it.apiType.lowercase() in WANTED_TYPES && !it.isSearchOnlyCatalog() }
                            .map { catalog -> CatalogEntry(addon, catalog) }
                    }
                    .distinctBy { it.key }
                _state.update { current ->
                    current.copy(
                        catalogs = catalogs,
                        loaded = true,
                        // A refresh can remove the catalog that was open.
                        selectedKey = current.selectedKey?.takeIf { key -> catalogs.any { it.key == key } },
                        items = if (current.selectedKey != null && catalogs.none { it.key == current.selectedKey }) {
                            emptyList()
                        } else {
                            current.items
                        },
                        error = if (current.selectedKey != null && catalogs.none { it.key == current.selectedKey }) {
                            null
                        } else {
                            current.error
                        },
                    )
                }
            }
        }
    }

    /** The first playable source for [item], fetched from the addon that supplied its catalog. */
    suspend fun resolvePreview(item: MetaPreview): Stream? {
        val entry = state.value.catalogs.firstOrNull { it.key == state.value.selectedKey } ?: return null
        return when (val result = streamRepository.getStreamsFromAddon(entry.addon, item.apiType, item.id)) {
            is NetworkResult.Success -> result.data.firstOrNull { it.getStreamUrl() != null }
            else -> null
        }
    }

    fun selectCatalog(entry: CatalogEntry) {
        if (_state.value.selectedKey == entry.key && (_state.value.loading || _state.value.items.isNotEmpty())) return
        loadJob?.cancel()
        _state.update {
            it.copy(selectedKey = entry.key, loading = true, error = null, items = emptyList())
        }
        loadJob = viewModelScope.launch {
            catalogRepository.getCatalog(
                addonBaseUrl = entry.addon.baseUrl,
                addonId = entry.addon.id,
                addonName = entry.addon.displayName,
                catalogId = entry.catalog.id,
                catalogName = entry.catalog.name,
                type = entry.catalog.apiType,
                posterScreen = CustomPosterScreen.HOME,
            ).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> _state.update { it.copy(loading = true, error = null) }
                    is NetworkResult.Success -> _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            items = result.data.items,
                        )
                    }
                    is NetworkResult.Error -> _state.update {
                        it.copy(loading = false, error = result.message, items = emptyList())
                    }
                }
            }
        }
    }

    private companion object {
        /** The catalog types this group is for. "sports" is accepted as an alias of "sport". */
        val WANTED_TYPES = setOf("live", "tv", "other", "sport", "sports")
    }
}
