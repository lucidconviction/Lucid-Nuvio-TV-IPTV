package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.TvType

/** Map CloudStream TvType to NuvioTV content type string ("movie" or "tv"). */
fun TvType.toNuvioType(): String = when (this) {
    TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> "movie"
    else -> "tv"
}

/** Parse TvType from string name, case-insensitive. */
fun tvTypeFromString(value: String): TvType? = TvType.entries.firstOrNull {
    it.name.equals(value, ignoreCase = true)
}

/**
 * Map a CloudStream plugin's declared `tvTypes` to the Nuvio content types it can search.
 * Unknown or empty declarations are treated as movie + series, matching the old fallback.
 */
internal fun cloudStreamContentTypes(tvTypes: List<String>?): List<String> =
    tvTypes.orEmpty().mapNotNull { type ->
        TvType.entries.firstOrNull { it.name.equals(type.trim(), ignoreCase = true) }
    }.flatMap { type ->
        when (type) {
            TvType.Others -> listOf("movie", "tv")
            TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> listOf("movie")
            else -> listOf("tv")
        }
    }.distinct().ifEmpty { listOf("movie", "tv") }
