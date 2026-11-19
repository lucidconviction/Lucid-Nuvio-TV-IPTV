package com.nuvio.tv.livetv.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiViewRetryPolicyTest {

    @Test
    fun `retry budget stops after five failures`() {
        assertTrue(MultiViewRetryPolicy.canRetry(1))
        assertTrue(MultiViewRetryPolicy.canRetry(4))
        assertFalse(MultiViewRetryPolicy.canRetry(5))
    }

    @Test
    fun `delay grows with each attempt and is capped`() {
        assertEquals(2_000L, MultiViewRetryPolicy.delayMs(1))
        assertEquals(4_000L, MultiViewRetryPolicy.delayMs(2))
        assertEquals(8_000L, MultiViewRetryPolicy.delayMs(3))
        assertEquals(15_000L, MultiViewRetryPolicy.delayMs(4))
        assertEquals(30_000L, MultiViewRetryPolicy.delayMs(5))
        assertEquals(30_000L, MultiViewRetryPolicy.delayMs(20))
    }

    @Test
    fun `backup is offered only when enabled and after two failures`() {
        assertFalse(MultiViewRetryPolicy.shouldTryBackup(1, autoBackupChannel = true))
        assertTrue(MultiViewRetryPolicy.shouldTryBackup(2, autoBackupChannel = true))
        assertFalse(MultiViewRetryPolicy.shouldTryBackup(2, autoBackupChannel = false))
    }
}
