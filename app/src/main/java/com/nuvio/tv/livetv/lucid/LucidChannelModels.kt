package com.nuvio.tv.livetv.lucid

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.DrmInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One film, episode or live channel a Lucid TV channel can play. */
data class LucidItem(
    /** The addon's own id for the film/episode, or the channel key for a relay. */
    val id: String,
    /** The id used for stream search; series use the series id plus season/episode. */
    val streamId: String = id,
    val type: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val description: String?,
    val year: Int?,
    val runtimeMs: Long?,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val genres: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** Relays: the existing live channel this item tunes. */
    val streamUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drm: DrmInfo? = null,
)

@Serializable
enum class LucidChannelKind {
    /** A catalog of films, fixed 90-minute slots, joined in progress. */
    CATALOG_ROTATE,
    /** A catalog of films in a continuous 24/7 queue using real runtimes. */
    CATALOG_LOOP,
    /** One series, episodes in order, using episode runtimes. */
    SERIES_LOOP,
    /** Existing live channels relayed for fixed segments. */
    RELAY_LOOP,
    /** A hand-picked list of films, 24/7. */
    MANUAL_LOOP,
}

@Serializable
enum class LucidOrder { SHUFFLE, SEQUENTIAL, RELEASE, AIR, MANUAL }

/** A channel definition. Auto-discovered catalog channels use default fields. */
data class LucidChannelSpec(
    val id: String,
    val name: String,
    val catalogName: String,
    val addonName: String,
    val addonBaseUrl: String,
    val addonId: String = "",
    val catalogId: String = "",
    val contentType: String = "movie",
    val kind: LucidChannelKind = LucidChannelKind.CATALOG_ROTATE,
    val group: String = "Lucid TV",
    val number: Int? = null,
    val poster: String? = null,
    val slotMinutes: Int = 90,
    val maxStartPercent: Int = 20,
    val jitter: Boolean = true,
    val order: LucidOrder = LucidOrder.SHUFFLE,
    val anchorMs: Long = 0L,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val relayGroupId: String? = null,
    val relayKeys: List<String> = emptyList(),
    val relaySegmentMinutes: Int = 30,
    val manualItems: List<LucidStoredItem> = emptyList(),
    val auto: Boolean = true,
) {
    val channelKey: String get() = "virtual:$id"
    val isLoop: Boolean get() = kind != LucidChannelKind.CATALOG_ROTATE
    val groupId: String get() = if (group.equals("Lucid TV", ignoreCase = true)) {
        "virtual:lucid"
    } else {
        "virtual:lucid:" + group
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "lucid" }
    }
}

/** What a channel should play right now, and how far into it to join. */
data class LucidTune(
    val channelKey: String,
    val slotNumber: Long,
    val slotStartMs: Long,
    val slotEndMs: Long,
    val item: LucidItem,
    val startOffsetMs: Long,
    val relay: Boolean = false,
)

/** A stored custom channel, as the studio saves it. */
@Serializable
data class LucidStoredSpec(
    val id: String,
    val name: String,
    val kind: LucidChannelKind,
    val enabled: Boolean = true,
    val group: String = "Lucid TV",
    val number: Int? = null,
    val addonId: String? = null,
    val addonName: String? = null,
    val addonBaseUrl: String? = null,
    val catalogId: String? = null,
    val catalogName: String? = null,
    val contentType: String = "movie",
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val slotMinutes: Int = 90,
    val maxStartPercent: Int = 20,
    val jitter: Boolean = true,
    val order: LucidOrder = LucidOrder.SHUFFLE,
    val anchorMs: Long = 0L,
    val relayGroupId: String? = null,
    val relayKeys: List<String> = emptyList(),
    val relaySegmentMinutes: Int = 30,
    val manualItems: List<LucidStoredItem> = emptyList(),
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    val channelKey: String get() = "virtual:$id"
}

@Serializable
data class LucidStoredItem(
    val id: String,
    val type: String = "movie",
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val addonId: String = "",
    val addonName: String = "",
    val addonBaseUrl: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

/** Import/export packs. Versioned so a later schema can migrate old packs. */
@Serializable
data class LucidPack(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val channels: List<LucidStoredSpec> = emptyList(),
)

object LucidSpecCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeSpecs(raw: String?): List<LucidStoredSpec> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<LucidStoredSpec>>(raw) }.getOrDefault(emptyList())

    fun encodeSpecs(specs: List<LucidStoredSpec>): String =
        json.encodeToString(specs)

    fun encodePack(specs: List<LucidStoredSpec>): String =
        json.encodeToString(LucidPack(channels = specs))

    fun decodePack(raw: String): List<LucidStoredSpec> =
        runCatching { json.decodeFromString<LucidPack>(raw).channels }
            .getOrElse { decodeSpecs(raw) }
}

fun LucidItem.toProgram(spec: LucidChannelSpec, startMs: Long, stopMs: Long): EpgProgram {
    val episodeTag = if (season != null) "S${season}E${episode ?: 0}" else null
    val title = when {
        spec.kind == LucidChannelKind.SERIES_LOOP && season != null ->
            listOfNotNull(title, episodeTag, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        else -> title
    }
    return EpgProgram(
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        description = description,
        category = "Lucid TV · " + when (spec.kind) {
            LucidChannelKind.RELAY_LOOP -> "Relay"
            LucidChannelKind.SERIES_LOOP -> "Series"
            else -> spec.catalogName
        },
        episode = episodeTag,
        icon = poster,
        year = year,
    )
}
