package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.awt.image.BufferedImage
import kotlin.system.measureTimeMillis

class BracketGuideRendererTest : BasePlatformTestCase() {
    fun testFollowsEveryVisualFragmentOfASoftWrappedLogicalLine() {
        val source = "call(argumentOne, argumentTwo, argumentThree, argumentFour)"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        assertTrue(EditorTestUtil.configureSoftWraps(editor, 12))

        val openOffset = source.indexOf('(')
        val closeOffset = source.lastIndexOf(')')
        val openVisualLine = editor.offsetToVisualPosition(openOffset).line
        val closeVisualLine = editor.offsetToVisualPosition(closeOffset).line
        assertTrue(closeVisualLine - openVisualLine >= 2)

        val image = paint(
            BracketPair(
                openOffset = openOffset,
                openTokenLength = 1,
                closeOffset = closeOffset,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            ),
        )

        for (visualLine in openVisualLine..closeVisualLine) {
            val bottomY = editor.visualPositionToXY(VisualPosition(visualLine, 0)).y +
                editor.lineHeight - 1
            assertTrue(
                "Expected a horizontal guide on visual line $visualLine",
                image.hasInkNear(bottomY),
            )
        }
    }

    fun testSuppressesMultilinePairCollapsedOntoOneVisualLine() {
        val source = "{\n    call();\n}"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        val closeOffset = source.lastIndexOf('}')

        editor.foldingModel.runBatchFoldingOperation {
            val region = editor.foldingModel.addFoldRegion(
                editor.document.getLineEndOffset(0),
                editor.document.getLineStartOffset(2),
                "…",
            )
            requireNotNull(region).isExpanded = false
        }
        assertTrue(
            editor.offsetToVisualPosition(0).line ==
                editor.offsetToVisualPosition(closeOffset).line,
        )

        val image = paint(
            BracketPair(
                openOffset = 0,
                openTokenLength = 1,
                closeOffset = closeOffset,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 2,
            ),
        )

        assertFalse(image.hasAnyInk())
    }

    fun testCanDisableHorizontalGuidesForASingleLogicalLine() {
        val source = "call(argument)"
        myFixture.configureByText("Sample.java", source)
        val pair = BracketPair(
            openOffset = source.indexOf('('),
            openTokenLength = 1,
            closeOffset = source.lastIndexOf(')'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )

        val image = paint(
            pair,
            GuideRenderOptions(
                showVertical = true,
                showHorizontal = false,
                lineWidth = 4,
                opacityPercent = 100,
            ),
        )

        assertFalse(image.hasAnyInk())
    }

    fun testAppliesConfiguredGuideOpacity() {
        val source = "call(argument)"
        myFixture.configureByText("Sample.java", source)
        val image = paint(
            BracketPair(
                openOffset = source.indexOf('('),
                openTokenLength = 1,
                closeOffset = source.lastIndexOf(')'),
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            ),
            GuideRenderOptions(
                showVertical = true,
                showHorizontal = true,
                lineWidth = 1,
                opacityPercent = 50,
            ),
        )

        assertTrue(image.maximumAlpha() in 1..200)
    }

    fun testHugeSoftWrappedPairPaintIsBoundedByTheViewportClip() {
        val source = "call(" + "abcdefghij,".repeat(5_000) + "last)"
        myFixture.configureByText("Generated.java", source)
        val editor = myFixture.editor
        assertTrue(EditorTestUtil.configureSoftWraps(editor, 12))

        val openOffset = source.indexOf('(')
        val closeOffset = source.lastIndexOf(')')
        val closeVisualLine = editor.offsetToVisualPosition(closeOffset).line
        assertTrue(closeVisualLine > 2_000)
        val pair = BracketPair(
            openOffset = openOffset,
            openTokenLength = 1,
            closeOffset = closeOffset,
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val highlighter = createGuideHighlighter(pair)
        val targetY = editor.visualLineToY(closeVisualLine / 2)
        val viewportHeight = editor.lineHeight * 3
        val image = BufferedImage(1_000, viewportHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val elapsed = try {
            graphics.translate(0, -targetY)
            graphics.clipRect(0, targetY, image.width, viewportHeight)
            measureTimeMillis {
                BracketGuideRenderer.paint(editor, highlighter, graphics)
            }
        } finally {
            graphics.dispose()
            highlighter.dispose()
        }

        assertTrue("Clipped soft-wrap paint took ${elapsed}ms", elapsed < 2_000)
        assertTrue(image.hasAnyInk())
    }

    fun testStaleStoredLineNumbersCannotCrashPaintAfterDocumentShrink() {
        val source = "{\n    call();\n}"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = 1,
            closeOffset = source.lastIndexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        val highlighter = createGuideHighlighter(pair)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(1, pair.closeOffset)
        }
        assertTrue(editor.document.lineCount == 1)

        val image = BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            BracketGuideRenderer.paint(editor, highlighter, graphics)
        } finally {
            graphics.dispose()
            highlighter.dispose()
        }
    }

    private fun paint(
        pair: BracketPair,
        options: GuideRenderOptions = GuideRenderOptions.DEFAULT,
    ): BufferedImage {
        val highlighter = createGuideHighlighter(pair, options)

        return BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                BracketGuideRenderer.paint(myFixture.editor, highlighter, graphics)
            } finally {
                graphics.dispose()
                highlighter.dispose()
            }
        }
    }

    private fun createGuideHighlighter(
        pair: BracketPair,
        options: GuideRenderOptions = GuideRenderOptions.DEFAULT,
    ) =
        myFixture.editor.markupModel.addRangeHighlighter(
            null,
            pair.openOffset,
            pair.closeOffset + pair.closeTokenLength,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { highlighter ->
            highlighter.putUserData(
                GuideLineHighlightingPass.GUIDE_KEY,
                BracketGuide(pair, guideColumn = 0),
            )
            highlighter.putUserData(
                GuideLineHighlightingPass.GUIDE_RENDER_OPTIONS_KEY,
                options,
            )
        }

    private fun BufferedImage.hasInkNear(y: Int): Boolean {
        val firstY = (y - 1).coerceAtLeast(0)
        val lastY = (y + 1).coerceAtMost(height - 1)
        for (pixelY in firstY..lastY) {
            for (x in 0 until width) {
                if ((getRGB(x, pixelY) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun BufferedImage.hasAnyInk(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((getRGB(x, y) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun BufferedImage.maximumAlpha(): Int {
        var maximum = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                maximum = maxOf(maximum, getRGB(x, y) ushr 24)
            }
        }
        return maximum
    }
}
