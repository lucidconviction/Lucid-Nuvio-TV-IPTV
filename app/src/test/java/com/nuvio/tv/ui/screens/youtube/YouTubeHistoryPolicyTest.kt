package com.nuvio.tv.ui.screens.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeHistoryPolicyTest {

    private val durationMs = 600_000L // 10 minutes

    @Test
    fun shortStopsDoNotCreateAHistoryCard() {
        assertFalse(youtubeShouldKeepHistory(1_000L, durationMs))
        assertFalse(youtubeShouldKeepHistory(2_999L, durationMs))
        assertEquals(0L, youtubeResumePosition(2_999L, durationMs))
    }

    @Test
    fun normalStopIsKeptAndResumesAtTheSameTimestamp() {
        val stoppedAt = 180_000L
        assertTrue(youtubeShouldKeepHistory(stoppedAt, durationMs))
        assertEquals(stoppedAt, youtubeResumePosition(stoppedAt, durationMs))
    }

    @Test
    fun finishedVideosAreRemovedInsteadOfResumingFromTheStart() {
        assertFalse(youtubeShouldKeepHistory(570_000L, durationMs)) // 95%
        assertFalse(youtubeShouldKeepHistory(599_000L, durationMs)) // last second
        assertEquals(0L, youtubeResumePosition(570_000L, durationMs))
        assertEquals(0L, youtubeResumePosition(599_000L, durationMs))
    }

    @Test
    fun justUnderTheFinishBoundaryStillResumes() {
        val stoppedAt = 564_000L // 94%
        assertTrue(youtubeShouldKeepHistory(stoppedAt, durationMs))
        assertEquals(stoppedAt, youtubeResumePosition(stoppedAt, durationMs))
    }

    @Test
    fun unknownDurationHasNoResumePoint() {
        assertFalse(youtubeShouldKeepHistory(30_000L, 0L))
        assertEquals(0L, youtubeResumePosition(30_000L, 0L))
    }
}
