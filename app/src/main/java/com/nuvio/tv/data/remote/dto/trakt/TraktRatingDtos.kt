package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRatingItemDto(
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

/** Trakt's sync/ratings body: each list is absent when empty. */
@JsonClass(generateAdapter = true)
data class TraktRatingsRequestDto(
    @Json(name = "movies") val movies: List<TraktRatingEntryDto>? = null,
    @Json(name = "shows") val shows: List<TraktRatingEntryDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatingEntryDto(
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "rating") val rating: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatedMovieDto(
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRatedShowDto(
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)
