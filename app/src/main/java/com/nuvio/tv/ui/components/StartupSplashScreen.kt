package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

private const val SPLASH_TITLE = "Lucid Conviction Build"

/**
 * Opening splash: plain black with the build title and the loading animation underneath.
 */
@Composable
fun StartupSplashScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = SPLASH_TITLE,
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(36.dp))
            LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.xxxl))
        }
    }
}
