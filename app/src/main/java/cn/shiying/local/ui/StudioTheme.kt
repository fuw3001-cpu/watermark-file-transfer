package cn.shiying.local.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val paper = lightColorScheme(
    primary = Color(0xFF215744), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE9DF), onPrimaryContainer = Color(0xFF163D30),
    secondary = Color(0xFF776036), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEE4CF), onSecondaryContainer = Color(0xFF483A21),
    tertiary = Color(0xFF9C5139), tertiaryContainer = Color(0xFFF9DFD4), onTertiaryContainer = Color(0xFF5D3024),
    background = Color(0xFFF7F6EF), onBackground = Color(0xFF1B2821),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF1B2821),
    surfaceVariant = Color(0xFFE9EAE1), onSurfaceVariant = Color(0xFF545F55),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F1E9),
    surfaceContainer = Color(0xFFECECE3), surfaceContainerHigh = Color(0xFFE7E8DF),
    surfaceContainerHighest = Color(0xFFE2E4DA), surfaceTint = Color(0xFF215744),
    outline = Color(0xFF747D71), outlineVariant = Color(0xFFD5D9CD),
)

private val darkroom = darkColorScheme(
    primary = Color(0xFFA2D5B9), onPrimary = Color(0xFF113D2D),
    primaryContainer = Color(0xFF254636), onPrimaryContainer = Color(0xFFCAEFDA),
    secondary = Color(0xFFE6C68D), onSecondary = Color(0xFF42331A),
    secondaryContainer = Color(0xFF453B29), onSecondaryContainer = Color(0xFFF4E2BF),
    tertiary = Color(0xFFF1B69F), tertiaryContainer = Color(0xFF51392F), onTertiaryContainer = Color(0xFFFFDCCD),
    background = Color(0xFF111913), onBackground = Color(0xFFE7EAE1),
    surface = Color(0xFF1A241D), onSurface = Color(0xFFE7EAE1),
    surfaceVariant = Color(0xFF2D382E), onSurfaceVariant = Color(0xFFBDC8BC),
    surfaceContainerLowest = Color(0xFF0E1510), surfaceContainerLow = Color(0xFF1C281F),
    surfaceContainer = Color(0xFF243027), surfaceContainerHigh = Color(0xFF2A362C),
    surfaceContainerHighest = Color(0xFF323F34), surfaceTint = Color(0xFFA2D5B9),
    outline = Color(0xFF8E9C8D), outlineVariant = Color(0xFF3E4C40),
)

private val baseType = Typography()
private val studioType = Typography(
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkroom else paper,
        typography = studioType, shapes = Shapes(small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)), content = content)
}
