@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.youtube.YouTubeAccountState
import com.nuvio.tv.youtube.YouTubeSettings
import kotlinx.coroutines.delay

/**
 * Settings for the YouTube tab. Every switch here is read by the engine or the player, so it
 * applies as soon as it is changed (feed filters reload the current tab straight away).
 */
@Composable
internal fun YouTubeSettingsScreen(
    account: YouTubeAccountState,
    onClose: () -> Unit,
    onManageAccount: () -> Unit,
    onClearWatchHistory: () -> Unit,
    onClearSearchHistory: () -> Unit,
    onFeedsChanged: () -> Unit,
) {
    val context = LocalContext.current
    var autoplay by remember { mutableStateOf(YouTubeSettings.autoplayNext(context)) }
    var resume by remember { mutableStateOf(YouTubeSettings.resumePlayback(context)) }
    var quality by remember { mutableIntStateOf(YouTubeSettings.preferredHeight(context)) }
    var hideShorts by remember { mutableStateOf(YouTubeSettings.hideShorts(context)) }
    var hideWatched by remember { mutableStateOf(YouTubeSettings.hideWatched(context)) }
    var hideUpcoming by remember { mutableStateOf(YouTubeSettings.hideUpcoming(context)) }
    var rememberTab by remember { mutableStateOf(YouTubeSettings.rememberLastTab(context)) }
    var deArrowTitles by remember { mutableStateOf(YouTubeSettings.deArrowTitles(context)) }
    var deArrowThumbs by remember { mutableStateOf(YouTubeSettings.deArrowThumbnails(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(90)
        runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }

    fun feedsChanged() = onFeedsChanged()

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "YouTube settings",
                style = NuvioTheme.textStyles.sectionTitle,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onClose) { Text("Done") }
        }
        notice?.let {
            Text(
                it,
                modifier = Modifier.padding(bottom = 8.dp),
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item { SettingsSection("Playback") }
            item {
                YouTubeSettingToggle(
                    label = "Autoplay next video",
                    subtitle = "Play the next video when this one ends.",
                    value = autoplay,
                    modifier = Modifier.focusRequester(firstFocus),
                ) {
                    autoplay = it
                    YouTubeSettings.setAutoplayNext(context, it)
                }
            }
            item {
                YouTubeSettingToggle(
                    label = "Resume where you left off",
                    subtitle = "Start a video from the last saved position.",
                    value = resume,
                ) {
                    resume = it
                    YouTubeSettings.setResumePlayback(context, it)
                }
            }
            item {
                SettingsQualityChoices(quality) {
                    quality = it
                    YouTubeSettings.setPreferredHeight(context, it)
                }
            }

            item { SettingsSection("Feeds") }
            item {
                YouTubeSettingToggle(
                    label = "Hide Shorts",
                    subtitle = "Keep vertical Shorts out of feeds and grids.",
                    value = hideShorts,
                ) {
                    hideShorts = it
                    YouTubeSettings.setHideShorts(context, it)
                    feedsChanged()
                }
            }
            item {
                YouTubeSettingToggle(
                    label = "Hide watched videos",
                    subtitle = "Hide videos you've almost finished.",
                    value = hideWatched,
                ) {
                    hideWatched = it
                    YouTubeSettings.setHideWatched(context, it)
                    feedsChanged()
                }
            }
            item {
                YouTubeSettingToggle(
                    label = "Hide upcoming premieres",
                    subtitle = "Upcoming videos can't be played yet.",
                    value = hideUpcoming,
                ) {
                    hideUpcoming = it
                    YouTubeSettings.setHideUpcoming(context, it)
                    feedsChanged()
                }
            }
            item {
                YouTubeSettingToggle(
                    label = "Remember last tab",
                    subtitle = "Open YouTube on the tab you used last.",
                    value = rememberTab,
                ) {
                    rememberTab = it
                    YouTubeSettings.setRememberLastTab(context, it)
                }
            }

            item { SettingsSection("Titles and thumbnails") }
            item {
                YouTubeSettingToggle(
                    label = "DeArrow titles",
                    subtitle = "Use community titles instead of clickbait.",
                    value = deArrowTitles,
                ) {
                    deArrowTitles = it
                    YouTubeSettings.setDeArrowTitles(context, it)
                    feedsChanged()
                }
            }
            item {
                YouTubeSettingToggle(
                    label = "DeArrow thumbnails",
                    subtitle = "Use community thumbnails where available.",
                    value = deArrowThumbs,
                ) {
                    deArrowThumbs = it
                    YouTubeSettings.setDeArrowThumbnails(context, it)
                    feedsChanged()
                }
            }

            item { SettingsSection("History") }
            item {
                SettingsActionRow(
                    label = "Clear watch history",
                    subtitle = "Removes Continue watching and saved positions on this device.",
                ) {
                    onClearWatchHistory()
                    notice = "YouTube watch history cleared."
                }
            }
            item {
                SettingsActionRow(
                    label = "Clear search history",
                    subtitle = "Removes the recent searches shown under the search box.",
                ) {
                    onClearSearchHistory()
                    notice = "YouTube search history cleared."
                }
            }
            item {
                SettingsActionRow(
                    label = "Refresh feeds",
                    subtitle = "Reload the current YouTube tab.",
                ) {
                    onFeedsChanged()
                    notice = "Refreshing feeds…"
                }
            }

            item { SettingsSection("Account") }
            item {
                SettingsActionRow(
                    label = if (account.signedIn) {
                        account.accountName ?: "Signed in"
                    } else {
                        "Sign in to YouTube"
                    },
                    subtitle = if (account.signedIn) {
                        "Manage the account used for subscriptions, likes and playlists."
                    } else {
                        "Use Google's TV code to sign in without typing a password."
                    },
                    value = if (account.signedIn) "Manage" else "Sign in",
                    onClick = onManageAccount,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
        style = NuvioTheme.textStyles.metadata,
        color = NuvioTheme.colors.TextTertiary,
        letterSpacing = 1.1.sp,
    )
}

@Composable
private fun YouTubeSettingToggle(
    label: String,
    subtitle: String,
    value: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Button(
        onClick = { onChange(!value) },
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            if (value) "On" else "Off",
            fontSize = 12.sp,
            color = if (value) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    subtitle: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = NuvioTheme.textStyles.metadata,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(value, fontSize = 12.sp, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun SettingsQualityChoices(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Default quality",
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            style = NuvioTheme.textStyles.metadata,
            color = NuvioTheme.colors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            YouTubeSettings.qualityChoices.forEach { height ->
                val isSelected = height == selected
                Button(
                    onClick = { onSelect(height) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) {
                            NuvioTheme.colors.TextPrimary
                        } else {
                            NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f)
                        },
                        contentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = if (isSelected) {
                            NuvioTheme.colors.TextPrimary
                        } else {
                            NuvioTheme.colors.FocusBackground
                        },
                        focusedContentColor = if (isSelected) NuvioTheme.colors.TextInverse else NuvioTheme.colors.TextPrimary,
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(9.dp)),
                ) {
                    Text(
                        if (height <= 0) "Auto" else "${height}p",
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            "The player starts at this size. Auto lets YouTube adapt to the connection.",
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            style = NuvioTheme.textStyles.metadata,
            color = NuvioTheme.colors.TextTertiary,
        )
        Spacer(Modifier.height(2.dp))
    }
}
