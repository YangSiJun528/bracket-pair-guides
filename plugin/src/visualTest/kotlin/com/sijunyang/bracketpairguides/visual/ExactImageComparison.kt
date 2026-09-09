package com.sijunyang.bracketpairguides.visual

import java.awt.image.BufferedImage

/** Exact, threshold-free comparison used as the committed-baseline oracle. */
internal object ExactImageComparison {
    fun matches(expected: BufferedImage, actual: BufferedImage): Boolean {
        if (expected.width != actual.width || expected.height != actual.height) return false
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) return false
            }
        }
        return true
    }
}
