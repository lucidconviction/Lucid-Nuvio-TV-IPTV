package com.nuvio.tv.livetv.lucid

/**
 * The clock behind Lucid TV. Every channel is a deterministic rotation over its catalog:
 * the same channel, pool and time give the same film on every device, with no server and no
 * schedule file. The hash and slot math match Debrify's Stremio TV, so the feel is the same.
 */
object LucidSchedule {

    /** Stable 31-bit DJB2, the same hash Debrify uses. */
    fun stableHash(input: CharSequence): Int {
        var hash = 5381
        for (i in input.indices) {
            hash = ((hash shl 5) + hash) + input[i].code
            hash = hash and 0x7FFFFFFF
        }
        return hash
    }

    /** Per-channel offset in [0, slotMs), so channels don't all change film at :00 and :30. */
    fun offsetMs(channelId: String, slotMs: Long): Long {
        require(slotMs > 0) { "slotMs must be positive" }
        var h = stableHash(channelId)
        h = (((h ushr 16) xor h) * 0x45d9f3b) and 0x7FFFFFFF
        h = (((h ushr 16) xor h) * 0x45d9f3b) and 0x7FFFFFFF
        h = (h ushr 16) xor h
        val r = h.toLong() % slotMs
        return if (r < 0) r + slotMs else r
    }

    data class Slot(val number: Long, val startMs: Long, val endMs: Long)

    fun slotAt(nowMs: Long, channelId: String, slotMs: Long): Slot {
        val offset = offsetMs(channelId, slotMs)
        val number = floorDiv(nowMs - offset, slotMs)
        val start = number * slotMs + offset
        return Slot(number, start, start + slotMs)
    }

    fun slotForNumber(number: Long, channelId: String, slotMs: Long): Slot {
        val start = number * slotMs + offsetMs(channelId, slotMs)
        return Slot(number, start, start + slotMs)
    }

    /** Which film in the pool this slot shows. Salt shifts the sequence without moving the clock. */
    fun itemIndexFor(channelId: String, slotNumber: Long, poolSize: Int, salt: Int = 0): Int {
        require(poolSize > 0) { "poolSize must be positive" }
        val seed = if (salt == 0) "$channelId:$slotNumber" else "$channelId:$slotNumber:$salt"
        return floorMod(stableHash(seed).toLong(), poolSize.toLong()).toInt()
    }

    data class QueueSlot(val itemIndex: Int, val startMs: Long, val endMs: Long)

    /**
     * LOOP playout: the queue is walked from [anchorMs] with real item durations and repeats
     * forever. Unlike ROTATE there is no hash: the next programme is simply the next item, so
     * the guide matches the media lengths.
     */
    fun queueAt(nowMs: Long, anchorMs: Long, durations: List<Long>): QueueSlot? {
        val cycle = durations.sum()
        if (durations.isEmpty() || cycle <= 0L) return null
        val t = floorMod(nowMs - anchorMs, cycle)
        var acc = 0L
        durations.forEachIndexed { index, duration ->
            if (t < acc + duration) {
                val start = nowMs - (t - acc)
                return QueueSlot(index, start, start + duration)
            }
            acc += duration
        }
        return null
    }

    /** The whole LOOP queue covering [fromMs, toMs), including the programme already running. */
    fun queueSchedule(
        anchorMs: Long,
        durations: List<Long>,
        fromMs: Long,
        toMs: Long,
    ): List<QueueSlot> {
        val cycle = durations.sum()
        if (durations.isEmpty() || cycle <= 0L || toMs <= fromMs) return emptyList()
        val tFrom = floorMod(fromMs - anchorMs, cycle)
        var base = fromMs - tFrom
        val out = ArrayList<QueueSlot>()
        var guard = 0
        while (base < toMs && guard++ < 20_000) {
            var acc = 0L
            durations.forEachIndexed { index, duration ->
                val start = base + acc
                val end = start + duration
                if (end > fromMs && start < toMs) out += QueueSlot(index, start, end)
                acc += duration
            }
            base += cycle
        }
        return out
    }

    /**
     * Where to start inside the current slot, so tuning in feels like joining a broadcast that has
     * already started. A cap keeps the join point away from the end of a short film; the jitter
     * stops every viewer of the channel joining at exactly the same second.
     *
     * [maxStartPercent] 0 = always start at the beginning, negative = use the real slot progress.
     */
    fun joinOffsetMs(
        channelId: String,
        slot: Slot,
        nowMs: Long,
        maxStartPercent: Int,
        jitter: Boolean,
    ): Long {
        val slotMs = (slot.endMs - slot.startMs).coerceAtLeast(1L)
        val raw = (nowMs - slot.startMs).coerceIn(0L, slotMs)
        if (maxStartPercent == 0) return 0L
        if (maxStartPercent < 0) return raw
        val cap = slotMs * maxStartPercent.coerceIn(0, 100) / 100
        if (raw <= cap) return raw
        if (!jitter) return cap
        val factor = (stableHash("$channelId:join") % 1000) / 1000.0
        return (cap * factor).toLong().coerceIn(0L, cap)
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if (a % b != 0L && ((a xor b) < 0)) q--
        return q
    }

    private fun floorMod(a: Long, b: Long): Long {
        val r = a % b
        return if (r < 0) r + b else r
    }
}
