package com.nuvio.tv.core.nativelib

import android.util.Log
import com.nuvio.tv.data.local.InternalPlayerEngine

/**
 * A device whose bundled libmpv fails to load must play on ExoPlayer instead of crashing in
 * mpv's constructor (upstream report 2026-11-25, TuvoraTV 63fc7c40 / B287).
 */
internal object LibmpvAvailabilityPolicy {
    fun usable(wanted: Boolean, loadable: () -> Boolean): Boolean = wanted && loadable()

    fun engineFor(engine: InternalPlayerEngine): InternalPlayerEngine =
        if (engine == InternalPlayerEngine.MVP_PLAYER && !usable(wanted = true) { LibmpvNativeProbe.loadable }) {
            InternalPlayerEngine.EXOPLAYER
        } else {
            engine
        }
}

/** Loads the same libraries, in the same order, as the mpv constructor; once per process. */
internal object LibmpvNativeProbe {
    val loadable: Boolean by lazy {
        try {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("LibmpvNativeProbe", "libmpv native libraries failed to load; using ExoPlayer")
            false
        }
    }
}
