package com.sijunyang.bracketpairguides.visual

import java.awt.Color
import java.awt.image.BufferedImage

internal data class ImageDiffMetrics(
    val width: Int,
    val height: Int,
    val changedPixels: Long,
    val maximumChannelDifference: Int,
    val meanAbsoluteChannelDifference: Double,
) {
    val totalPixels: Long
        get() = width.toLong() * height

    val isIdentical: Boolean
        get() = changedPixels == 0L

    fun toJson(environment: String? = null): String =
        """
        {
          "environment": ${environment?.let { "\"$it\"" } ?: "null"},
          "width": $width,
          "height": $height,
          "changedPixels": $changedPixels,
          "totalPixels": $totalPixels,
          "maximumChannelDifference": $maximumChannelDifference,
          "meanAbsoluteChannelDifference": $meanAbsoluteChannelDifference,
          "identical": $isIdentical
        }
        """.trimIndent() + "\n"
}

internal data class ImageDiffResult(val metrics: ImageDiffMetrics, val difference: BufferedImage)

internal object ImageDiff {
    fun compare(expected: BufferedImage, actual: BufferedImage): ImageDiffResult {
        require(expected.width == actual.width && expected.height == actual.height) {
            "Image dimensions differ: expected ${expected.width}x${expected.height}, " +
                "actual ${actual.width}x${actual.height}"
        }

        val difference = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        var changedPixels = 0L
        var maximumDifference = 0
        var absoluteDifference = 0L
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val expectedArgb = expected.getRGB(x, y)
                val actualArgb = actual.getRGB(x, y)
                val channels =
                    intArrayOf(
                        expectedArgb ushr 24 and 0xff,
                        expectedArgb ushr 16 and 0xff,
                        expectedArgb ushr 8 and 0xff,
                        expectedArgb and 0xff,
                        actualArgb ushr 24 and 0xff,
                        actualArgb ushr 16 and 0xff,
                        actualArgb ushr 8 and 0xff,
                        actualArgb and 0xff,
                    )
                var pixelChanged = false
                for (channel in 0..3) {
                    val delta = kotlin.math.abs(channels[channel] - channels[channel + 4])
                    absoluteDifference += delta
                    maximumDifference = maxOf(maximumDifference, delta)
                    pixelChanged = pixelChanged || delta != 0
                }
                if (pixelChanged) changedPixels++
                difference.setRGB(
                    x,
                    y,
                    if (pixelChanged) Color.MAGENTA.rgb else dimmed(actualArgb),
                )
            }
        }
        return ImageDiffResult(
            ImageDiffMetrics(
                width = actual.width,
                height = actual.height,
                changedPixels = changedPixels,
                maximumChannelDifference = maximumDifference,
                meanAbsoluteChannelDifference =
                absoluteDifference.toDouble() / (actual.width.toLong() * actual.height * 4),
            ),
            difference,
        )
    }

    private fun dimmed(argb: Int): Int {
        val alpha = argb ushr 24 and 0xff
        val red = (argb ushr 16 and 0xff) / 4
        val green = (argb ushr 8 and 0xff) / 4
        val blue = (argb and 0xff) / 4
        return alpha shl 24 or (red shl 16) or (green shl 8) or blue
    }
}
