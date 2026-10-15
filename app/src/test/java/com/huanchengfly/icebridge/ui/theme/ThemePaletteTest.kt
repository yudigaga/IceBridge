package com.huanchengfly.icebridge.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    private val seeds = listOf(
        0xFF3482FF, 0xFF8E55B5, 0xFF2D7D65, 0xFFC77336,
        0xFFC55377, 0xFF537EA2, 0xFFFF0000, 0xFF00FF00,
        0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
        0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF010101, 0xFFFEFFFF
    ).map { it.toInt() }

    @Test fun bodySummaryAndActionTextMeetContrastOnEveryCardLayer() {
        for (seed in seeds) for (isDark in listOf(false, true)) {
            val palette = ThemePalette(seed, isDark)
            val surfaces = listOf(
                palette.background, palette.mediumCard, palette.smallCard,
                palette.elevated, palette.dialog, palette.material.surfaceContainerHighest
            )
            for (surface in surfaces) {
                assertReadable(palette.onSurface, surface)
                assertReadable(palette.onSurfaceVariant, surface)
                assertReadable(palette.primary, surface)
            }
            assertReadable(palette.onPrimary, palette.primary)
            assertReadable(palette.onPrimaryContainer, palette.primaryContainer)
            assertReadable(palette.onError, palette.error)
            assertReadable(palette.onErrorContainer, palette.errorContainer)
        }
    }

    @Test fun coloredSeedsTintThePageAndBothCardSizesInBothModes() {
        for (seed in seeds) for (isDark in listOf(false, true)) {
            val palette = ThemePalette(seed, isDark)
            assertNotEquals(palette.background, palette.mediumCard)
            assertNotEquals(palette.background, palette.smallCard)
            assertNotEquals(palette.mediumCard, palette.smallCard)
            for (surface in listOf(palette.background, palette.mediumCard, palette.smallCard)) {
                val rgb = channels(surface)
                if (isNeutralSeed(seed)) {
                    assertEquals(rgb.maxOrNull()!!, rgb.minOrNull()!!, 0f)
                } else {
                    assertTrue("Surface must carry the selected family's tint", rgb.maxOrNull()!! > rgb.minOrNull()!!)
                }
            }
            if (isDark) {
                assertTrue(lightness(palette.background) < lightness(palette.mediumCard))
                assertTrue(lightness(palette.mediumCard) < lightness(palette.smallCard))
            } else {
                assertTrue(lightness(palette.mediumCard) > lightness(palette.background))
                assertTrue(lightness(palette.background) > lightness(palette.smallCard))
            }
        }
    }

    @Test fun dayAndNightKeepTheSameSeedFamily() {
        for (seed in seeds) {
            val day = ThemePalette(seed, false)
            val night = ThemePalette(seed, true)
            assertEquals(day.familyHue, night.familyHue, 0f)
            if (!isNeutralSeed(seed)) {
                assertTrue(hueDistance(hue(day.primary), day.familyHue) < 2f)
                assertTrue(hueDistance(hue(night.primary), day.familyHue) < 2f)
                assertTrue(hueDistance(hue(day.primary), hue(night.primary)) < 2f)
                // Bright surfaces quantize into a narrow RGB range, so allow 8 degrees.
                assertTrue(hueDistance(hue(day.background), day.familyHue) < 8f)
                assertTrue(hueDistance(hue(night.background), night.familyHue) < 8f)
            }
            assertTrue(lightness(day.background) > lightness(night.background))
            assertTrue(lightness(day.primary) < lightness(night.primary))
        }
    }

    @Test fun blackWhiteAndGraySeedsStayAchromaticAcrossModesAndBothToolkits() {
        for (seed in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())) {
            for (isDark in listOf(false, true)) {
                val palette = ThemePalette(seed, isDark)
                for (color in listOf(
                    palette.primary, palette.onPrimary, palette.primaryContainer, palette.onPrimaryContainer,
                    palette.background, palette.mediumCard, palette.smallCard, palette.elevated, palette.dialog,
                    palette.onSurface, palette.onSurfaceVariant, palette.outline, palette.divider,
                    palette.material.inversePrimary, palette.material.inverseSurface, palette.material.inverseOnSurface,
                    palette.miuix.primaryVariant, palette.miuix.surfaceContainerHighest
                )) {
                    val rgb = channels(color)
                    assertEquals("Neutral theme must not acquire a hue", rgb[0], rgb[1], 0f)
                    assertEquals("Neutral theme must not acquire a hue", rgb[1], rgb[2], 0f)
                }
            }
        }
    }

    @Test fun miuixAndMaterialUseTheSameColorsInsteadOfTheirIndependentDefaults() {
        for (isDark in listOf(false, true)) {
            val palette = ThemePalette(0xFF8E55B5.toInt(), isDark)
            assertEquals(palette.primary, palette.miuix.primary)
            assertEquals(palette.primary, palette.material.primary)
            assertEquals(palette.background, palette.miuix.background)
            assertEquals(palette.background, palette.material.background)
            assertEquals(palette.mediumCard, palette.miuix.surface)
            assertEquals(palette.mediumCard, palette.material.surface)
            assertEquals(palette.smallCard, palette.miuix.surfaceContainer)
            assertEquals(palette.smallCard, palette.material.surfaceContainer)
            assertEquals(palette.onSurfaceVariant, palette.miuix.onSurfaceVariantSummary)
            assertEquals(palette.onSurfaceVariant, palette.material.onSurfaceVariant)
        }
    }

    @Test fun seedAlphaDoesNotMakeThemeSurfacesTransparent() {
        val opaque = ThemePalette(0xFF3482FF.toInt(), false)
        val transparentSeed = ThemePalette(0x003482FF, false)
        assertEquals(opaque.primary, transparentSeed.primary)
        for (surface in listOf(transparentSeed.background, transparentSeed.mediumCard, transparentSeed.smallCard)) {
            assertEquals(255, surface.toArgb() ushr 24)
        }
    }

    @Test fun contrastCalculationMatchesBlackAndWhiteReference() {
        assertEquals(21.0, ThemePalette.contrastRatio(Color.Black, Color.White), 0.00001)
        assertEquals(1.0, ThemePalette.contrastRatio(Color.Black, Color.Black), 0.00001)
    }

    @Test fun errorMeaningStaysRedWhenSelectingAnotherFamily() {
        for (isDark in listOf(false, true)) {
            val blue = ThemePalette(0xFF3482FF.toInt(), isDark)
            val green = ThemePalette(0xFF2D7D65.toInt(), isDark)
            assertEquals(blue.error, green.error)
            assertEquals(blue.errorContainer, green.errorContainer)
            assertTrue(hueDistance(hue(blue.error), 0f) < 15f)
        }
    }

    private fun assertReadable(text: Color, surface: Color) {
        val ratio = ThemePalette.contrastRatio(text, surface)
        assertTrue("Text ${text.toArgb().toUInt().toString(16)} on ${surface.toArgb().toUInt().toString(16)} is only $ratio:1", ratio >= 4.5)
    }

    private fun channels(color: Color): List<Float> {
        val argb = color.toArgb()
        return listOf((argb ushr 16 and 255) / 255f, (argb ushr 8 and 255) / 255f, (argb and 255) / 255f)
    }

    private fun isNeutralSeed(seed: Int): Boolean =
        (seed ushr 16 and 255) == (seed ushr 8 and 255) && (seed ushr 8 and 255) == (seed and 255)

    private fun lightness(color: Color): Float {
        val rgb = channels(color)
        return (rgb.maxOrNull()!! + rgb.minOrNull()!!) / 2f
    }

    private fun hue(color: Color): Float {
        val rgb = channels(color)
        val red = rgb[0]
        val green = rgb[1]
        val blue = rgb[2]
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum
        if (delta == 0f) return 0f
        val value = when (maximum) {
            red -> (green - blue) / delta % 6f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        } * 60f
        return (value + 360f) % 360f
    }

    private fun hueDistance(first: Float, second: Float): Float {
        val difference = abs(first - second)
        return min(difference, 360f - difference)
    }
}
