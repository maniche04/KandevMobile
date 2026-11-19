package ca.mslab.forge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.mslab.forge.R

private val Hanken = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal),
    Font(R.font.hanken_grotesk, FontWeight.Medium),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk, FontWeight.Bold),
)
val ForgeCodeFont = FontFamily(Font(R.font.jetbrains_mono))
private val BaseTypography = Typography()
private val ForgeTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = Hanken),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = Hanken),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = Hanken),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = Hanken, fontSize = 16.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = Hanken, fontSize = 14.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = Hanken, fontSize = 13.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = Hanken, fontWeight = FontWeight.Medium),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFFA64B32), onPrimary = Color(0xFFFFFDFA),
    primaryContainer = Color(0xFFF3E0D4), onPrimaryContainer = Color(0xFF8C3D27),
    secondary = Color(0xFF6D635B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEE7DE), onSecondaryContainer = Color(0xFF4B423B),
    tertiary = Color(0xFF3F6E5A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEBE2), onTertiaryContainer = Color(0xFF214A39),
    background = Color(0xFFF7F5F0), onBackground = Color(0xFF302D29),
    surface = Color(0xFFFFFDFA), onSurface = Color(0xFF302D29),
    surfaceVariant = Color(0xFFEEEAE3), onSurfaceVariant = Color(0xFF6D635B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF9F5),
    surfaceContainer = Color(0xFFF2EFE9), surfaceContainerHigh = Color(0xFFECE8E1),
    surfaceContainerHighest = Color(0xFFE6E1D9),
    outline = Color(0xFFB9AE9F), outlineVariant = Color(0xFFE2DBD0),
    error = Color(0xFFC8394D), errorContainer = Color(0xFFFBE3E5), onErrorContainer = Color(0xFF8A1C2C),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE9A17E), onPrimary = Color(0xFF2E1C13),
    primaryContainer = Color(0xFF443127), onPrimaryContainer = Color(0xFFF1BDA0),
    secondary = Color(0xFFBFB2A1), onSecondary = Color(0xFF201D19),
    secondaryContainer = Color(0xFF332E27), onSecondaryContainer = Color(0xFFEEE8DE),
    tertiary = Color(0xFF8CC7A8), onTertiary = Color(0xFF0F2A1E),
    tertiaryContainer = Color(0xFF243A30), onTertiaryContainer = Color(0xFFBFE3CF),
    background = Color(0xFF1B1917), onBackground = Color(0xFFEEE8DE),
    surface = Color(0xFF25221E), onSurface = Color(0xFFEEE8DE),
    surfaceVariant = Color(0xFF302B25), onSurfaceVariant = Color(0xFFBFB2A1),
    surfaceContainerLowest = Color(0xFF151311), surfaceContainerLow = Color(0xFF211E1B),
    surfaceContainer = Color(0xFF29261F), surfaceContainerHigh = Color(0xFF312D27),
    surfaceContainerHighest = Color(0xFF3A352E),
    outline = Color(0xFF655849), outlineVariant = Color(0xFF3E372F),
    error = Color(0xFFFF8696), errorContainer = Color(0xFF4A2228), onErrorContainer = Color(0xFFFFC9D0),
)

/** Semantic status colors used by state pills, git stats and diffs. */
@Immutable
data class ForgeStatusColors(
    val success: Color, val successContainer: Color,
    val warning: Color, val warningContainer: Color,
    val info: Color, val infoContainer: Color,
    val neutral: Color, val neutralContainer: Color,
    val diffAdded: Color, val diffRemoved: Color, val diffHunk: Color,
)
private val LightStatus = ForgeStatusColors(
    success = Color(0xFF2F7A4F), successContainer = Color(0xFFDDF0E3),
    warning = Color(0xFF9A6200), warningContainer = Color(0xFFFBEBCB),
    info = Color(0xFF2F64A8), infoContainer = Color(0xFFDDE8F7),
    neutral = Color(0xFF6D635B), neutralContainer = Color(0xFFEEEAE3),
    diffAdded = Color(0x332F7A4F), diffRemoved = Color(0x33C8394D), diffHunk = Color(0x222F64A8),
)
private val DarkStatus = ForgeStatusColors(
    success = Color(0xFF7FD3A0), successContainer = Color(0xFF1F3A2A),
    warning = Color(0xFFF2C063), warningContainer = Color(0xFF3D3019),
    info = Color(0xFF8DB7F0), infoContainer = Color(0xFF1E2E44),
    neutral = Color(0xFFBFB2A1), neutralContainer = Color(0xFF302B25),
    diffAdded = Color(0x3346B373), diffRemoved = Color(0x33FF6B7D), diffHunk = Color(0x335C8FD6),
)
private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

object ForgeTheme {
    val status: ForgeStatusColors @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun ForgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ForgeTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
