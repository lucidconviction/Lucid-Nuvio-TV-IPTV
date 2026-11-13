package com.nuvio.tv.livetv.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LucidScheduleTest {

    @Test
    fun hashIsStableAndNonNegative() {
        assertEquals(LucidSchedule.stableHash("lucid-tv"), LucidSchedule.stableHash("lucid-tv"))
        listOf("", "a", "vc_123", "addon|catalog|movie|top").forEach {
            assertTrue(LucidSchedule.stableHash(it) >= 0)
        }
    }

    @Test
    fun offsetStaysInsideTheSlot() {
        val slotMs = 90 * 60_000L
        repeat(500) { i ->
            val offset = LucidSchedule.offsetMs("channel-$i", slotMs)
            assertTrue(offset in 0 until slotMs)
        }
    }

    @Test
    fun slotContainsNowAndBoundariesAreExact() {
        val slotMs = 90 * 60_000L
        val channel = "vc_example"
        val offset = LucidSchedule.offsetMs(channel, slotMs)
        listOf(0L, 1L, -1L, 1_700_000_000_000L, -1_700_000_000_000L).forEach { now ->
            val slot = LucidSchedule.slotAt(now, channel, slotMs)
            assertTrue("$now not in ${slot.startMs}..${slot.endMs}", now >= slot.startMs && now < slot.endMs)
            assertEquals(offset, Math.floorMod(slot.startMs, slotMs))
            assertEquals(slotMs, slot.endMs - slot.startMs)
        }
    }

    @Test
    fun slotsAreContiguous() {
        val slotMs = 45 * 60_000L
        val channel = "vc_contiguous"
        var slot = LucidSchedule.slotAt(1_700_000_000_000L, channel, slotMs)
        repeat(100) {
            val next = LucidSchedule.slotForNumber(slot.number + 1, channel, slotMs)
            assertEquals(slot.endMs, next.startMs)
            slot = next
        }
    }

    @Test
    fun itemIndexIsDeterministicAndSaltChangesIt() {
        val first = LucidSchedule.itemIndexFor("vc_a", 42L, 17)
        val second = LucidSchedule.itemIndexFor("vc_a", 42L, 17)
        assertEquals(first, second)
        assertTrue(first in 0 until 17)
        val salted = LucidSchedule.itemIndexFor("vc_a", 42L, 17, salt = 3)
        assertTrue(salted in 0 until 17)
        val different = (0..9).map { LucidSchedule.itemIndexFor("vc_a", 42L, 1000, salt = it) }
        assertTrue(different.distinct().size > 1)
    }

    @Test
    fun joinOffsetsFollowThePercentRules() {
        val slot = LucidSchedule.Slot(10L, 1_000_000L, 1_000_000L + 90 * 60_000L)
        val now = slot.startMs + 45 * 60_000L
        assertEquals(0L, LucidSchedule.joinOffsetMs("vc_a", slot, now, 0, true))
        val raw = LucidSchedule.joinOffsetMs("vc_a", slot, now, -1, true)
        assertEquals(45 * 60_000L, raw)
        val capped = LucidSchedule.joinOffsetMs("vc_a", slot, now, 20, true)
        assertTrue(capped in 0..(90 * 60_000L * 20 / 100))
        assertEquals(capped, LucidSchedule.joinOffsetMs("vc_a", slot, now, 20, true))
        val early = LucidSchedule.joinOffsetMs("vc_a", slot, slot.startMs + 1_000L, 20, true)
        assertEquals(1_000L, early)
    }

    @Test
    fun loopQueueWrapsAndKeepsRealDurations() {
        val durations = listOf(20 * 60_000L, 40 * 60_000L, 10 * 60_000L)
        val anchor = 1_000_000L
        val cycle = durations.sum()
        val at = anchor + cycle * 3 + 25 * 60_000L
        val slot = LucidSchedule.queueAt(at, anchor, durations) ?: error("no slot")
        assertEquals(1, slot.itemIndex)
        assertEquals(slot.startMs + durations[1], slot.endMs)
        assertTrue(at >= slot.startMs && at < slot.endMs)
        val next = LucidSchedule.queueAt(slot.endMs, anchor, durations) ?: error("no next")
        assertEquals(2, next.itemIndex)
        assertEquals(slot.endMs, next.startMs)
    }

    @Test
    fun loopScheduleIsContiguousAndCoversTheWindow() {
        val durations = listOf(30 * 60_000L, 45 * 60_000L, 20 * 60_000L)
        val anchor = 5_000_000L
        val from = anchor + durations.sum() * 7 + 10 * 60_000L
        val to = from + 6 * 60 * 60_000L
        val slots = LucidSchedule.queueSchedule(anchor, durations, from, to)
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.first().startMs <= from)
        assertTrue(slots.last().endMs >= to)
        slots.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        slots.forEach { assertTrue(it.endMs > it.startMs) }
    }

    @Test
    fun specCodecRoundTripsChannelsAndPacks() {
        val spec = LucidStoredSpec(
            id = "vc_test",
            name = "24/7 Test",
            kind = LucidChannelKind.RELAY_LOOP,
            group = "My 24/7",
            relayGroupId = "group-1",
            relaySegmentMinutes = 30,
            anchorMs = 123L,
        )
        val decoded = LucidSpecCodec.decodeSpecs(LucidSpecCodec.encodeSpecs(listOf(spec)))
        assertEquals(listOf(spec), decoded)
        val pack = LucidSpecCodec.decodePack(LucidSpecCodec.encodePack(listOf(spec)))
        assertEquals(listOf(spec), pack)
        assertEquals(emptyList<LucidStoredSpec>(), LucidSpecCodec.decodeSpecs("{not json}"))
    }
}
