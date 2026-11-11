package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Addon catalogs exposed as ordinary guide channels, so the Addons group behaves like a playlist:
 * the same channel rows and EPG column, full screen, channel surfing, stream menu, Recently
 * watched and the player's channel lists. Only the catalog item is loaded here; its stream is
 * resolved on first play (see LiveTvPlaybackController's addon: URL).
 */
@Singleton
class AddonGuideRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        scope.launch {
            addonRepository.getInstalledAddons()
                .debounce(400)
                .collectLatest { addons ->
                    load(addons)
                }
        }
    }

    private suspend fun load(addons: List<com.nuvio.tv.domain.model.Addon>) {
        val catalogs = addons
            .filter { it.enabled }
            .flatMap { addon ->
                addon.catalogs
                    .filter { catalog ->
                        catalog.apiType.lowercase() in WANTED_TYPES && !catalog.isSearchOnlyCatalog()
                    }
                    .map { catalog -> addon to catalog }
            }
            .distinctBy { (addon, catalog) -> "${addon.id}|${catalog.apiType}|${catalog.id}" }

        val out = ArrayList<LiveChannel>()
        for ((addon, catalog) in catalogs) {
            val result = runCatching {
                withTimeoutOrNull(20_000) {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        posterScreen = CustomPosterScreen.HOME,
                    ).first { it !is NetworkResult.Loading }
                }
            }.getOrNull() ?: continue
            val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
            items.take(MAX_ITEMS_PER_CATALOG).forEach { item ->
                val url = "addon:${addon.id}:${item.apiType}:${item.id}"
                out += LiveChannel(
                    key = url,
                    sourceId = addon.baseUrl,
                    sourceName = addon.displayName,
                    name = item.name,
                    tvgId = null,
                    tvgName = null,
                    logo = item.logo ?: item.poster,
                    groupId = ChannelGroup.ADDONS,
                    group = catalog.name,
                    number = out.size + 1,
                    url = url,
                )
            }
        }
        _channels.value = out.distinctBy { it.key }
    }

    private companion object {
        val WANTED_TYPES = setOf("live", "tv", "other", "sport", "sports")
        const val MAX_ITEMS_PER_CATALOG = 300
    }
}
