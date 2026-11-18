package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import com.nuvio.tv.livetv.data.IptvOrgList
import com.nuvio.tv.livetv.data.IptvOrgListType
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Browse the iptv-org country, category and region playlists and add one as a normal
 * playlist. The lists are cached for a day by [IptvOrgViewModel].
 */
@Composable
internal fun IptvOrgBrowserDialog(
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (IptvOrgList) -> Unit,
    viewModel: IptvOrgViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(IptvOrgListType.COUNTRY) }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<IptvOrgList?>(null) }
    val firstTabFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.load()
        delay(80)
        runCatching { firstTabFocus.requestFocus() }
    }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(60)
            runCatching { addFocus.requestFocus() }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Browse M3U playlists",
        subtitle = "Country, category and region channel lists from the iptv-org project",
        width = 760.dp,
    ) {
        val data = state.data
        if (pending != null) {
            AddConfirmation(
                item = pending!!,
                addFocus = addFocus,
                onAdd = {
                    onAdd(pending!!)
                    onDismiss()
                },
                onCancel = { pending = null },
            )
            return@NuvioDialog
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTopBarTab(
                title = "Countries",
                isSelected = tab == IptvOrgListType.COUNTRY,
                onClick = { tab = IptvOrgListType.COUNTRY },
                focusRequester = firstTabFocus,
            )
            SettingsTopBarTab(
                title = "Categories",
                isSelected = tab == IptvOrgListType.CATEGORY,
                onClick = { tab = IptvOrgListType.CATEGORY },
            )
            SettingsTopBarTab(
                title = "Regions",
                isSelected = tab == IptvOrgListType.REGION,
                onClick = { tab = IptvOrgListType.REGION },
            )
        }

        LiveTextField(
            value = query,
            onValueChange = { query = it },
            hint = "Search by name or code",
            focusRequester = searchFocus,
        )

        when {
            data == null && state.loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(26.dp))
                }
            }

            data == null && state.error != null -> {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        state.error!!,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { viewModel.load(force = true) }) { Text("Retry") }
                }
            }

            data != null -> {
                val lists = when (tab) {
                    IptvOrgListType.COUNTRY -> data.countries
                    IptvOrgListType.CATEGORY -> data.categories
                    IptvOrgListType.REGION -> data.regions
                }
                val filtered = remember(lists, query) {
                    if (query.isBlank()) lists
                    else lists.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.code.contains(query, ignoreCase = true)
                    }
                }
                if (data.fromCache) {
                    Text(
                        "Showing cached lists (offline).",
                        color = NuvioTheme.colors.TextTertiary,
                    )
                }
                if (filtered.isEmpty()) {
                    Text(
                        "No playlists match this search.",
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { "${it.type}:${it.code}" }) { item ->
                            val normalized = normalizeIptvOrgUrl(item.url)
                            val alreadyAdded = normalized in existingUrls
                            SettingsActionRow(
                                title = listOfNotNull(item.flag, item.name).joinToString(" "),
                                subtitle = if (alreadyAdded) "Already added · ${item.subtitle}" else item.subtitle,
                                leadingIcon = Icons.Default.LiveTv,
                                enabled = !alreadyAdded,
                                onClick = { pending = item },
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Playlists are maintained by the iptv-org project. Channels may be geo-blocked or offline.",
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddConfirmation(
    item: IptvOrgList,
    addFocus: FocusRequester,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        "Add ${item.name}?",
        color = NuvioTheme.colors.TextPrimary,
        fontWeight = FontWeight.Bold,
    )
    Text(
        item.subtitle,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        "The playlist downloads in the background; its channels then appear in Live TV.",
        color = NuvioTheme.colors.TextTertiary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAdd, modifier = Modifier.focusRequester(addFocus)) { Text("Add playlist") }
        Button(onClick = onCancel) { Text("Cancel") }
    }
}

/** Duplicate matching ignores case and a trailing slash. */
internal fun normalizeIptvOrgUrl(url: String): String = url.trim().trimEnd('/').lowercase()
