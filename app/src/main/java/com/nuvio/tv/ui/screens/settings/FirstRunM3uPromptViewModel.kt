package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether a first run should be offered the M3U playlist setup. */
internal fun shouldOfferM3uSetup(hasSeenPrompt: Boolean, hasPlaylists: Boolean): Boolean =
    !hasSeenPrompt && !hasPlaylists

/**
 * Offers M3U playlist setup once, after onboarding, when the app has no playlists yet.
 * Existing users with playlists never see it; the flag is install-wide, like onboarding.
 */
@HiltViewModel
class FirstRunM3uPromptViewModel @Inject constructor(
    private val onboarding: AppOnboardingDataStore,
    private val liveTvPreferences: LiveTvPreferences,
) : ViewModel() {

    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()

    private var evaluated = false

    fun evaluate() {
        if (evaluated) return
        viewModelScope.launch {
            // Wait for the onboarding screen to be finished; the prompt must not sit over it.
            onboarding.hasSeenAuthQrOnFirstLaunch.first { it }
            val hasSeenPrompt = onboarding.hasSeenM3uPrompt.first()
            val hasPlaylists = liveTvPreferences.playlists.first().isNotEmpty()
            evaluated = true
            if (hasSeenPrompt) return@launch
            if (shouldOfferM3uSetup(hasSeenPrompt = false, hasPlaylists = hasPlaylists)) {
                _visible.value = true
            } else {
                // Already set up: remember that the offer is settled so it never appears later.
                onboarding.setHasSeenM3uPrompt(true)
            }
        }
    }

    fun dismiss() {
        _visible.value = false
        viewModelScope.launch { onboarding.setHasSeenM3uPrompt(true) }
    }
}
