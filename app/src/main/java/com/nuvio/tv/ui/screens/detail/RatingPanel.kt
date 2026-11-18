package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Ten stars, matching the 1–10 scale of the tracking providers. The selected rating can be
 * removed, which sends a null rating to the provider.
 */
@Composable
internal fun RatingPanel(
    title: String,
    state: RatingUiState,
    onRate: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val firstStar = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstStar.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.rating_panel_title),
        subtitle = title,
        width = 620.dp,
    ) {
        Text(
            text = state.rating?.let { stringResource(R.string.rating_your_rating, it.toInt()) }
                ?: stringResource(R.string.rating_not_rated),
            color = if (state.rating != null) Color(0xFFFFC107) else NuvioTheme.colors.TextSecondary,
            fontWeight = FontWeight.Bold
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rowStart in listOf(1, 6)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (value in rowStart until rowStart + 5) {
                        val selected = state.rating != null && value <= state.rating
                        Button(
                            onClick = { onRate(value) },
                            enabled = !state.saving,
                            modifier = Modifier
                                .size(44.dp)
                                .then(if (value == 1) Modifier.focusRequester(firstStar) else Modifier)
                        ) {
                            Text(
                                text = "$value ★",
                                color = if (selected) Color(0xFFFFC107) else NuvioTheme.colors.TextTertiary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
        if (state.providerName != null) {
            Text(
                text = stringResource(R.string.rating_saved_to, state.providerName),
                color = NuvioTheme.colors.TextTertiary
            )
        }
        state.error?.let { error ->
            Text(text = error, color = NuvioTheme.colors.Error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            Button(
                onClick = { onRate(null) },
                enabled = !state.saving && state.rating != null
            ) {
                Text(stringResource(R.string.rating_remove))
            }
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.rating_close))
            }
        }
    }
}
