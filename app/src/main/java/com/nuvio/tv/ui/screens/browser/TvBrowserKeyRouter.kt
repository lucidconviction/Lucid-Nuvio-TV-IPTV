package com.nuvio.tv.ui.screens.browser

import android.view.KeyEvent

/**
 * Activity-level key bridge for the TV browser.
 *
 * Chromium consumes D-pad keys before a Compose key listener on some TV boxes, which made
 * the on-screen pointer move only a few pixels while the page also moved its own focus.
 * MainActivity calls [dispatch] before the WebView, and the browser installs [handler]
 * while it is the visible screen.
 */
object TvBrowserKeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
