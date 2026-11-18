package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A tracking provider that can read and write the user's 1–10 rating. */
interface TrackingRatingProvider {
    val providerId: TrackingProviderId

    /** Emits whenever the user's ratings change, so open screens refresh. */
    val changes: Flow<Any?>

    fun supports(media: TrackingMediaReference): Boolean

    fun rating(media: TrackingMediaReference): Double?

    /** Loads the user's ratings so [rating] can answer for titles rated elsewhere. */
    suspend fun refresh() = Unit

    suspend fun rate(profileId: Int, media: TrackingMediaReference, rating: Int?): TrackingMutationResult
}

data class TrackingRatingState(
    val provider: TrackingProviderId? = null,
    val rating: Double? = null
) {
    val isAvailable: Boolean
        get() = provider != null
}

/**
 * The rating source chosen in settings, and the rating it holds for a title. Only providers that
 * are connected and implement [TrackingRatingProvider] can be chosen.
 */
@Singleton
class TrackingRatings @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingRatingProvider>,
    private val registry: TrackingProviderRegistry,
    private val settings: TraktSettingsDataStore,
    private val profiles: ProfileManager
) {
    private val providersById = providers.associateBy(TrackingRatingProvider::providerId)

    val source: StateFlow<TrackingProviderId?> = settings.ratingSource

    fun availableSources(): List<TrackingProviderId> =
        providersById.keys
            .filter { registry.isAuthenticated(it) }
            .sortedBy { it.ordinal }

    fun stateFor(media: TrackingMediaReference): TrackingRatingState {
        val provider = providerFor(media) ?: return TrackingRatingState()
        return TrackingRatingState(provider.providerId, provider.rating(media))
    }

    /** Returns the connected rating providers' change signals, for a screen to observe. */
    fun changes(): List<Flow<Any?>> =
        listOf(source) + providersById.values.map(TrackingRatingProvider::changes)

    suspend fun refresh() {
        providerForSource()?.refresh()
    }

    suspend fun rate(media: TrackingMediaReference, rating: Int?): Boolean {
        require(rating == null || rating in 1..MAX_RATING)
        val provider = providerFor(media) ?: return false
        val saved = try {
            provider.rate(profiles.activeProfileId.value, media, rating).isComplete
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return saved
    }

    suspend fun setSource(source: TrackingProviderId?) {
        if (source != null && source !in providersById) return
        settings.setRatingSource(source)
        withTimeoutOrNull(SOURCE_APPLY_TIMEOUT_MS) { settings.ratingSource.first { it == source } }
    }

    private fun providerForSource(): TrackingRatingProvider? {
        val source = source.value ?: return null
        if (!registry.isAuthenticated(source)) return null
        return providersById[source]
    }

    private fun providerFor(media: TrackingMediaReference): TrackingRatingProvider? =
        providerForSource()?.takeIf { it.supports(media) }

    companion object {
        const val MAX_RATING = 10
        private const val SOURCE_APPLY_TIMEOUT_MS = 2_000L
    }
}
