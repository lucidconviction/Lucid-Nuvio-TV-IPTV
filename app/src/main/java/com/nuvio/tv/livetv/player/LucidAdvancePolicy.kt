package com.nuvio.tv.livetv.player

/**
 * When a Lucid TV channel is allowed to move to the item the guide says is on now. Kept pure so
 * the timer, the watchdog and the tests agree on the paused/grace rules.
 */
internal object LucidAdvancePolicy {
    /** How late a boundary may be before the watchdog takes over. */
    const val GRACE_MS = 3_000L

    fun isDue(nowMs: Long, slotEndMs: Long): Boolean = nowMs >= slotEndMs + GRACE_MS

    fun canAdvance(
        nowMs: Long,
        slotEndMs: Long,
        userPaused: Boolean,
        inBackground: Boolean,
        resolving: Boolean,
    ): Boolean = !userPaused && !inBackground && !resolving && isDue(nowMs, slotEndMs)
}
