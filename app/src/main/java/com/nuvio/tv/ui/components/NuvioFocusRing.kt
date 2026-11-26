package com.nuvio.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A consistent accent ring for D-pad focus. TV Material only tints focused surfaces; this keeps
 * the focused object outlined so the eye can follow focus on dark artwork and web pages.
 */
fun Modifier.nuvioFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    widthDp: Int = 2,
    color: Color? = null,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ringColor = color ?: NuvioTheme.colors.FocusRing
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) widthDp.dp else 0.dp,
            color = if (focused) ringColor else Color.Transparent,
            shape = shape,
        )
}
