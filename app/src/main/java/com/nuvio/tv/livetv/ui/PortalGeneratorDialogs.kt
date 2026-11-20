@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.data.PortalGenerator
import com.nuvio.tv.livetv.data.PortalStore
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * "Generate portals": pulls the Cloud Vault Xtream database and public Reddit/paste sources,
 * verifies each account, and parks the working ones in PortalStore. Nothing is installed until
 * Add is pressed, so a run cannot fill the channel list with fifteen providers.
 */
@Composable
internal fun PortalGeneratorDialog(
    onDismiss: () -> Unit,
    onAdd: (PortalStore.Saved) -> Unit,
) {
    val context = LocalContext.current
    var runKey by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Starting…") }
    var tried by remember { mutableIntStateOf(0) }
    var found by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val portals by PortalStore.portals.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { PortalStore.init(context) }
    LaunchedEffect(runKey) {
        running = true
        error = null
        tried = 0
        found = 0
        status = "Loading Cloud Vault portals…"
        runCatching {
            PortalGenerator.generate(target = PortalGenerator.TARGET) { progress ->
                status = progress.status
                tried = progress.tried
                found = progress.found
            }
        }.onSuccess { generated ->
            val added = PortalStore.addAll(context, generated)
            status = if (added > 0) {
                "Found $added portal${if (added == 1) "" else "s"}. Add the ones you want below."
            } else {
                "No new portals this round. Try again — the pool is shuffled."
            }
        }.onFailure { throwable ->
            error = throwable.message ?: "Couldn't generate portals."
            status = ""
        }
        running = false
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    NuvioDialog(
        onDismiss = { if (!running) onDismiss() },
        title = "Generate Xtream portals",
        subtitle = "Cloud Vault + Reddit · each account is checked before it is offered. Add one to install it as an Xtream playlist.",
        width = 760.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) LoadingIndicator(Modifier.height(24.dp))
            Text(
                text = if (running) status else status.ifBlank { "Done." },
                color = NuvioTheme.colors.TextPrimary,
                style = NuvioTheme.textStyles.body,
                modifier = Modifier.weight(1f),
            )
        }
        if (running && (tried > 0 || found > 0)) {
            Text(
                "Tried $tried · verified $found of ${PortalGenerator.TARGET}",
                color = NuvioTheme.colors.TextSecondary,
                style = NuvioTheme.textStyles.metadata,
            )
        }
        error?.let {
            Text(it, color = NuvioTheme.colors.Error, style = NuvioTheme.textStyles.metadata)
        }
        if (portals.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(portals, key = { it.key }) { portal ->
                    PortalRow(
                        portal = portal,
                        focusRequester = if (portal == portals.first()) firstFocus else null,
                        onAdd = {
                            onAdd(portal)
                            PortalStore.markAdded(context, portal)
                        },
                        onRemove = { PortalStore.remove(context, portal) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { runKey++ },
                enabled = !running,
            ) { Text(if (portals.isEmpty()) "Try again" else "Generate again") }
            Button(onClick = onDismiss, enabled = !running) { Text("Close") }
        }
    }
}

/** Results from earlier runs, without starting a new generation. */
@Composable
internal fun GeneratedPortalsDialog(
    onDismiss: () -> Unit,
    onAdd: (PortalStore.Saved) -> Unit,
) {
    val context = LocalContext.current
    val portals by PortalStore.portals.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        PortalStore.init(context)
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Generated portals",
        subtitle = "Checked Xtream accounts from earlier runs. Add one to install it.",
        width = 760.dp,
    ) {
        if (portals.isEmpty()) {
            Text("No generated portals yet.", color = NuvioTheme.colors.TextSecondary, style = NuvioTheme.textStyles.body)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(portals, key = { it.key }) { portal ->
                    PortalRow(
                        portal = portal,
                        focusRequester = if (portal == portals.first()) firstFocus else null,
                        onAdd = {
                            onAdd(portal)
                            PortalStore.markAdded(context, portal)
                        },
                        onRemove = { PortalStore.remove(context, portal) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun PortalRow(
    portal: PortalStore.Saved,
    focusRequester: FocusRequester?,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                portal.name,
                color = NuvioTheme.colors.TextPrimary,
                style = NuvioTheme.textStyles.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    portal.host,
                    portal.expiry,
                    portal.maxConnections?.let { "$it conn" },
                    portal.source,
                ).joinToString(" · "),
                color = NuvioTheme.colors.TextSecondary,
                style = NuvioTheme.textStyles.metadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (portal.added) {
            Text("Added", color = NuvioTheme.colors.Success, style = NuvioTheme.textStyles.metadata)
        } else {
            Button(
                onClick = onAdd,
                modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
            ) { Text("Add") }
        }
        Button(onClick = onRemove) { Text("Remove") }
    }
}
