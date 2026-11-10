package com.nuvio.tv.ui.util

/** True while the Activity is the system picture-in-picture window (playback must keep going). */
object PipState {
    @Volatile var active: Boolean = false
}
