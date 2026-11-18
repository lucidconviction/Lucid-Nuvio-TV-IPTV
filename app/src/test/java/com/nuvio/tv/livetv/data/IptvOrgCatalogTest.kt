package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOrgCatalogTest {

    @Test
    fun `countries become M3U URLs and All stays first`() {
        val lists = parseCountries(
            """[
              {"name":"United States","code":"US","flag":"🇺🇸"},
              {"name":"Albania","code":"AL","flag":"🇦🇱"}
            ]"""
        )

        assertEquals("ALL", lists.first().code)
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", lists.first().url)
        assertEquals("Albania", lists[1].name)
        assertEquals("🇦🇱", lists[1].flag)
        assertEquals("https://iptv-org.github.io/iptv/countries/al.m3u", lists[1].url)
        assertEquals("United States", lists[2].name)
        assertEquals("https://iptv-org.github.io/iptv/countries/us.m3u", lists[2].url)
    }

    @Test
    fun `categories keep their id and description`() {
        val lists = parseCategories(
            """[{"id":"news","name":"News","description":"Programming with news"}]"""
        )

        assertEquals(1, lists.size)
        assertEquals("news", lists[0].code)
        assertEquals("Programming with news", lists[0].subtitle)
        assertEquals("https://iptv-org.github.io/iptv/categories/news.m3u", lists[0].url)
    }

    @Test
    fun `regions use the lowercase code`() {
        val lists = parseRegions(
            """[{"code":"AFR","name":"Africa"}]"""
        )

        assertEquals("AFR", lists[0].code)
        assertEquals("https://iptv-org.github.io/iptv/regions/afr.m3u", lists[0].url)
    }

    @Test
    fun `blank entries are skipped`() {
        assertEquals(2, parseCountries("""[{"name":"","code":"US"},{"name":"Albania","code":"AL"}]""").size)
        assertTrue(parseCategories("[]").isEmpty())
        assertTrue(parseRegions("[]").isEmpty())
    }
}
