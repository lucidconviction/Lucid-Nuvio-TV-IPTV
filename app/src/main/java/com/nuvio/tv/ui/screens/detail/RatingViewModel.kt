package com.nuvio.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RatingUiState(
    val available: Boolean = false,
    val providerName: String? = null,
    val rating: Double? = null,
    val saving: Boolean = false,
    val error: String? = null
)

/** Star rating for the title open on the details page, saved to the configured rating source. */
@HiltViewModel
class RatingViewModel @Inject constructor(
    private val ratings: TrackingRatings,
) : ViewModel() {

    private val _state = MutableStateFlow(RatingUiState())
    val state = _state.asStateFlow()

    private var media: TrackingMediaReference? = null
    private var loadJob: Job? = null

    fun bind(media: TrackingMediaReference) {
        if (this.media == media) return
        this.media = media
        loadJob?.cancel()
        val current = ratings.stateFor(media)
        _state.value = RatingUiState(
            available = current.isAvailable,
            providerName = current.provider?.label(),
            rating = current.rating
        )
        if (!current.isAvailable) return
        loadJob = viewModelScope.launch {
            ratings.refresh()
            val refreshed = ratings.stateFor(media)
            _state.update {
                if (this@RatingViewModel.media == media) it.copy(rating = refreshed.rating) else it
            }
        }
    }

    fun rate(value: Int?) {
        val target = media ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val saved = ratings.rate(target, value)
            _state.update { current ->
                if (this@RatingViewModel.media != target) {
                    current
                } else {
                    current.copy(
                        saving = false,
                        rating = if (saved) value?.toDouble() else current.rating,
                        error = if (saved) null else "Could not save the rating"
                    )
                }
            }
        }
    }
}

internal fun TrackingProviderId.label(): String = when (this) {
    TrackingProviderId.TRAKT -> "Trakt"
    TrackingProviderId.SIMKL -> "Simkl"
    TrackingProviderId.MDBLIST -> "MDBList"
}
