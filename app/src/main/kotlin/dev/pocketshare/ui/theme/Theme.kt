package dev.pocketshare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketshare.R

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF0E6F5C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD2F4E9),
        onPrimaryContainer = Color(0xFF06382E),
        inversePrimary = Color(0xFF79D7BE),
        secondary = Color(0xFF4E635B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFD7E8E0),
        onSecondaryContainer = Color(0xFF0B1F19),
        tertiary = Color(0xFF8A5A00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE7B8),
        onTertiaryContainer = Color(0xFF2B1A00),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF6F9F7),
        onBackground = Color(0xFF101714),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF101714),
        surfaceVariant = Color(0xFFE2EBE6),
        onSurfaceVariant = Color(0xFF3F4A45),
        outline = Color(0xFF6F7B75),
        outlineVariant = Color(0xFFC6D1CB),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F6F3),
        surfaceContainer = Color(0xFFEBF2EE),
        surfaceContainerHigh = Color(0xFFE5EDE8),
        surfaceContainerHighest = Color(0xFFDFE8E3),
        inverseSurface = Color(0xFF2B322E),
        inverseOnSurface = Color(0xFFEDF2EF),
        scrim = Color(0xFF000000),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF6FDBC1),
        onPrimary = Color(0xFF00382C),
        primaryContainer = Color(0xFF0B5243),
        onPrimaryContainer = Color(0xFFA5F2DE),
        inversePrimary = Color(0xFF0E6F5C),
        secondary = Color(0xFFB3CCC1),
        onSecondary = Color(0xFF1E332B),
        secondaryContainer = Color(0xFF334A41),
        onSecondaryContainer = Color(0xFFCFE9DD),
        tertiary = Color(0xFFF3C36A),
        onTertiary = Color(0xFF472A00),
        tertiaryContainer = Color(0xFF654100),
        onTertiaryContainer = Color(0xFFFFE0A3),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0A0F0D),
        onBackground = Color(0xFFE2E9E5),
        surface = Color(0xFF0F1613),
        onSurface = Color(0xFFE2E9E5),
        surfaceVariant = Color(0xFF26332E),
        onSurfaceVariant = Color(0xFFB9C9C2),
        outline = Color(0xFF84948D),
        outlineVariant = Color(0xFF3E4C46),
        surfaceContainerLowest = Color(0xFF060A08),
        surfaceContainerLow = Color(0xFF121A16),
        surfaceContainer = Color(0xFF16201B),
        surfaceContainerHigh = Color(0xFF1C2822),
        surfaceContainerHighest = Color(0xFF233029),
        inverseSurface = Color(0xFFE2E9E5),
        inverseOnSurface = Color(0xFF27322D),
        scrim = Color(0xFF000000),
    )

@Immutable
data class BrandColors(
    val heroGradient: List<Color>,
    val heroContent: Color,
    val heroContentMuted: Color,
    val heroGlass: Color,
    val heroGlassBorder: Color,
    val accentGold: Color,
    val success: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val glowA: Color,
    val glowB: Color,
)

private val LightBrand =
    BrandColors(
        heroGradient =
            listOf(
                Color(0xFF0C6B58),
                Color(0xFF084C3F),
                Color(0xFF06352D),
            ),
        heroContent = Color(0xFFF4FFFB),
        heroContentMuted = Color(0xFFC7E8DD),
        heroGlass = Color(0x1FFFFFFF),
        heroGlassBorder = Color(0x33FFFFFF),
        accentGold = Color(0xFFF3C36A),
        success = Color(0xFF19A974),
        warning = Color(0xFFB26A00),
        warningContainer = Color(0xFFFFF0D5),
        onWarningContainer = Color(0xFF3E2600),
        glowA = Color(0x336FE0C4),
        glowB = Color(0x33F3C36A),
    )

private val DarkBrand =
    BrandColors(
        heroGradient =
            listOf(
                Color(0xFF11453A),
                Color(0xFF0B2E27),
                Color(0xFF071D18),
            ),
        heroContent = Color(0xFFEFFFFA),
        heroContentMuted = Color(0xFFA9D6C8),
        heroGlass = Color(0x14FFFFFF),
        heroGlassBorder = Color(0x24FFFFFF),
        accentGold = Color(0xFFF3C36A),
        success = Color(0xFF4ADE9B),
        warning = Color(0xFFF3C36A),
        warningContainer = Color(0xFF3B2A08),
        onWarningContainer = Color(0xFFFFE0A3),
        glowA = Color(0x2E5EE0C0),
        glowB = Color(0x26F3C36A),
    )

val LocalBrandColors = staticCompositionLocalOf { LightBrand }

@OptIn(ExperimentalTextApi::class)
private val Manrope =
    FontFamily(
        Font(
            resId = R.font.manrope,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            resId = R.font.manrope,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            resId = R.font.manrope,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            resId = R.font.manrope,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

private val PocketTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp,
                lineHeight = 50.sp,
                letterSpacing = (-1.2).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 38.sp,
                lineHeight = 44.sp,
                letterSpacing = (-1.0).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.8).sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.6).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.4).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.3.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.8.sp,
            ),
    )

private val PocketShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )

@Composable
fun PocketShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PocketTypography,
            shapes = PocketShapes,
            content = content,
        )
    }
}
