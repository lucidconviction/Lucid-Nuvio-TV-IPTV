package com.nuvio.tv.youtube

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard

/** One cell of the sprite sheet YouTube uses for its seek-preview thumbnails. */
data class YtStoryboardFrame(
    val url: String,
    val row: Int,
    val column: Int,
    val rows: Int,
    val columns: Int,
    /** Where this frame starts, in the video's timeline. */
    val startMs: Long,
)

/**
 * A video's storyboard: YouTube packs the preview frames into sprite sheets, each sheet a grid
 * of [rows] × [columns] cells, one cell every [durationEachMs]. The sheet after the first is
 * addressed by its index in the URL.
 */
class YtStoryboard internal constructor(private val storyboard: MediaItemStoryboard) {
    private val size = storyboard.groupSize

    val durationEachMs: Long = size.durationEachMS.toLong().coerceAtLeast(1L)
    val rows: Int = size.rowCount.coerceAtLeast(1)
    val columns: Int = size.colCount.coerceAtLeast(1)
    /** Size of one preview cell, as the sheet stores it. */
    val cellWidth: Int = size.width.coerceAtLeast(1)
    val cellHeight: Int = size.height.coerceAtLeast(1)
    val sheetDurationMs: Long = storyboard.groupDurationMS.toLong()

    fun frameAt(positionMs: Long): YtStoryboardFrame? {
        val index = positionMs.coerceAtLeast(0L) / durationEachMs
        val perSheet = rows * columns
        val sheet = (index / perSheet).toInt()
        val cell = (index % perSheet).toInt()
        val url = runCatching { storyboard.getGroupUrl(sheet) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return YtStoryboardFrame(
            url = url,
            row = cell / columns,
            column = cell % columns,
            rows = rows,
            columns = columns,
            startMs = index * durationEachMs,
        )
    }
}
