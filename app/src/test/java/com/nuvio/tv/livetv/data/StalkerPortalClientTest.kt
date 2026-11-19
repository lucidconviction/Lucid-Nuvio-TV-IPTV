package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerPortalClientTest {

    @Test
    fun `portal address is reduced to its base`() {
        assertEquals(
            "http://portal.example:8080",
            StalkerPortalClient.normalizePortal("portal.example:8080/c/"),
        )
        assertEquals(
            "https://portal.example",
            StalkerPortalClient.normalizePortal("https://portal.example/portal.php?type=stb"),
        )
        assertEquals(
            "http://portal.example",
            StalkerPortalClient.normalizePortal("http://portal.example/server/load.php"),
        )
    }

    @Test
    fun `mac is normalised and generated in the MAG prefix`() {
        assertEquals("00:1A:79:AA:BB:CC", StalkerPortalClient.normalizeMac("00-1a-79-aa-bb-cc"))
        assertTrue(StalkerPortalClient.randomMac().matches(Regex("00:1A:79:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}")))
    }

    @Test
    fun `player prefix is stripped from a portal command`() {
        assertEquals("http://stream", StalkerPortalClient.stripCommandPrefix("ffmpeg http://stream"))
        assertEquals("http://stream", StalkerPortalClient.stripCommandPrefix("auto http://stream"))
        assertEquals("http://stream", StalkerPortalClient.stripCommandPrefix("http://stream"))
    }

    @Test
    fun `m3u keeps the channel id name logo and genre`() {
        val text = StalkerPortalClient.buildM3u(
            "playlist-1",
            listOf(
                StalkerPortalChannel(
                    id = "42",
                    name = "News One",
                    command = "ffmpeg http://stream/42",
                    logo = "http://logo/42.png",
                    genreId = "News",
                )
            ),
        )

        assertTrue(text.contains("#EXTM3U"))
        assertTrue(text.contains("tvg-id=\"42\""))
        assertTrue(text.contains("group-title=\"News\""))
        assertTrue(text.contains("stalker:playlist-1:42"))
    }
}
