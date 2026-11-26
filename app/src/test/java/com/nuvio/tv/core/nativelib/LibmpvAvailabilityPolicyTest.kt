package com.nuvio.tv.core.nativelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibmpvAvailabilityPolicyTest {
    @Test
    fun libmpvWantedButNotLoadableFallsBack() {
        assertFalse(LibmpvAvailabilityPolicy.usable(wanted = true) { false })
    }

    @Test
    fun libmpvWantedAndLoadableIsUsed() {
        assertTrue(LibmpvAvailabilityPolicy.usable(wanted = true) { true })
    }

    @Test
    fun probeIsNotRunWhenLibmpvIsNotWanted() {
        var probed = 0
        assertFalse(LibmpvAvailabilityPolicy.usable(wanted = false) { probed++; true })
        assertEquals(0, probed)
    }
}
