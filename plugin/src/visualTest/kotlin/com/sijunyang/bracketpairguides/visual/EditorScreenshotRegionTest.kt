package com.sijunyang.bracketpairguides.visual

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class EditorScreenshotRegionTest {
    @Test
    fun tabFocusEdgeDoesNotChangeTheContentCapture() {
        val inactive = editorScreenshot()
        val active = editorScreenshot()
        for (x in 0 until active.width) active.setRGB(x, 0, Color.BLUE.rgb)

        assertTrue(
            ExactImageComparison.matches(EditorScreenshotRegion.crop(inactive), EditorScreenshotRegion.crop(active)),
        )
    }

    @Test
    fun onePixelChangesAtBothContentEdgesStillFail() {
        val expected = EditorScreenshotRegion.crop(editorScreenshot())
        for ((x, y) in listOf(0 to 1, 219 to 239)) {
            val actual = editorScreenshot()
            actual.setRGB(x, y, Color.RED.rgb)

            assertFalse(ExactImageComparison.matches(expected, EditorScreenshotRegion.crop(actual)))
        }
    }

    @Test
    fun sourceMustIncludeTheWholeContentRectangle() {
        assertThrows(IllegalArgumentException::class.java) {
            EditorScreenshotRegion.crop(BufferedImage(220, 239, BufferedImage.TYPE_INT_ARGB))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorScreenshotRegion.crop(BufferedImage(219, 240, BufferedImage.TYPE_INT_ARGB))
        }
    }

    private fun editorScreenshot() = BufferedImage(220, 240, BufferedImage.TYPE_INT_ARGB)
}
