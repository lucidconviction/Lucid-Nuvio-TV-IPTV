package com.nuvio.tv.ui.screens.browser

/** A one-shot URL for the TV browser (Web-Apps home, market, bookmarks). */
object BrowserLaunch {
    @Volatile private var pending: String? = null

    fun request(url: String) {
        pending = url.trim().takeIf { it.isNotBlank() }
    }

    fun take(): String? {
        val value = pending
        pending = null
        return value
    }
}
