package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogSyncSupportTest {

    @Test
    fun `manifest case is synced while old order disabled flags and titles survive`() {
        val addon = testAddon(
            id = "aio-metadata",
            baseUrl = "https://example.com/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(type = ContentType.MOVIE, id = "movies", name = "Movies"),
                CatalogDescriptor(type = ContentType.SERIES, rawType = "Series", id = "series-list", name = "Series")
            )
        )
        val oldKey = "aio-metadata_series_series-list"
        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState(
                orderKeys = listOf(oldKey),
                disabledKeys = setOf(oldKey),
                customTitles = mapOf(oldKey to "My shows")
            )
        )

        assertEquals("series-list", payload.items.first().catalogId)
        assertEquals("Series", payload.items.first().type)
        assertEquals("My shows", payload.items.first().customTitle)
        assertFalse(payload.items.first().enabled)
        assertEquals(oldKey, homeCatalogKey("aio-metadata", "Series", "series-list"))
        assertEquals(
            homeLegacyDisabledCatalogKey(addon.baseUrl, "series", "series-list", "Series"),
            homeLegacyDisabledCatalogKey(addon.baseUrl, "Series", "series-list", "Series")
        )
    }

    @Test
    fun `build payload keeps disabled catalog when only legacy disable key exists`() {
        val addon = testAddon(
            id = "com.test.addon",
            baseUrl = "https://example.com/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState(
                disabledKeys = setOf(
                    homeLegacyDisabledCatalogKey(
                        addonBaseUrl = addon.baseUrl,
                        type = "movie",
                        catalogId = "top",
                        catalogName = "Top"
                    )
                )
            )
        )

        assertEquals(1, payload.items.size)
        assertEquals("com.test.addon", payload.items.single().addonId)
        assertFalse(payload.items.single().enabled)
    }

    @Test
    fun `build payload includes all visible catalogs even when saved order is empty`() {
        val firstAddon = testAddon(
            id = "com.test.first",
            baseUrl = "https://first.example/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )
        val secondAddon = testAddon(
            id = "com.test.second",
            baseUrl = "https://second.example/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.SERIES,
                    id = "latest",
                    name = "Latest",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(firstAddon, secondAddon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState()
        )

        assertEquals(
            listOf(
                homeCatalogKey("com.test.first", "movie", "top"),
                homeCatalogKey("com.test.second", "series", "latest")
            ),
            payload.items.map { homeCatalogKey(it.addonId, it.type, it.catalogId) }
        )
        assertTrue(payload.items.all { it.enabled })
    }

    @Test
    fun `build payload carries hide unreleased content setting`() {
        val payload = buildHomeCatalogSyncPayload(
            addons = emptyList(),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState(hideUnreleasedContent = true)
        )

        assertTrue(payload.hideUnreleasedContent)
        assertEquals(emptyList<SyncCatalogItem>(), payload.items)
    }

    @Test
    fun `build payload excludes catalogs from disabled addons`() {
        val addon = testAddon(
            id = "com.test.disabled",
            baseUrl = "https://disabled.example/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        ).copy(enabled = false)

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState()
        )

        assertEquals(emptyList<SyncCatalogItem>(), payload.items)
    }

    @Test
    fun `build payload appends collections after catalogs and preserves disabled collections`() {
        val addon = testAddon(
            id = "com.test.addon",
            baseUrl = "https://example.com/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )
        val collection = Collection(id = "col-1", title = "Favorites")

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = listOf(collection),
            localState = LocalHomeCatalogSettingsState(
                disabledKeys = setOf(homeCollectionKey("col-1"))
            )
        )

        assertEquals(2, payload.items.size)
        assertTrue(payload.items.last().isCollection)
        assertEquals("col-1", payload.items.last().collectionId)
        assertFalse(payload.items.last().enabled)
    }

    @Test
    fun `carries forward rows from an addon that did not load`() {
        val remote = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem(addonId = "slow-addon", type = "movie", catalogId = "films", order = 0)
            )
        )
        val local = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem(addonId = "fast-addon", type = "series", catalogId = "shows", order = 0)
            )
        )

        val merged = carryForwardUnloadedCatalogRows(
            localPayload = local,
            remotePayload = remote,
            installedAddonIds = setOf("slow-addon", "fast-addon"),
            loadedAddonIds = setOf("fast-addon"),
        )

        assertEquals(2, merged.items.size)
        assertEquals("fast-addon", merged.items[0].addonId)
        assertEquals(0, merged.items[0].order)
        assertEquals("slow-addon", merged.items[1].addonId)
        assertEquals(1, merged.items[1].order)
    }

    @Test
    fun `does not carry rows when the addon loaded or is gone`() {
        val remote = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem(addonId = "loaded", type = "movie", catalogId = "films"),
                SyncCatalogItem(addonId = "gone", type = "movie", catalogId = "films"),
            )
        )
        val local = SyncHomeCatalogPayload()

        val merged = carryForwardUnloadedCatalogRows(
            localPayload = local,
            remotePayload = remote,
            installedAddonIds = setOf("loaded"),
            loadedAddonIds = setOf("loaded"),
        )

        assertTrue(merged.items.isEmpty())
    }

    @Test
    fun `does not duplicate a row the local payload already has`() {
        val item = SyncCatalogItem(addonId = "slow-addon", type = "movie", catalogId = "films")
        val remote = SyncHomeCatalogPayload(items = listOf(item.copy(order = 3)))
        val local = SyncHomeCatalogPayload(items = listOf(item.copy(order = 0)))

        val merged = carryForwardUnloadedCatalogRows(
            localPayload = local,
            remotePayload = remote,
            installedAddonIds = setOf("slow-addon"),
            loadedAddonIds = emptySet(),
        )

        assertEquals(1, merged.items.size)
    }

    private fun testAddon(
        id: String,
        baseUrl: String,
        catalogs: List<CatalogDescriptor>
    ): Addon {
        return Addon(
            id = id,
            name = id,
            displayName = id,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }
}
