package com.nuvio.tv.livetv.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LucidShareCodecTest {

    private fun spec(id: String) = LucidStoredSpec(
        id = id,
        name = "Channel $id",
        kind = LucidChannelKind.CATALOG_ROTATE,
        addonId = "addon",
        addonBaseUrl = "https://example.test",
        catalogId = "top",
        contentType = "movie",
    )

    @Test
    fun packRoundTripsThroughAShareCode() {
        val specs = listOf(spec("a"), spec("b"))
        val json = LucidSpecCodec.encodePack(specs)
        val code = LucidShareCodec.encode(json)
        assertTrue(code.startsWith(LucidShareCodec.PREFIX))
        assertEquals(listOf("a", "b"), LucidShareCodec.decode(code)?.map { it.id })
    }

    @Test
    fun linkAndRawJsonAlsoImport() {
        val specs = listOf(spec("one"))
        val code = LucidShareCodec.encode(LucidSpecCodec.encodePack(specs))
        assertEquals("one", LucidShareCodec.decode("nuvio://lucid?pack=$code")?.single()?.id)
        assertEquals("one", LucidShareCodec.decode(LucidSpecCodec.encodePack(specs))?.single()?.id)
    }

    @Test
    fun brokenInputIsRejected() {
        assertNull(LucidShareCodec.decode(null))
        assertNull(LucidShareCodec.decode(""))
        assertNull(LucidShareCodec.decode("not a pack"))
        assertNull(LucidShareCodec.decode("lucid1:!!!!"))
    }
}
