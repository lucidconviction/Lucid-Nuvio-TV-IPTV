package com.nuvio.tv.ui.util

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDirectionUtilsTest {

    @Test
    fun `hebrew and arabic text read right to left`() {
        assertEquals(TextDirection.Rtl, "שלום עולם".contentTextDirection())
        assertEquals(TextDirection.Rtl, "مرحبا بالعالم".contentTextDirection())
        assertTrue("شارع".isContentRtl())
    }

    @Test
    fun `latin and digit-led text read left to right`() {
        assertEquals(TextDirection.Ltr, "Hello world".contentTextDirection())
        assertEquals(TextDirection.Ltr, "2 GB".contentTextDirection())
        assertFalse("News".isContentRtl())
    }

    @Test
    fun `flags and emoji do not decide the direction`() {
        assertEquals(TextDirection.Rtl, "🇮🇱 ערוץ".contentTextDirection())
        assertEquals(TextDirection.Ltr, "🇺🇸 News".contentTextDirection())
    }

    @Test
    fun `absolute alignment follows the text direction`() {
        assertEquals(AbsoluteAlignment.Right, TextDirection.Rtl.toAbsoluteAlignment())
        assertEquals(AbsoluteAlignment.Left, TextDirection.Ltr.toAbsoluteAlignment())
    }

    @Test
    fun `keepLtr protects left-to-right text but leaves rtl text alone`() {
        assertEquals("\u200ENews\u200E", "News".keepLtr())
        assertEquals("חדשות", "חדשות".keepLtr())
    }
}
