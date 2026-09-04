package com.sijunyang.bracketpairguides.visual

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ImageDiffTest {
    @Test
    fun identicalImagesHaveNoChangedPixels() {
        val expected = image(2, 2, Color(10, 20, 30, 255).rgb)
        val result = ImageDiff.compare(expected, image(2, 2, Color(10, 20, 30, 255).rgb))

        assertTrue(result.metrics.isIdentical)
        assertEquals(0L, result.metrics.changedPixels)
        assertEquals(0, result.metrics.maximumChannelDifference)
        assertEquals(0.0, result.metrics.meanAbsoluteChannelDifference)
    }

    @Test
    fun changedPixelsProduceExactMetricsAndVisibleDiff() {
        val expected = image(1, 1, Color(10, 20, 30, 255).rgb)
        val actual = image(1, 1, Color(14, 18, 40, 255).rgb)

        val result = ImageDiff.compare(expected, actual)

        assertFalse(result.metrics.isIdentical)
        assertEquals(1L, result.metrics.changedPixels)
        assertEquals(10, result.metrics.maximumChannelDifference)
        assertEquals(4.0, result.metrics.meanAbsoluteChannelDifference)
        assertEquals(Color.MAGENTA.rgb, result.difference.getRGB(0, 0))
    }

    @Test
    fun differentDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageDiff.compare(image(1, 1, 0), image(2, 1, 0))
        }
    }

    private fun image(width: Int, height: Int, argb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setRGB(x, y, argb)
            }
        }
}
