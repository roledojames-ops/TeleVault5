package app.televault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC0FF),
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF123B5C),
    onPrimaryContainer = Color(0xFFCDE9FF),
    secondary = Color(0xFF9C8CFF),
    onSecondary = Color(0xFF1E1250),
    secondaryContainer = Color(0xFF30266F),
    onSecondaryContainer = Color(0xFFE4DEFF),
    tertiary = Color(0xFF3DDBB0),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0F182B),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2640),
    onSurfaceVariant = Color(0xFFA9B7D0),
    outline = Color(0xFF4A5A7A),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6FE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E8FF),
    onPrimaryContainer = Color(0xFF00305F),
    secondary = Color(0xFF5B4BD6),
    secondaryContainer = Color(0xFFE5E0FF),
    onSecondaryContainer = Color(0xFF1B0F66),
    tertiary = Color(0xFF00896A),
    background = Color(0xFFF5F8FD),
    onBackground = Color(0xFF111A2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111A2B),
    surfaceVariant = Color(0xFFE6ECF7),
    onSurfaceVariant = Color(0xFF47546E),
    outline = Color(0xFF8795B0),
    error = Color(0xFFD93636),
)

@Composable
fun TeleVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
