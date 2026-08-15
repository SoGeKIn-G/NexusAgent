package com.nexusagent.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A deep indigo / electric-cyan pairing. The agent's "active" states lean on the cyan
// accent so that a running agent is unmistakable at a glance — important when the
// overlay is floating over someone else's app.
private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFA5B4FC)
private val Cyan = Color(0xFF06B6D4)
private val CyanLight = Color(0xFF67E8F9)
private val Amber = Color(0xFFF59E0B)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = CyanLight,
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF155E75),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Amber,
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFE7E7EC),
    surface = Color(0xFF131319),
    onSurface = Color(0xFFE7E7EC),
    surfaceVariant = Color(0xFF2A2A33),
    onSurfaceVariant = Color(0xFFC7C7D1),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Cyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = Amber,
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF16161A),
    surface = Color.White,
    onSurface = Color(0xFF16161A),
    surfaceVariant = Color(0xFFEEEEF3),
    onSurfaceVariant = Color(0xFF46464F),
)

@Composable
fun NexusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You. Off by default so the brand palette stays consistent in demo videos. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NexusTypography,
        content = content,
    )
}
