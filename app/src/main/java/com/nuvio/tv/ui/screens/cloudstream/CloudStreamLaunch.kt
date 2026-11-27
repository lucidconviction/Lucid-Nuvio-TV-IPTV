package com.nuvio.tv.ui.screens.cloudstream

/** A one-shot query handed to the CloudStream page (Sports, Search, etc.). */
object CloudStreamLaunch {
    data class Request(
        val query: String,
        /** Extensions the caller does not want searched (Sports Providers switches). */
        val disabledProviderIds: Set<String> = emptySet(),
    )

    @Volatile private var pending: Request? = null

    fun request(query: String, disabledProviderIds: Set<String> = emptySet()) {
        pending = query.trim().takeIf { it.isNotBlank() }?.let {
            Request(it, disabledProviderIds)
        }
    }

    fun take(): Request? {
        val request = pending
        pending = null
        return request
    }
}
