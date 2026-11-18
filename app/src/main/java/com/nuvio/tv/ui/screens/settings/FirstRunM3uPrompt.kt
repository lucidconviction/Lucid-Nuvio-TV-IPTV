package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.Button
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * First-run offer to set up live TV from an M3U playlist. "Yes" opens Live TV settings with
 * Playlists & Guides already expanded, where the user can paste a link or browse iptv-org lists.
 */
@Composable
internal fun FirstRunM3uPromptHost(
    navController: NavHostController,
    viewModel: FirstRunM3uPromptViewModel = hiltViewModel()
) {
    val currentRoute by navController.currentBackStackEntryAsState()
    val route = currentRoute?.destination?.route
    val ready = route != null &&
        route != Screen.ExperienceModeSelection.route &&
        route != Screen.LayoutSelection.route &&
        route != Screen.ProfileSelection.route
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val openFocus = remember { FocusRequester() }

    LaunchedEffect(ready) {
        if (ready) viewModel.evaluate()
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(120)
            runCatching { openFocus.requestFocus() }
        }
    }
    if (!ready || !visible) return

    NuvioDialog(
        onDismiss = viewModel::dismiss,
        title = stringResource(R.string.first_run_m3u_title),
        subtitle = stringResource(R.string.first_run_m3u_subtitle),
    ) {
        Text(
            text = stringResource(R.string.first_run_m3u_body),
            color = NuvioTheme.colors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Button(
                onClick = {
                    viewModel.dismiss()
                    navController.navigate(Screen.LiveTvSettings.createRoute(openSources = true)) {
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.focusRequester(openFocus)
            ) {
                Text(stringResource(R.string.first_run_m3u_open))
            }
            Button(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.first_run_m3u_not_now))
            }
        }
        Text(
            text = stringResource(R.string.first_run_m3u_hint),
            color = NuvioTheme.colors.TextTertiary
        )
    }
}
