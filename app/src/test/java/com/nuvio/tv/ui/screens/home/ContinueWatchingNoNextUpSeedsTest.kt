package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingNoNextUpSeedsTest {

    private val now = 1_000_000L
    private val day = NO_NEXT_UP_RECHECK_MS

    @Test
    fun `a known release date is rechecked when it comes out`() {
        assertEquals(now + 5_000L, noNextUpRecheckAt(now + 5_000L, now))
    }

    @Test
    fun `an older release date is rechecked now`() {
        assertEquals(now, noNextUpRecheckAt(now - day, now))
    }

    @Test
    fun `a release date beyond a day is rechecked after a day`() {
        assertEquals(now + day, noNextUpRecheckAt(now + 30L * day, now))
    }

    @Test
    fun `no release date is rechecked after a day`() {
        assertEquals(now + day, noNextUpRecheckAt(null, now))
    }

    @Test
    fun `a seed confirmed before its recheck time is skipped`() {
        assertTrue(isNoNextUpStillConfirmed(now + 1L, now))
    }

    @Test
    fun `a seed at or past its recheck time is looked up again`() {
        assertFalse(isNoNextUpStillConfirmed(now, now))
        assertFalse(isNoNextUpStillConfirmed(now - 1L, now))
    }

    @Test
    fun `a seed never confirmed is looked up`() {
        assertFalse(isNoNextUpStillConfirmed(null, now))
    }
}
