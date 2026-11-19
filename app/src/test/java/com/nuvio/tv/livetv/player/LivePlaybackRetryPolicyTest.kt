package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackRetryPolicyTest {

    @Test
    fun `provider refusals are not treated as freezes`() {
        listOf(401, 403, 404, 410, 429, 456, 458, 509).forEach { status ->
            assertTrue("status $status", LivePlaybackRetryPolicy.isProviderRefusal(status))
        }
        assertFalse(LivePlaybackRetryPolicy.isProviderRefusal(500))
        assertFalse(LivePlaybackRetryPolicy.isProviderRefusal(null))
    }

    @Test
    fun `expired or blocked lines stop immediately`() {
        listOf(401, 403, 404, 410).forEach { status ->
            assertTrue("status $status", LivePlaybackRetryPolicy.isImmediateStop(status))
            assertFalse("status $status", LivePlaybackRetryPolicy.shouldAutoReconnect(1, status))
        }
    }

    @Test
    fun `connection limits get one delayed retry`() {
        listOf(429, 456, 458, 509).forEach { status ->
            assertTrue("status $status", LivePlaybackRetryPolicy.shouldAutoReconnect(1, status))
            assertFalse("status $status", LivePlaybackRetryPolicy.shouldAutoReconnect(2, status))
            assertEquals(5_000L, LivePlaybackRetryPolicy.delayMs(1, status))
        }
    }

    @Test
    fun `network errors keep the normal ladder`() {
        assertTrue(LivePlaybackRetryPolicy.shouldAutoReconnect(8, null))
        assertEquals(2_000L, LivePlaybackRetryPolicy.delayMs(1, null))
        assertEquals(10_000L, LivePlaybackRetryPolicy.delayMs(8, null))
    }
}
