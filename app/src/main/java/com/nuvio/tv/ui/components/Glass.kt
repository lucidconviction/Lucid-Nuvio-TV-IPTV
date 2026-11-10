package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The app's glass look: a faint see-through fill with a light edge, rounded. Used by the player,
 * the guide and the newer pages so panels sit over the artwork instead of blocking it.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color.White,
    strength: Float = 1f,
): Modifier {
    val base = NuvioTheme.colors.BackgroundElevated
    val top = base.copy(alpha = (0.68f * strength).coerceIn(0f, 1f))
    val bottom = base.copy(alpha = (0.84f * strength).coerceIn(0f, 1f))
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, tint.copy(alpha = 0.16f * strength), shape)
}
