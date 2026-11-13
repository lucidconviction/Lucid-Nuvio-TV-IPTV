package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick Ch Search matching and liveness heuristics. */
class QuickChannelsLogicTest {

    private fun channel(name: String, group: String = "General") = LiveChannel(
        key = name,
        sourceId = "s",
        sourceName = "Playlist",
        name = name,
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = group,
        group = group,
        number = 1,
        url = "http://example/$name",
    )

    @Test
    fun rtDoesNotMatchCartoonNetworkButMatchesItsOwnNames() {
        val rt = QuickChannel("RT", listOf("RT News", "Russia Today"), listOf("US"))
        assertFalse(QuickChannelList.matches(rt, channel("Cartoon Network")))
        assertFalse(QuickChannelList.matches(rt, channel("Business Plus")))
        assertTrue(QuickChannelList.matches(rt, channel("RT")))
        assertTrue(QuickChannelList.matches(rt, channel("RT HD")))
        assertTrue(QuickChannelList.matches(rt, channel("Russia Today")))
    }

    @Test
    fun cnnAliasesMatchSpacingAndSuffixVariants() {
        val cnn = QuickChannel("CNN", listOf("CNN US", "CNN USA", "CNN International"), listOf("US"))
        assertTrue(QuickChannelList.matches(cnn, channel("CNN")))
        assertTrue(QuickChannelList.matches(cnn, channel("CNN US")))
        assertTrue(QuickChannelList.matches(cnn, channel("CNNUS HD")))
        assertTrue(QuickChannelList.matches(cnn, channel("CNN International")))
        assertFalse(QuickChannelList.matches(cnn, channel("Comedy Central")))
    }

    @Test
    fun punctuationAndSpacingDoNotBreakWholePhraseMatching() {
        val show = QuickChannel("Real America's Voice", listOf("RAV"), listOf("US"))
        assertTrue(QuickChannelList.matches(show, channel("Real Americas Voice")))
        assertTrue(QuickChannelList.matches(show, channel("REAL AMERICA'S VOICE HD")))
        assertFalse(QuickChannelList.matches(show, channel("America's Voice")))
    }

    @Test
    fun twoLetterPhrasesOnlyMatchWholeWords() {
        val us = QuickChannel("US", emptyList(), listOf("US"))
        assertTrue(QuickChannelList.matches(us, channel("US")))
        assertTrue(QuickChannelList.matches(us, channel("US | East")))
        assertFalse(QuickChannelList.matches(us, channel("Business")))
        assertFalse(QuickChannelList.matches(us, channel("Russia Today")))
    }

    @Test
    fun regionBundleMatchesNameAndGroupTokens() {
        val us = QuickChannel("US Channels", listOf("usa", "united states"), listOf("US"))
        assertTrue(QuickChannelList.matches(us, channel("CNN", group = "US | News")))
        assertTrue(QuickChannelList.matches(us, channel("USA Network", group = "General")))
        assertFalse(QuickChannelList.matches(us, channel("Business", group = "General")))
    }

    @Test
    fun categoriesNarrowTheCuratedList() {
        assertTrue(QuickChannelList.getChannelsForCategory("sports").isNotEmpty())
        assertTrue(QuickChannelList.getChannelsForCategory("sports").all { "sports" in it.tags })
        assertTrue(QuickChannelList.getChannelsForCategory("uk").all { "UK" in it.effectiveRegions })
        assertEquals(QuickChannelList.combinedChannels().size, QuickChannelList.getChannelsForCategory("all").size)
    }

    @Test
    fun customRegionsAndChannelsAreSavedAndSearched() {
        QuickChannelList.load(null)
        assertTrue(
            QuickChannelList.addCustomCategory(
                QuickChannelList.QuickCategory("my-region", "My Region", tags = listOf("regional"))
            )
        )
        assertTrue(
            QuickChannelList.addCustomChannel(
                QuickChannel("My Channel", listOf("Mine"), listOf("CUSTOM"), listOf("regional"))
            )
        )
        assertEquals(
            listOf("My Channel"),
            QuickChannelList.getChannelsForCategory("my-region").map { it.displayName }
        )
        val restored = QuickChannelList.encodeCustom()
        QuickChannelList.load(null)
        assertTrue(QuickChannelList.encodeCustom().contains("\"channels\":[]"))
        QuickChannelList.load(restored)
        assertEquals(listOf("My Channel"), QuickChannelList.all.filter { QuickChannelList.isCustomChannel(it.displayName) }.map { it.displayName })
        assertTrue(QuickChannelList.isCustomCategory("my-region"))
        assertTrue(QuickChannelList.removeCustomChannel("My Channel"))
        assertTrue(QuickChannelList.removeCustomCategory("my-region"))
        QuickChannelList.load(null)
    }

    @Test
    fun manifestHeaderIsAliveEvenWhenItMentionsErrors() {
        assertTrue(QuickStreamChecker.looksAlive("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,error channel"))
        assertTrue(QuickStreamChecker.looksAlive("\uFEFF  \n#EXTM3U\n"))
    }

    @Test
    fun htmlAndErrorPagesAreDead() {
        assertFalse(QuickStreamChecker.looksAlive(""))
        assertFalse(QuickStreamChecker.looksAlive("   \n\t"))
        assertFalse(QuickStreamChecker.looksAlive("<!DOCTYPE html><html><body>Not Found</body></html>"))
        assertFalse(QuickStreamChecker.looksAlive("<html><body>404</body></html>"))
        assertFalse(QuickStreamChecker.looksAlive("Service Unavailable"))
        assertFalse(QuickStreamChecker.looksAlive("invalid username"))
    }

    @Test
    fun binaryTransportStreamSamplesAreAlive() {
        val sample = "G@\u0001\u0002\u0010\u0003\u0000\u0004stream-packet"
        assertTrue(QuickStreamChecker.looksAlive(sample))
    }

    @Test
    fun probeBytesDecodeOneCharacterPerByte() {
        val ts = byteArrayOf(0x47, 0x40, 0x01, 0x02, 0x10, 0x03, 0x00, 0x04)
        val sample = QuickStreamChecker.decodeSample(ts, ts.size)
        assertEquals(ts.size, sample.length)
        assertEquals(0x47, sample[0].code)
        assertEquals(0x01, sample[2].code)
        assertTrue(QuickStreamChecker.looksAlive(sample))

        val manifest = "#EXTM3U\n#EXT-X-VERSION:3\n".encodeToByteArray()
        val decoded = QuickStreamChecker.decodeSample(manifest, manifest.size)
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n", decoded)
        assertTrue(QuickStreamChecker.looksAlive(decoded))
    }

    @Test
    fun binarySamplesContainingErrorWordsAreAlive() {
        assertTrue(QuickStreamChecker.looksAlive("G@\u0001\u0002\u0010\u0003\u0000\u0004 error 404 not found offline"))
        assertTrue(QuickStreamChecker.looksAlive("\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0002\u0000isomiso2 error: 401"))
    }

    @Test
    fun quickDataRoundTripsThroughTheCodec() {
        val categories = listOf(
            QuickChannelList.QuickCategory("my-region", "My Region", "Local favourites", listOf("news", "sports"))
        )
        val channels = listOf(
            QuickChannel("My Channel", listOf("Mine", "Local One"), listOf("CUSTOM"), listOf("news"))
        )
        val decoded = QuickChannelList.QuickChannelCodec.decode(
            QuickChannelList.QuickChannelCodec.encode(categories, channels)
        )
        assertEquals(1, decoded.categories.size)
        assertEquals("my-region", decoded.categories.single().id)
        assertEquals(listOf("news", "sports"), decoded.categories.single().tags)
        assertEquals("My Channel", decoded.channels.single().displayName)
        assertEquals(listOf("Mine", "Local One"), decoded.channels.single().aliases)
    }

    @Test
    fun blankIdsAndNamesAreDropped() {
        val raw = """
            {
              "categories": [ { "id": "", "title": "No id" }, { "id": "ok", "title": "OK" } ],
              "channels": [ { "displayName": "  " }, { "displayName": "Saved" } ]
            }
        """.trimIndent()
        val decoded = QuickChannelList.QuickChannelCodec.decode(raw)
        assertEquals(listOf("ok"), decoded.categories.map { it.id })
        assertEquals(listOf("Saved"), decoded.channels.map { it.displayName })
    }

    @Test
    fun missingOrBrokenJsonGivesAnEmptyStore() {
        assertTrue(QuickChannelList.QuickChannelCodec.decode(null).channels.isEmpty())
        assertTrue(QuickChannelList.QuickChannelCodec.decode("not json").channels.isEmpty())
        assertTrue(QuickChannelList.QuickChannelCodec.decode("{}").categories.isEmpty())
    }
}
