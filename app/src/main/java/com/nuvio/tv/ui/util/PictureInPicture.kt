package com.nuvio.tv.ui.util

import androidx.compose.runtime.compositionLocalOf

/**
 * Android picture-in-picture for the current activity. [enter] returns false when the device or
 * system doesn't allow it (TVs below Android 8, or PiP switched off).
 */
class PictureInPictureController(
    val enter: () -> Boolean = { false },
    val isInPip: Boolean = false,
)

val LocalPictureInPicture = compositionLocalOf { PictureInPictureController() }
