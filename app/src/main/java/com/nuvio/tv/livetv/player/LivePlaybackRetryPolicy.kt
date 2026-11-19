package com.nuvio.tv.livetv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * Provider-side refusals (account limits, expired lines, missing channels) are not a freeze:
 * re-asking the same link every few seconds keeps the provider busy and can hold a connection
 * slot. Network/parse errors still use the normal bounded reconnect ladder.
 */
internal object LivePlaybackRetryPolicy {

    fun providerRefusalStatus(error: PlaybackException?): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    fun isProviderRefusal(status: Int?): Boolean = status in setOf(
        401, 403, 404, 410, 429, 456, 458, 509,
    )

    /** A wrong/expired line: asking again immediately cannot succeed. */
    fun isImmediateStop(status: Int?): Boolean = status in setOf(401, 403, 404, 410)

    /** 429/456/458/509 get one delayed retry; the rest stop at the first refusal. */
    fun shouldAutoReconnect(attempt: Int, status: Int?): Boolean {
        if (!isProviderRefusal(status)) return true
        return !isImmediateStop(status) && attempt < 2
    }

    fun delayMs(attempt: Int, status: Int?): Long =
        if (status in setOf(429, 456, 458, 509)) {
            5_000L
        } else {
            (attempt.coerceAtLeast(1) * 2_000L).coerceAtMost(10_000L)
        }
}
