package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

class BracketGuideDrawingTest : BasePlatformTestCase() {
    fun testInlineHintBeforeOpeningBracketDoesNotShiftGuide() {
        val source = """
            fun update() {
                state.copy(
                    activePair = value,
                    )
            }
        """.trimIndent()
        myFixture.configureByText("Sample.kt", source)
        val editor = myFixture.editor
        val openOffset = source.indexOf("copy(") + "copy".length
        val closeOffset = source.indexOf(')', openOffset)
        val openLine = editor.document.getLineNumber(openOffset)
        val closeLine = editor.document.getLineNumber(closeOffset)
        val guideColumn = 8
        val firstVisualLine = editor.logicalToVisualPosition(
            LogicalPosition(openLine, 0),
        ).line
        val anchorLine = openLine + 1
        val anchorVisualLine = editor.logicalToVisualPosition(
            LogicalPosition(anchorLine, 0),
        ).line
        val expectedX = editor.visualPositionToXY(
            VisualPosition(anchorVisualLine, guideColumn),
        ).x
        val hintOffset = source.indexOf("state.copy")
        val inlay = requireNotNull(
            editor.inlayModel.addInlineElement(
                hintOffset,
                true,
                FixedWidthInlay(width = 72),
            ),
        )

        try {
            val shiftedX = editor.visualPositionToXY(
                VisualPosition(firstVisualLine, guideColumn),
            ).x
            assertThat(shiftedX).describedAs(
                "Test inlay must shift the raw visual position: " +
                    "expected=$expectedX, shifted=$shiftedX, bounds=${inlay.bounds}, " +
                    "visualPosition=${inlay.visualPosition}",
            ).isGreaterThan(expectedX)

            val image = paint(
                pair = BracketPair(
                    openOffset = openOffset,
                    openTokenLength = 1,
                    closeOffset = closeOffset,
                    closeTokenLength = 1,
                    depth = 0,
                    openLine = openLine,
                    closeLine = closeLine,
                ),
                guideColumn = guideColumn,
                anchorLine = anchorLine,
            )
            val bodyY = editor.visualLineToY(anchorVisualLine) + editor.lineHeight / 2

            assertThat(
                image.hasInkNear(expectedX, bodyY),
            ).describedAs(
                "Expected the guide at the text indentation before the inline hint: " +
                    "expected=$expectedX, shifted=$shiftedX",
            ).isTrue()
            assertThat(
                image.hasInkNear(shiftedX, bodyY),
            ).describedAs("Guide must not use the inlay-shifted coordinate").isFalse()
        } finally {
            inlay.dispose()
        }
    }

    fun testFollowsEveryVisualFragmentOfASoftWrappedLogicalLine() {
        val source = "call(argumentOne, argumentTwo, argumentThree, argumentFour)"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        assertThat(EditorTestUtil.configureSoftWraps(editor, 12)).isTrue()

        val openOffset = source.indexOf('(')
        val closeOffset = source.lastIndexOf(')')
        val openVisualLine = editor.offsetToVisualPosition(openOffset).line
        val closeVisualLine = editor.offsetToVisualPosition(closeOffset).line
        assertThat(closeVisualLine - openVisualLine).isGreaterThanOrEqualTo(2)

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
            assertThat(image.hasInkNear(bottomY))
                .describedAs("Expected a horizontal guide on visual line $visualLine")
                .isTrue()
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
        assertThat(editor.offsetToVisualPosition(closeOffset).line)
            .isEqualTo(editor.offsetToVisualPosition(0).line)

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

        assertThat(image.hasAnyInk()).isFalse()
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
            GuideAppearance(
                showVertical = true,
                showHorizontal = false,
                lineWidth = 4,
                opacityPercent = 100,
            ),
        )

        assertThat(image.hasAnyInk()).isFalse()
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
            GuideAppearance(
                showVertical = true,
                showHorizontal = true,
                lineWidth = 1,
                opacityPercent = 50,
            ),
        )

        assertThat(image.maximumAlpha()).isBetween(1, 200)
    }

    fun testUsesConfiguredGuideColor() {
        val source = "call(argument)"
        myFixture.configureByText("GuideColor.java", source)
        val color = Color(0x12, 0x34, 0x56)
        val image = paint(
            pair = BracketPair(
                openOffset = source.indexOf('('),
                openTokenLength = 1,
                closeOffset = source.lastIndexOf(')'),
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            ),
            options = GuideAppearance(
                showVertical = false,
                showHorizontal = true,
                lineWidth = 4,
                opacityPercent = 100,
            ),
            color = color,
        )

        assertThat(image.containsOpaque(color)).isTrue()
    }

    fun testAppliesOpacityOnlyOnceAtHorizontalVerticalJoints() {
        val source = "{\n    call();\n}"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        val closeOffset = source.lastIndexOf('}')
        val guideColumn = 4
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = 1,
            closeOffset = closeOffset,
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        val image = paint(
            pair = pair,
            options = GuideAppearance(
                showVertical = true,
                showHorizontal = true,
                lineWidth = 4,
                opacityPercent = 50,
            ),
            guideColumn = guideColumn,
            anchorLine = 1,
        )
        val anchorVisualLine = editor.logicalToVisualPosition(
            LogicalPosition(1, 0),
        ).line
        val guideX = editor.visualPositionToXY(
            VisualPosition(anchorVisualLine, guideColumn),
        ).x
        val openBottomY = editor.offsetToXY(pair.openOffset).y + editor.lineHeight - 1
        val closeBottomY = editor.offsetToXY(pair.closeOffset).y + editor.lineHeight - 1
        val bodyY = editor.visualLineToY(anchorVisualLine) + editor.lineHeight / 2
        val bodyAlpha = image.alphaAt(guideX, bodyY)

        assertThat(bodyAlpha)
            .describedAs("Expected a half-transparent guide, alpha=$bodyAlpha")
            .isBetween(100, 150)
        assertThat(image.alphaAt(guideX, openBottomY + 1)).isEqualTo(bodyAlpha)
        assertThat(image.alphaAt(guideX, closeBottomY)).isEqualTo(bodyAlpha)
        assertThat(image.maximumAlpha()).isEqualTo(bodyAlpha)
    }

    fun testThickGuideSegmentsRemainCenteredOnTheirAxes() {
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
        val guideColumn = 4
        val anchorVisualLine = editor.logicalToVisualPosition(
            LogicalPosition(1, 0),
        ).line
        val guideX = editor.visualPositionToXY(
            VisualPosition(anchorVisualLine, guideColumn),
        ).x
        val bodyY = editor.visualLineToY(anchorVisualLine) + editor.lineHeight / 2
        val openPoint = editor.offsetToXY(pair.openOffset)
        val openBottomY = openPoint.y + editor.lineHeight - 1
        val horizontalSampleX = (guideX + openPoint.x) / 2

        for (scale in listOf(1.0, 2.0)) {
            for (lineWidth in 2..4) {
                val image = paint(
                    pair = pair,
                    options = GuideAppearance(
                        showVertical = true,
                        showHorizontal = true,
                        lineWidth = lineWidth,
                        opacityPercent = 100,
                    ),
                    guideColumn = guideColumn,
                    anchorLine = 1,
                    graphicsScale = scale,
                )
                val deviceGuideX = guideX * scale
                val deviceBodyY = (bodyY * scale).roundToInt()
                val deviceHorizontalX = (horizontalSampleX * scale).roundToInt()
                val deviceOpenBottomY = openBottomY * scale
                val searchRadius = (lineWidth * scale).roundToInt() + 3

                assertThat(
                    image.alphaWeightedCenterX(
                        y = deviceBodyY,
                        centerX = deviceGuideX.roundToInt(),
                        radius = searchRadius,
                    ),
                ).describedAs(
                    "Vertical width $lineWidth at ${scale}x must stay centered",
                ).isCloseTo(
                    deviceGuideX,
                    within(CENTER_TOLERANCE_IN_DEVICE_PIXELS),
                )
                assertThat(
                    image.alphaWeightedCenterY(
                        x = deviceHorizontalX,
                        centerY = deviceOpenBottomY.roundToInt(),
                        radius = searchRadius,
                    ),
                ).describedAs(
                    "Horizontal width $lineWidth at ${scale}x must stay centered",
                ).isCloseTo(
                    deviceOpenBottomY,
                    within(CENTER_TOLERANCE_IN_DEVICE_PIXELS),
                )
            }
        }
    }

    fun testLeftmostVerticalGuideKeepsItsFullDeviceWidth() {
        val source = "{\n    call();\n}"
        myFixture.configureByText("LeftmostGuide.java", source)
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
        val anchorVisualLine = editor.logicalToVisualPosition(
            LogicalPosition(1, 0),
        ).line
        val logicalBodyY = editor.visualLineToY(anchorVisualLine) + editor.lineHeight / 2

        for (graphicsScale in listOf(1.0, 2.0)) {
            for (lineWidth in 1..4) {
                val options = GuideAppearance(
                    showVertical = true,
                    showHorizontal = false,
                    lineWidth = lineWidth,
                    opacityPercent = 100,
                )
                val leftmost = paint(
                    pair = pair,
                    options = options,
                    guideColumn = 0,
                    anchorLine = 1,
                    graphicsScale = graphicsScale,
                )
                val interior = paint(
                    pair = pair,
                    options = options,
                    guideColumn = 4,
                    anchorLine = 1,
                    graphicsScale = graphicsScale,
                )
                val deviceBodyY = (logicalBodyY * graphicsScale).roundToInt()

                assertThat(leftmost.inkPixelCountAt(deviceBodyY))
                    .describedAs(
                        "A left-edge width $lineWidth at ${graphicsScale}x must not lose " +
                            "half of its stroke",
                    )
                    .isEqualTo(interior.inkPixelCountAt(deviceBodyY))
            }
        }
    }

    fun testHugeSoftWrappedPairPaintIsBoundedByTheViewportClip() {
        val source = "call(" + "abcdefghij,".repeat(5_000) + "last)"
        myFixture.configureByText("Generated.java", source)
        val editor = myFixture.editor
        assertThat(EditorTestUtil.configureSoftWraps(editor, 12)).isTrue()

        val openOffset = source.indexOf('(')
        val closeOffset = source.lastIndexOf(')')
        val closeVisualLine = editor.offsetToVisualPosition(closeOffset).line
        assertThat(closeVisualLine).isGreaterThan(2_000)
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
                drawGuide(editor, highlighter, graphics)
            }
        } finally {
            graphics.dispose()
            highlighter.dispose()
        }

        assertThat(elapsed)
            .describedAs("Clipped soft-wrap paint took ${elapsed}ms")
            .isLessThan(2_000)
        assertThat(image.hasAnyInk()).isTrue()
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
        assertThat(editor.document.lineCount).isEqualTo(1)

        val image = BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            drawGuide(editor, highlighter, graphics)
        } finally {
            graphics.dispose()
            highlighter.dispose()
        }
    }

    fun testInvalidStoredTokenBoundsAreIgnored() {
        val source = "call(argument)"
        myFixture.configureByText("InvalidStoredPair.java", source)
        val validPair = BracketPair(
            openOffset = source.indexOf('('),
            openTokenLength = 1,
            closeOffset = source.lastIndexOf(')'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val invalidPairs = listOf(
            validPair.copy(openTokenLength = -10),
            validPair.copy(closeTokenLength = source.length),
            validPair.copy(closeOffset = validPair.openOffset),
        )

        for (pair in invalidPairs) {
            val image = paintStoredGuide(pair)
            assertThat(image.hasAnyInk()).describedAs("Invalid token bounds must not paint: $pair").isFalse()
        }
    }

    private fun paint(
        pair: BracketPair,
        options: GuideAppearance = DEFAULT_OPTIONS,
        guideColumn: Int = 0,
        anchorLine: Int = pair.openLine,
        graphicsScale: Double = 1.0,
        color: Color = Color.WHITE,
    ): BufferedImage {
        val highlighter = createGuideHighlighter(
            pair,
            options,
            guideColumn,
            anchorLine,
            color,
        )

        val imageSize = (1_000 * graphicsScale).roundToInt()
        return BufferedImage(
            imageSize,
            imageSize,
            BufferedImage.TYPE_INT_ARGB,
        ).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.scale(graphicsScale, graphicsScale)
                drawGuide(myFixture.editor, highlighter, graphics)
            } finally {
                graphics.dispose()
                highlighter.dispose()
            }
        }
    }

    private fun paintStoredGuide(pair: BracketPair): BufferedImage {
        val editor = myFixture.editor
        val highlighter = editor.markupModel.addRangeHighlighter(
            null,
            0,
            editor.document.textLength,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { highlighter ->
            highlighter.customRenderer = BracketGuideDrawing(
                BracketGuide(pair, 0),
                DEFAULT_OPTIONS,
                Color.WHITE,
            )
        }
        return BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                drawGuide(editor, highlighter, graphics)
            } finally {
                graphics.dispose()
                highlighter.dispose()
            }
        }
    }

    private fun createGuideHighlighter(
        pair: BracketPair,
        options: GuideAppearance = DEFAULT_OPTIONS,
        guideColumn: Int = 0,
        anchorLine: Int = pair.openLine,
        color: Color = Color.WHITE,
    ) =
        myFixture.editor.markupModel.addRangeHighlighter(
            null,
            pair.openOffset,
            pair.closeOffset + pair.closeTokenLength,
            HighlighterLayer.ADDITIONAL_SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { highlighter ->
            highlighter.customRenderer = BracketGuideDrawing(
                BracketGuide(pair, guideColumn, anchorLine),
                options,
                color,
            )
        }

    private fun drawGuide(
        editor: com.intellij.openapi.editor.Editor,
        highlighter: com.intellij.openapi.editor.markup.RangeHighlighter,
        graphics: java.awt.Graphics,
    ) {
        val drawing = highlighter.customRenderer as BracketGuideDrawing
        drawing.paint(editor, highlighter, graphics)
    }

    companion object {
        private const val CENTER_TOLERANCE_IN_DEVICE_PIXELS = 0.55
        private val DEFAULT_OPTIONS = GuideAppearance(
            showVertical = true,
            showHorizontal = true,
            lineWidth = 1,
            opacityPercent = 100,
        )
    }

    private class FixedWidthInlay(
        private val width: Int,
    ) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = width
    }

    private fun BufferedImage.hasInkNear(x: Int, y: Int): Boolean {
        val firstX = (x - 1).coerceAtLeast(0)
        val lastX = (x + 1).coerceAtMost(width - 1)
        val firstY = (y - 1).coerceAtLeast(0)
        val lastY = (y + 1).coerceAtMost(height - 1)
        for (pixelY in firstY..lastY) {
            for (pixelX in firstX..lastX) {
                if ((getRGB(pixelX, pixelY) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun BufferedImage.containsOpaque(color: Color): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y) == color.rgb) return true
            }
        }
        return false
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

    private fun BufferedImage.inkPixelCountAt(y: Int): Int =
        (0 until width).count { x -> alphaAt(x, y) != 0 }

    private fun BufferedImage.alphaAt(x: Int, y: Int): Int = getRGB(x, y) ushr 24

    private fun BufferedImage.alphaWeightedCenterX(
        y: Int,
        centerX: Int,
        radius: Int,
    ): Double {
        var weightedPosition = 0.0
        var totalAlpha = 0L
        for (x in (centerX - radius).coerceAtLeast(0)..
            (centerX + radius).coerceAtMost(width - 1)
        ) {
            val alpha = alphaAt(x, y)
            weightedPosition += (x + 0.5) * alpha
            totalAlpha += alpha
        }
        assertThat(totalAlpha)
            .describedAs("Expected vertical guide ink near x=$centerX")
            .isGreaterThan(0)
        return weightedPosition / totalAlpha
    }

    private fun BufferedImage.alphaWeightedCenterY(
        x: Int,
        centerY: Int,
        radius: Int,
    ): Double {
        var weightedPosition = 0.0
        var totalAlpha = 0L
        for (y in (centerY - radius).coerceAtLeast(0)..
            (centerY + radius).coerceAtMost(height - 1)
        ) {
            val alpha = alphaAt(x, y)
            weightedPosition += (y + 0.5) * alpha
            totalAlpha += alpha
        }
        assertThat(totalAlpha)
            .describedAs("Expected horizontal guide ink near y=$centerY")
            .isGreaterThan(0)
        return weightedPosition / totalAlpha
    }
}
