package com.nuvio.tv.ui.screens.cloudstream

/** A one-shot query handed to the CloudStream page (Sports, Search, etc.). */
object CloudStreamLaunch {
    @Volatile private var pendingQuery: String? = null

    fun request(query: String) {
        pendingQuery = query.trim().takeIf { it.isNotBlank() }
    }

    fun take(): String? {
        val query = pendingQuery
        pendingQuery = null
        return query
    }
}
