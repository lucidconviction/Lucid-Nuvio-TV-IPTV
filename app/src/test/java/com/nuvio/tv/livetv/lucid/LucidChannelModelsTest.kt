package com.nuvio.tv.livetv.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LucidChannelModelsTest {

    private fun item(
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
    ) = LucidItem(
        id = "tt123:1:1",
        streamId = "tt123:1:1",
        type = type,
        title = "Example Show",
        poster = null,
        backdrop = null,
        description = "An example.",
        year = 2024,
        runtimeMs = 45 * 60_000L,
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
    )

    @Test
    fun allVodKindsUseTheRuntimeQueue() {
        listOf(
            LucidChannelKind.CATALOG_ROTATE,
            LucidChannelKind.CATALOG_LOOP,
            LucidChannelKind.SERIES_LOOP,
            LucidChannelKind.MANUAL_LOOP,
            LucidChannelKind.RELAY_LOOP,
        ).forEach { kind ->
            assertTrue(
                "kind=$kind",
                LucidChannelSpec(
                    id = "vc_test",
                    name = "Test",
                    catalogName = "Test",
                    addonName = "Addon",
                    addonBaseUrl = "https://addon.example",
                    kind = kind,
                ).usesQueue,
            )
        }
        assertFalse(
            LucidChannelSpec(
                id = "vc_live",
                name = "Live",
                catalogName = "Live",
                addonName = "Addon",
                addonBaseUrl = "https://addon.example",
                kind = LucidChannelKind.ADDON_LIVE,
            ).usesQueue,
        )
    }

    @Test
    fun episodeProgramsShowSeasonAndEpisode() {
        val spec = LucidChannelSpec(
            id = "vc_test",
            name = "Test",
            catalogName = "Test",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            kind = LucidChannelKind.CATALOG_LOOP,
        )
        val program = item(
            type = "series",
            season = 2,
            episode = 5,
            episodeTitle = "The One",
        ).toProgram(spec, 1_000L, 2_000L)
        assertEquals("Example Show · S2E5 · The One", program.title)
        assertEquals("S2E5", program.episode)
    }

    @Test
    fun movieProgramsKeepTheirPlainTitle() {
        val spec = LucidChannelSpec(
            id = "vc_test",
            name = "Test",
            catalogName = "Test",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            kind = LucidChannelKind.CATALOG_ROTATE,
        )
        val program = item().toProgram(spec, 1_000L, 2_000L)
        assertEquals("Example Show", program.title)
        assertNull(program.episode)
    }

    @Test
    fun catalogRuntimesParseMinutesAndHours() {
        assertEquals(101 * 60_000L, LucidTvRepository.parseRuntimeMs("101 min"))
        assertEquals((1 * 60 + 45) * 60_000L, LucidTvRepository.parseRuntimeMs("1 h 45 m"))
        assertEquals(45 * 60_000L, LucidTvRepository.parseRuntimeMs("45m"))
        assertEquals(null, LucidTvRepository.parseRuntimeMs(null))
        assertEquals(null, LucidTvRepository.parseRuntimeMs("unknown"))
    }
}
