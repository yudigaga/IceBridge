package com.huanchengfly.icebridge.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixColor

/**
 * A single seed family for both toolkits and both brightness modes.
 *
 * All decorative colors keep the seed hue. Low saturation on large surfaces keeps
 * the tint visible without turning an entire screen into a saturated accent.
 * Neutral seeds remain achromatic in both modes. Error colors retain their red meaning.
 */
class ThemePalette(val seedArgb: Int, val isDark: Boolean) {
    val familyHue: Float = hueOf(seedArgb)
    private val isNeutral = saturationOf(seedArgb) == 0f
    private val seedSaturation = if (isNeutral) 0f else saturationOf(seedArgb).coerceIn(0.42f, 0.72f)

    val background = tone(0.22f, 0.945f, 0.055f)
    val mediumCard = tone(0.28f, 0.980f, 0.120f)
    val smallCard = tone(0.31f, 0.910f, 0.180f)
    val elevated = tone(0.28f, 0.965f, 0.150f)
    val dialog = tone(0.28f, 0.980f, 0.160f)
    private val highestContainer = tone(0.30f, 0.885f, 0.210f)
    private val contentSurfaces = listOf(background, mediumCard, smallCard, elevated, dialog, highestContainer)

    val onSurface = readableTone(0.15f, 0.115f, 0.940f, contentSurfaces)
    val onSurfaceVariant = readableTone(0.10f, 0.355f, 0.735f, contentSurfaces)
    val primary = readableTone(seedSaturation, 0.365f, 0.735f, contentSurfaces)
    val onPrimary = textOn(primary)
    val primaryContainer = tone(0.36f, 0.865f, 0.235f)
    val onPrimaryContainer = readableTone(0.34f, 0.235f, 0.900f, listOf(primaryContainer))
    val outline = tone(0.12f, 0.690f, 0.390f)
    val divider = tone(0.15f, 0.825f, 0.270f)

    val error = Color(if (isDark) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt())
    val onError = textOn(error)
    val errorContainer = Color(if (isDark) 0xFF601410.toInt() else 0xFFF9DEDC.toInt())
    val onErrorContainer = Color(if (isDark) 0xFFFFDAD6.toInt() else 0xFF410E0B.toInt())

    /** Miuix 0.2.6 Card defaults to surface; explicitly use smallCard for nested cards. */
    val miuix = MiuixColor(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primary,
        onPrimaryVariant = onPrimary,
        disabledPrimary = smallCard,
        disabledOnPrimary = onSurfaceVariant,
        disabledPrimaryButton = smallCard,
        disabledOnPrimaryButton = onSurfaceVariant,
        disabledPrimarySlider = outline,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = smallCard,
        onSecondary = onSurface,
        secondaryVariant = elevated,
        onSecondaryVariant = onSurface,
        disabledSecondary = smallCard,
        disabledOnSecondary = onSurfaceVariant,
        disabledSecondaryVariant = elevated,
        disabledOnSecondaryVariant = onSurfaceVariant,
        secondaryContainer = smallCard,
        onSecondaryContainer = onSurfaceVariant,
        secondaryContainerVariant = highestContainer,
        onSecondaryContainerVariant = onSurfaceVariant,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        tertiaryContainerVariant = primaryContainer,
        background = background,
        onBackground = onSurface,
        onBackgroundVariant = onSurfaceVariant,
        surface = mediumCard,
        onSurface = onSurface,
        surfaceVariant = dialog,
        onSurfaceVariantDialog = onSurface,
        onSurfaceVariantSummary = onSurfaceVariant,
        onSurfaceVariantActions = onSurfaceVariant,
        disabledOnSurface = onSurfaceVariant,
        outline = outline,
        dividerLine = divider,
        surfaceContainer = smallCard,
        onSurfaceContainer = onSurface,
        onSurfaceContainerVariant = onSurfaceVariant,
        surfaceContainerHigh = elevated,
        onSurfaceContainerHigh = onSurface,
        surfaceContainerHighest = highestContainer,
        onSurfaceContainerHighest = onSurface,
        windowDimming = Color.Black.copy(alpha = if (isDark) 0.60f else 0.30f)
    )

    /** Explicit bridge: Miuix 0.2.6 does not provide MaterialTheme colors itself. */
    val material: ColorScheme = materialScheme()

    private fun familyColor(saturation: Float, lightness: Float): Color =
        hslColor(familyHue, if (isNeutral) 0f else saturation, lightness)

    private fun tone(saturation: Float, light: Float, dark: Float): Color =
        familyColor(saturation, if (isDark) dark else light)

    private fun readableTone(
        saturation: Float,
        light: Float,
        dark: Float,
        surfaces: List<Color>
    ): Color {
        var lightness = if (isDark) dark else light
        var color = familyColor(saturation, lightness)
        // HSL lightness alone does not predict luminance (especially for yellow).
        // Check actual sRGB contrast and only adjust lightness, preserving the family.
        while (surfaces.any { contrastRatio(color, it) < 4.5 } && lightness in 0.005f..0.995f) {
            lightness = (lightness + if (isDark) 0.005f else -0.005f).coerceIn(0f, 1f)
            color = familyColor(saturation, lightness)
        }
        return color
    }

    private fun materialScheme(): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = familyColor(seedSaturation, if (isDark) 0.34f else 0.78f),
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = smallCard,
            onSecondaryContainer = onSurface,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onPrimaryContainer,
            background = background,
            onBackground = onSurface,
            surface = mediumCard,
            onSurface = onSurface,
            surfaceVariant = smallCard,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = familyColor(0.15f, if (isDark) 0.94f else 0.15f),
            inverseOnSurface = familyColor(0.12f, if (isDark) 0.12f else 0.94f),
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = divider,
            scrim = Color.Black,
            surfaceBright = elevated,
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = mediumCard,
            surfaceContainer = smallCard,
            surfaceContainerHigh = elevated,
            surfaceContainerHighest = highestContainer
        )
    }

    companion object {
        /** WCAG relative luminance contrast for opaque sRGB UI colors. */
        fun contrastRatio(first: Color, second: Color): Double {
            val firstLuminance = luminance(first.toArgb())
            val secondLuminance = luminance(second.toArgb())
            return (max(firstLuminance, secondLuminance) + 0.05) /
                (min(firstLuminance, secondLuminance) + 0.05)
        }

        private fun textOn(background: Color): Color =
            if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) {
                Color.White
            } else {
                Color.Black
            }

        private fun luminance(argb: Int): Double {
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(argb ushr 16 and 0xFF) +
                0.7152 * linear(argb ushr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)
        }

        private fun seedChannels(argb: Int) = floatArrayOf(
            (argb ushr 16 and 0xFF) / 255f,
            (argb ushr 8 and 0xFF) / 255f,
            (argb and 0xFF) / 255f
        )

        private fun hueOf(argb: Int): Float {
            val channels = seedChannels(argb)
            val red = channels[0]
            val green = channels[1]
            val blue = channels[2]
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val delta = maximum - minimum
            if (delta < 0.001f) return 210f
            val hue = when (maximum) {
                red -> (green - blue) / delta % 6f
                green -> (blue - red) / delta + 2f
                else -> (red - green) / delta + 4f
            } * 60f
            return (hue + 360f) % 360f
        }

        private fun saturationOf(argb: Int): Float {
            val channels = seedChannels(argb)
            val maximum = channels.maxOrNull() ?: 0f
            val minimum = channels.minOrNull() ?: 0f
            val delta = maximum - minimum
            if (delta < 0.001f) return 0f
            return delta / (1f - abs(maximum + minimum - 1f))
        }

        private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
            val chroma = (1f - abs(2f * lightness - 1f)) * saturation
            val segment = hue / 60f
            val intermediate = chroma * (1f - abs(segment % 2f - 1f))
            val (red, green, blue) = when {
                segment < 1f -> Triple(chroma, intermediate, 0f)
                segment < 2f -> Triple(intermediate, chroma, 0f)
                segment < 3f -> Triple(0f, chroma, intermediate)
                segment < 4f -> Triple(0f, intermediate, chroma)
                segment < 5f -> Triple(intermediate, 0f, chroma)
                else -> Triple(chroma, 0f, intermediate)
            }
            val match = lightness - chroma / 2f
            fun channel(value: Float) = ((value + match) * 255f).roundToInt().coerceIn(0, 255)
            return Color(0xFF000000.toInt() or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue))
        }
    }
}
