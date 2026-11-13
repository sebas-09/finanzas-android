package ec.finanzas.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F172A), onPrimary = Color.White,
    secondary = Color(0xFF1E3A8A), onSecondary = Color.White,
    tertiary = Color(0xFFA16207), onTertiary = Color.White,
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF020617),
    surface = Color(0xFFF8FAFC), onSurface = Color(0xFF020617),
    surfaceVariant = Color(0xFFE8ECF1), onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFB91C1C), onError = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE2E8F0), onPrimary = Color(0xFF0F172A),
    secondary = Color(0xFFBFDBFE), onSecondary = Color(0xFF172554),
    tertiary = Color(0xFFFDE68A), onTertiary = Color(0xFF422006),
    background = Color(0xFF0F172A), onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F172A), onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B), onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFCA5A5), onError = Color(0xFF450A0A),
)

@Composable
fun FinanzasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(), content = content)
}

