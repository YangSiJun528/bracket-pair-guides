package com.sijunyang.bracketpairguides.visual

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ExactImageComparisonTest {
    @Test
    fun identicalImagesMatch() {
        val expected = image(2, 2, Color(10, 20, 30, 255).rgb)

        assertTrue(
            ExactImageComparison.matches(
                expected,
                image(2, 2, Color(10, 20, 30, 255).rgb),
            ),
        )
    }

    @Test
    fun differentImagesDoNotMatch() {
        val expected = image(1, 1, Color(10, 20, 30, 255).rgb)
        val actual = image(1, 1, Color(14, 18, 40, 255).rgb)

        assertFalse(ExactImageComparison.matches(expected, actual))
    }

    @Test
    fun differentDimensionsDoNotMatch() {
        assertFalse(ExactImageComparison.matches(image(1, 1, 0), image(2, 1, 0)))
    }

    private fun image(width: Int, height: Int, argb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setRGB(x, y, argb)
            }
        }
}
