package com.nuvio.tv.livetv.ui.lucid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.lucid.LucidChannelKind
import com.nuvio.tv.livetv.lucid.LucidOrder
import com.nuvio.tv.livetv.lucid.LucidSpecCodec
import com.nuvio.tv.livetv.lucid.LucidStoredItem
import com.nuvio.tv.livetv.lucid.LucidStoredSpec
import com.nuvio.tv.livetv.lucid.LucidTvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/** One relay group the studio can turn into a 24/7 channel. */
data class LucidRelayGroup(
    val id: String,
    val name: String,
    val channelCount: Int,
)

@HiltViewModel
class LucidTvStudioViewModel @Inject constructor(
    private val prefs: LiveTvPreferences,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    liveTvRepository: LiveTvRepository,
    val lucid: LucidTvRepository,
) : ViewModel() {

    val specs: StateFlow<List<LucidStoredSpec>> = prefs.lucidChannels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val addons: StateFlow<List<Addon>> = addonRepository.getInstalledAddons()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val relayGroups: StateFlow<List<LucidRelayGroup>> = liveTvRepository.channels
        .combine(prefs.lucidChannels) { channels, _ ->
            channels.groupBy { it.groupId }
                .map { (id, list) ->
                    LucidRelayGroup(id = id, name = list.first().group, channelCount = list.size)
                }
                .filter { it.channelCount > 0 }
                .sortedBy { it.name.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun catalogsFor(addon: Addon, forSeries: Boolean): List<CatalogDescriptor> =
        addon.catalogs.filter {
            it.apiType == (if (forSeries) "series" else "movie") &&
                !it.isSearchOnlyCatalog() &&
                it.extra.none { extra -> extra.isRequired }
        }

    suspend fun catalogItems(addon: Addon, catalog: CatalogDescriptor): Result<List<MetaPreview>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = withTimeoutOrNull(25_000) {
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
                (result as? NetworkResult.Success)?.data?.items.orEmpty()
                    .filter { it.apiType == catalog.apiType }
                    .distinctBy { it.id }
            }
        }

    fun newSpec(
        name: String,
        kind: LucidChannelKind,
        addon: Addon? = null,
        catalog: CatalogDescriptor? = null,
        seriesId: String? = null,
        poster: String? = null,
        relayGroupId: String? = null,
        manualItems: List<LucidStoredItem> = emptyList(),
    ): LucidStoredSpec {
        val now = System.currentTimeMillis()
        val series = kind == LucidChannelKind.SERIES_LOOP
        return LucidStoredSpec(
            id = "vc_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.ifBlank { "Lucid TV channel" },
            kind = kind,
            group = "Lucid TV",
            addonId = addon?.id,
            addonName = addon?.displayName,
            addonBaseUrl = addon?.baseUrl,
            catalogId = catalog?.id,
            catalogName = catalog?.name,
            contentType = if (series) "series" else catalog?.apiType ?: "movie",
            seriesId = seriesId,
            seriesTitle = if (series) name else null,
            poster = poster,
            description = null,
            slotMinutes = if (series) 45 else 90,
            maxStartPercent = 20,
            jitter = true,
            relayGroupId = relayGroupId,
            relaySegmentMinutes = 30,
            order = if (series) LucidOrder.AIR else LucidOrder.SHUFFLE,
            manualItems = manualItems,
            anchorMs = now,
            createdAtMs = now,
            updatedAtMs = now,
        )
    }

    fun manualItem(item: MetaPreview, addon: Addon): LucidStoredItem = LucidStoredItem(
        id = item.id,
        type = item.apiType,
        title = item.name,
        poster = item.poster ?: item.landscapePoster ?: item.background,
        backdrop = item.background,
        description = item.description,
        year = item.releaseInfo?.let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() },
        runtimeMs = parseRuntimeMs(item.runtime),
        addonId = addon.id,
        addonName = addon.displayName,
        addonBaseUrl = addon.baseUrl,
    )

    private fun parseRuntimeMs(runtime: String?): Long? {
        if (runtime.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*h", RegexOption.IGNORE_CASE).find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)\\s*m", RegexOption.IGNORE_CASE).find(runtime)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val total = hours * 60 + minutes
        return if (total > 0) total * 60_000L else null
    }

    fun save(spec: LucidStoredSpec) {
        viewModelScope.launch {
            prefs.updateLucidChannels { list ->
                val stamped = spec.copy(updatedAtMs = System.currentTimeMillis())
                if (list.any { it.id == stamped.id }) {
                    list.map { if (it.id == stamped.id) stamped else it }
                } else {
                    list + stamped
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            prefs.updateLucidChannels { list -> list.filterNot { it.id == id } }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            prefs.updateLucidChannels { list ->
                list.map { if (it.id == id) it.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis()) else it }
            }
        }
    }

    fun duplicate(spec: LucidStoredSpec) {
        val now = System.currentTimeMillis()
        save(
            spec.copy(
                id = "vc_" + UUID.randomUUID().toString().replace("-", "").take(12),
                name = spec.name + " copy",
                createdAtMs = now,
                updatedAtMs = now,
                anchorMs = now,
            )
        )
    }

    fun exportJson(): String = LucidSpecCodec.encodePack(specs.value)

    suspend fun importJson(text: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = LucidSpecCodec.decodePack(text)
            if (parsed.isEmpty()) error("No channels in that pack.")
            val existing = prefs.lucidChannels.first()
            val importedIds = parsed.mapTo(HashSet()) { it.id }
            val merged = existing.filterNot { it.id in importedIds } + parsed
            prefs.setLucidChannels(merged)
            parsed.size
        }
    }
}
