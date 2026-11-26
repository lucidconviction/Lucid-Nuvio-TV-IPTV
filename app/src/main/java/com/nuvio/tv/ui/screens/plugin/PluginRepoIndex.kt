package com.nuvio.tv.ui.screens.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val REPO_INDEX_URL =
    "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"

data class PluginRepoIndexEntry(
    val name: String,
    val url: String,
    val verified: Boolean,
)

/** The public MegaRepo (3737) repository database, parsed natively for the TV Plugins page. */
private object PluginRepoIndexRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(): List<PluginRepoIndexEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(REPO_INDEX_URL)
                .header("User-Agent", "NuvioTV/1.0")
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            } ?: return@withContext emptyList()
            parse(body)
        }.getOrDefault(emptyList())
    }

    private fun parse(body: String): List<PluginRepoIndexEntry> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                when (val element = array.opt(index)) {
                    is String -> element.trim().takeIf { it.isNotBlank() }?.let {
                        add(entry(it, verified = false))
                    }
                    is JSONObject -> {
                        val url = element.optString("url").trim()
                        if (url.isNotBlank()) {
                            add(entry(url, element.optBoolean("verified", false)))
                        }
                    }
                }
            }
        }.distinctBy { it.url }
    }

    private fun entry(url: String, verified: Boolean): PluginRepoIndexEntry =
        PluginRepoIndexEntry(name = displayName(url), url = url, verified = verified)

    private fun displayName(url: String): String {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return url
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        return when {
            parsed.host.equals("raw.githubusercontent.com", ignoreCase = true) && segments.size >= 2 ->
                "${segments[0]}/${segments[1]}"
            segments.isNotEmpty() -> "${parsed.host}/${segments.first()}"
            else -> parsed.host
        }
    }
}

@Composable
fun RepoIndexCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "CloudStream repositories",
                    color = NuvioTheme.colors.TextPrimary,
                    style = NuvioTheme.textStyles.sectionTitle,
                )
                Text(
                    "Browse the MegaRepo (3737) index and install individual repositories",
                    color = NuvioTheme.colors.TextSecondary,
                    style = NuvioTheme.textStyles.metadata,
                )
            }
            Text("Browse", color = NuvioTheme.colors.TextPrimary, style = NuvioTheme.textStyles.metadata)
        }
    }
}

@Composable
fun RepoIndexOverlay(
    installedRepos: List<com.nuvio.tv.domain.model.PluginRepository>,
    adding: Boolean,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<PluginRepoIndexEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var addingUrl by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    val installedUrls = installedRepos.map { it.url.trimEnd('/') }.toSet()
    val firstAddUrl = entries.firstOrNull { it.url.trimEnd('/') !in installedUrls }?.url

    LaunchedEffect(loading, entries, installedRepos) {
        if (!loading && error == null) {
            delay(120)
            runCatching {
                if (firstAddUrl != null) firstFocus.requestFocus() else closeFocus.requestFocus()
            }
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        entries = PluginRepoIndexRepository.fetch()
        loading = false
        if (entries.isEmpty()) error = "Could not load the MegaRepo index. Check the connection."
    }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocus),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Close")
                }
                Column(Modifier.weight(1f)) {
                    Text("Repository index", color = NuvioTheme.colors.TextPrimary, style = NuvioTheme.textStyles.headline)
                    Text(
                        "MegaRepo 3737 · install individual CloudStream repositories",
                        color = NuvioTheme.colors.TextSecondary,
                        style = NuvioTheme.textStyles.metadata,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = NuvioTheme.colors.TextPrimary)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.orEmpty(), color = NuvioTheme.colors.TextSecondary)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().focusRestorer(firstFocus),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val groups = listOf(true to "Verified", false to "Community")
                    groups.forEach { (verified, label) ->
                        val groupEntries = entries.filter { it.verified == verified }
                        if (groupEntries.isNotEmpty()) {
                            item(key = "header-$label") {
                                Text(
                                    label,
                                    color = NuvioTheme.colors.TextSecondary,
                                    style = NuvioTheme.textStyles.sectionTitle,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                )
                            }
                            items(groupEntries, key = { it.url }) { entry ->
                                val installed = installedRepos.any {
                                    it.url.trimEnd('/') == entry.url.trimEnd('/')
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.name,
                                            color = NuvioTheme.colors.TextPrimary,
                                            style = NuvioTheme.textStyles.body,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.url,
                                            color = NuvioTheme.colors.TextSecondary,
                                            style = NuvioTheme.textStyles.metadata,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (installed) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = NuvioTheme.colors.TextPrimary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Installed", color = NuvioTheme.colors.TextSecondary)
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                addingUrl = entry.url
                                                onAdd(entry.url)
                                            },
                                            enabled = !adding,
                                            modifier = if (entry.url == firstAddUrl) {
                                                Modifier.focusRequester(firstFocus)
                                            } else {
                                                Modifier
                                            },
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (adding && addingUrl == entry.url) "Adding…" else "Add")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
