package com.nuvio.tv.livetv.lucid

import com.nuvio.tv.livetv.model.EpgProgram

/** One film a Lucid TV channel can play. */
data class LucidItem(
    /** The addon's own id for the film (often an IMDb id). */
    val id: String,
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
)

/** A catalog turned into a channel. Kept small: the pool lives separately. */
data class LucidChannelSpec(
    val id: String,
    val name: String,
    val catalogName: String,
    val addonName: String,
    val addonBaseUrl: String,
    val contentType: String = "movie",
    val slotMinutes: Int = 90,
    val maxStartPercent: Int = 20,
    val jitter: Boolean = true,
) {
    val channelKey: String get() = "virtual:$id"
}

/** What a channel should play right now, and how far into it to join. */
data class LucidTune(
    val channelKey: String,
    val slotNumber: Long,
    val slotStartMs: Long,
    val slotEndMs: Long,
    val item: LucidItem,
    val startOffsetMs: Long,
)

fun LucidItem.toProgram(spec: LucidChannelSpec, startMs: Long, stopMs: Long): EpgProgram =
    EpgProgram(
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        description = description,
        category = "Lucid TV · ${spec.catalogName}",
        icon = poster,
        year = year,
    )
