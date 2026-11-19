package com.nuvio.tv.livetv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LucidAdvancePolicyTest {

    private val end = 1_000_000L

    @Test
    fun `boundary gets a small grace before it is due`() {
        assertFalse(LucidAdvancePolicy.isDue(end + 2_999L, end))
        assertTrue(LucidAdvancePolicy.isDue(end + LucidAdvancePolicy.GRACE_MS, end))
    }

    @Test
    fun `pause background and an in-flight resolve block the advance`() {
        val due = end + LucidAdvancePolicy.GRACE_MS
        assertTrue(LucidAdvancePolicy.canAdvance(due, end, userPaused = false, inBackground = false, resolving = false))
        assertFalse(LucidAdvancePolicy.canAdvance(due, end, userPaused = true, inBackground = false, resolving = false))
        assertFalse(LucidAdvancePolicy.canAdvance(due, end, userPaused = false, inBackground = true, resolving = false))
        assertFalse(LucidAdvancePolicy.canAdvance(due, end, userPaused = false, inBackground = false, resolving = true))
    }
}
