package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatingProvider
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEntryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Trakt ratings (1–10) for titles. Episodes are not rated here: our Trakt ids for an episode are
 * not always known, so the star action is offered for movies and shows only.
 */
@Singleton
class TraktRatings @Inject constructor(
    private val traktApi: TraktApi,
    private val auth: TraktAuthService,
    private val profiles: ProfileManager
) : TrackingRatingProvider {

    override val providerId = TrackingProviderId.TRAKT

    private val version = MutableStateFlow(0L)
    override val changes: Flow<Any?> = version

    private val mutex = Mutex()
    private val ratingsByProfile = mutableMapOf<Int, Map<String, Double>>()
    private val loadedProfiles = mutableSetOf<Int>()

    override fun supports(media: TrackingMediaReference): Boolean =
        media.episode == null && media.hasResolvableIdentity && media.ids.hasRatingIds()

    override fun rating(media: TrackingMediaReference): Double? =
        ratingsByProfile[profiles.activeProfileId.value]?.get(media.stableKey)

    override suspend fun refresh() {
        val profileId = profiles.activeProfileId.value
        mutex.withLock {
            if (profileId in loadedProfiles) return
            val movies = auth.executeAuthorizedRequest { header ->
                traktApi.getRatedMovies(header)
            }?.takeIf { it.isSuccessful }?.body().orEmpty()
            val shows = auth.executeAuthorizedRequest { header ->
                traktApi.getRatedShows(header)
            }?.takeIf { it.isSuccessful }?.body().orEmpty()
            val ratings = buildMap {
                movies.forEach { item ->
                    val rating = item.rating?.toDouble() ?: return@forEach
                    val ids = item.movie?.ids?.toTrackingIds() ?: return@forEach
                    put(reference(TrackingMediaKind.MOVIE, ids).stableKey, rating)
                }
                shows.forEach { item ->
                    val rating = item.rating?.toDouble() ?: return@forEach
                    val ids = item.show?.ids?.toTrackingIds() ?: return@forEach
                    put(reference(TrackingMediaKind.SHOW, ids).stableKey, rating)
                }
            }
            ratingsByProfile[profileId] = ratings
            loadedProfiles += profileId
        }
        version.value++
    }

    override suspend fun rate(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?
    ): TrackingMutationResult {
        if (profileId != profiles.activeProfileId.value || !supports(media)) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        val ids = media.ids.toTraktIds() ?: return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        val entry = TraktRatingEntryDto(ids = ids, rating = rating)
        val body = if (media.kind == TrackingMediaKind.MOVIE) {
            TraktRatingsRequestDto(movies = listOf(entry))
        } else {
            TraktRatingsRequestDto(shows = listOf(entry))
        }
        val response = auth.executeAuthorizedRequest { header ->
            if (rating == null) {
                traktApi.removeRatings(authorization = header, body = body)
            } else {
                traktApi.addRatings(authorization = header, body = body)
            }
        } ?: return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)

        if (!response.isSuccessful) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        val current = ratingsByProfile[profileId].orEmpty().toMutableMap()
        if (rating == null) current.remove(media.stableKey) else current[media.stableKey] = rating.toDouble()
        ratingsByProfile[profileId] = current
        version.value++
        return TrackingMutationResult(attemptedCount = 1)
    }

    private fun reference(kind: TrackingMediaKind, ids: TrackingExternalIds) =
        TrackingMediaReference(kind = kind, ids = ids)

    private fun TrackingExternalIds.hasRatingIds(): Boolean =
        imdb != null || tmdb != null || trakt != null || tvdb != null

    private fun TrackingExternalIds.toTraktIds(): TraktIdsDto? {
        if (!hasRatingIds()) return null
        return TraktIdsDto(trakt = trakt?.toInt(), imdb = imdb, tmdb = tmdb?.toInt(), tvdb = tvdb?.toIntOrNull())
    }

    private fun TraktIdsDto.toTrackingIds(): TrackingExternalIds = TrackingExternalIds(
        imdb = imdb?.takeIf { it.isNotBlank() },
        tmdb = tmdb?.toLong(),
        trakt = trakt?.toLong(),
        tvdb = tvdb?.toString()
    )
}
