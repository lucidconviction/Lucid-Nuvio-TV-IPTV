package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.QuickStreamChecker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.livetv.model.LiveChannel

/**
 * Matching one Quick Ch Search channel against the playlists:
 * - [matches] are the channels whose names/aliases matched.
 * - [statuses] maps a stream URL to true (working), false (dead) or null (still checking).
 */
data class QuickChannelMatchState(
    val matches: List<LiveChannel> = emptyList(),
    val statuses: Map<String, Boolean?> = emptyMap(),
) {
    val isChecking: Boolean get() = matches.any { statuses[it.url] == null }
    val workingCount: Int get() = matches.count { statuses[it.url] == true }
    val checkedCount: Int get() = matches.count { statuses[it.url] != null }

    /** Working first, then still-checking; dead streams are hidden. */
    val visibleMatches: List<LiveChannel>
        get() = matches
            .sortedWith(
                compareByDescending<LiveChannel> { statuses[it.url] == true }
                    .thenByDescending { statuses[it.url] == null },
            )
            .filter { statuses[it.url] != false }
}

/**
 * Resolves [selected] against [liveChannels] and probes each matched stream. Re-runs when the
 * playlist changes, so a slow playlist load doesn't leave an empty result on screen.
 */
@Composable
fun rememberQuickChannelMatchState(
    selected: QuickChannel?,
    liveChannels: List<LiveChannel>,
): QuickChannelMatchState {
    var state by remember { mutableStateOf(QuickChannelMatchState()) }
    val run = remember { intArrayOf(0) }
    LaunchedEffect(selected, liveChannels) {
        val myRun = ++run[0]
        if (selected == null) {
            state = QuickChannelMatchState()
            return@LaunchedEffect
        }
        val matches = liveChannels.filter { QuickChannelList.matches(selected, it) }
        val pending = matches.distinctBy { it.url }.associate { it.url to null as Boolean? }
        state = QuickChannelMatchState(matches = matches, statuses = pending)
        if (matches.isEmpty()) return@LaunchedEffect
        QuickStreamChecker.checkBatch(matches) { url, alive ->
            if (run[0] == myRun && state.statuses[url] == null) {
                state = state.copy(statuses = state.statuses + (url to alive))
            }
        }
    }
    return state
}
