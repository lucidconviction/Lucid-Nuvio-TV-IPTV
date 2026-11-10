package com.nuvio.tv.youtube

import android.content.Context

/**
 * TV sign-in is confirmed on the phone or computer with the code on screen, so there is no browser
 * tab to return from. The account view model still reports the same start/stop points.
 */
internal object YouTubeSignInReturn {
    fun openActivatePage(context: Context, link: String, fallback: () -> Unit) {
        fallback()
    }

    fun backToApp(context: Context) = Unit

    fun cancelled() = Unit
}
