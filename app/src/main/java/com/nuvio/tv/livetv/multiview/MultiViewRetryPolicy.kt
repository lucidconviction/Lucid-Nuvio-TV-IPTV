package com.nuvio.tv.livetv.multiview

/**
 * How a Multi-view pane behaves when its stream stops making progress. The pane rebuilds a few
 * times with a growing delay; once the retry budget is spent it stops instead of looping forever
 * on a refused/dead link. With auto-backup on, a later failure asks for another copy of the
 * channel first.
 */
internal object MultiViewRetryPolicy {
    /** Rebuilds after failures 1..MAX_ATTEMPTS-1; the MAX_ATTEMPTS-th failure stops the pane. */
    const val MAX_ATTEMPTS = 5

    fun canRetry(attempt: Int): Boolean = attempt < MAX_ATTEMPTS

    fun delayMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
        1 -> 2_000L
        2 -> 4_000L
        3 -> 8_000L
        4 -> 15_000L
        else -> 30_000L
    }

    fun shouldTryBackup(attempt: Int, autoBackupChannel: Boolean): Boolean =
        autoBackupChannel && attempt >= 2
}
