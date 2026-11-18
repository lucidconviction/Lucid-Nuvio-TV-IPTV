package com.nuvio.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class StreamBadgePacksTest {

    @Test
    fun `pack ids and preset URLs are unique`() {
        val ids = StreamBadgePacks.all.map { it.id }
        val urls = StreamBadgePacks.all.map { it.sourceUrl }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(urls.size, urls.toSet().size)
        assertTrue(urls.all { it.startsWith(StreamBadgePacks.PREFIX) })
    }

    @Test
    fun `every pack has named filters with valid patterns`() {
        StreamBadgePacks.all.forEach { pack ->
            assertTrue(pack.name.isNotBlank())
            assertTrue(pack.description.isNotBlank())
            assertTrue(pack.filters.isNotEmpty())
            pack.filters.forEach { filter ->
                assertTrue("${pack.id}: name", filter.name.isNotBlank())
                assertTrue("${pack.id}: pattern", filter.pattern.isNotBlank())
                Pattern.compile(filter.pattern)
            }
            val groupIds = pack.groups.map { it.id }.toSet()
            pack.filters.forEach { filter ->
                assertTrue("${pack.id}: unknown group ${filter.groupId}", filter.groupId in groupIds)
            }
        }
    }

    @Test
    fun `a pack becomes a normal import and resolves by source URL`() {
        val import = StreamBadgePacks.audio.toImport()

        assertEquals("preset://audio", import.sourceUrl)
        assertTrue(import.isActive)
        assertEquals(StreamBadgePacks.audio, StreamBadgePacks.packFor(import.sourceUrl))
        assertNotNull(StreamBadgePacks.packFor(" PRESET://AUDIO "))
        assertNull(StreamBadgePacks.packFor("https://example.com/badges.json"))
        assertTrue(StreamBadgePacks.isPreset(import.sourceUrl))
        assertFalse(StreamBadgePacks.isPreset("https://example.com/badges.json"))
    }

    @Test
    fun `a preset can be added, chosen for the player and removed`() {
        val rules = StreamBadgeRules().upsert(StreamBadgePacks.resolution.toImport())

        assertEquals(1, rules.imports.size)
        assertEquals("preset://resolution", rules.activeImport?.sourceUrl)
        assertEquals("preset://resolution", rules.forPlayer("preset://resolution").activeImport?.sourceUrl)
        assertTrue(rules.removeSource("preset://resolution").imports.isEmpty())
    }

    @Test
    fun `resolution and codec packs match a release name`() {
        val rules = StreamBadgeRules().upsert(StreamBadgePacks.resolution.toImport())
        val filters = StreamBadgeMatcher.compile(rules)

        val names = StreamBadgeMatcher
            .matchedBadges(listOf("Movie.2024.2160p.WEB-DL.x265"), filters)
            .map { it.name }

        assertEquals(listOf("4K"), names)
    }

    @Test
    fun `audio pack matches common release audio`() {
        val rules = StreamBadgeRules().upsert(StreamBadgePacks.audio.toImport())
        val filters = StreamBadgeMatcher.compile(rules)

        val names = StreamBadgeMatcher
            .matchedBadges(listOf("Movie.2024.1080p.WEB-DL.DDP5.1.Atmos.x264"), filters)
            .map { it.name }

        assertEquals(listOf("Atmos", "5.1"), names)
    }
}
