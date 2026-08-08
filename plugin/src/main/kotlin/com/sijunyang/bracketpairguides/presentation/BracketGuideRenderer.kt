package com.sijunyang.bracketpairguides.presentation

import com.sijunyang.bracketpairguides.analysis.hasWellFormedTokenRange
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.paint.PaintUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import org.jetbrains.annotations.ApiStatus

/**
 * Paints one complete bracket-pair guide for one range highlighter.
 *
 * Geometry is resolved at paint time so editor zoom, soft wraps, folding, and
 * font changes use current visual positions. Graphics state is isolated from
 * the rest of the editor paint pipeline.
 */
@ApiStatus.Internal
public object BracketGuideRenderer : CustomHighlighterRenderer {
    public override fun paint(
        editor: Editor,
        highlighter: RangeHighlighter,
        graphics: Graphics,
    ): Unit {
        val state = highlighter.guidePaintState() ?: return
        val guide = state.guide
        val options = state.options
        if (!highlighter.isValid || editor.isDisposed) return
        if (!options.showVertical && !options.showHorizontal) return

        val document = editor.document
        val openOffset = guide.pair.openOffset
        val closeOffset = guide.pair.closeOffset
        if (!guide.pair.hasWellFormedTokenRange(document.textLength)) return
        val openEnd = openOffset.toLong() + guide.pair.openTokenLength
        if (editor.foldingModel.isOffsetCollapsed(openOffset) ||
            editor.foldingModel.isOffsetCollapsed(closeOffset)
        ) {
            return
        }
        val currentOpenLine = document.getLineNumber(openOffset)
        val currentCloseLine = document.getLineNumber(closeOffset)
        if (currentOpenLine != currentCloseLine) {
            val headerRegion = editor.foldingModel.getCollapsedRegionAtOffset(
                document.getLineEndOffset(currentOpenLine),
            )
            val tailRegion = editor.foldingModel.getCollapsedRegionAtOffset(
                document.getLineStartOffset(currentCloseLine),
            )
            if (headerRegion != null && headerRegion === tailRegion) return
        }

        val openEndOffset = openEnd.toInt()
        val openPosition = editor.offsetToVisualPosition(openOffset)
        val closePosition = editor.offsetToVisualPosition(closeOffset)
        val openPoint = editor.offsetToXY(openOffset)
        val closePoint = editor.offsetToXY(closeOffset)
        val lineHeight = editor.lineHeight
        val baseColor = state.color
        val opacity = options.opacityPercent.coerceIn(0, 100)
        val color = Color(
            baseColor.red,
            baseColor.green,
            baseColor.blue,
            baseColor.alpha * opacity / 100,
        )
        if (currentOpenLine != currentCloseLine &&
            openPosition.line == closePosition.line
        ) {
            return
        }

        val g = graphics.create() as Graphics2D
        try {
            g.color = color
            val guideShape = GuideStrokeShape(
                graphics = g,
                lineWidth = options.lineWidth.coerceAtLeast(1),
            )

            if (currentOpenLine == currentCloseLine) {
                if (!options.showHorizontal) return
                if (openPosition.line == closePosition.line) {
                    val contentStartX = editor.offsetToXY(openEndOffset).x
                    val y = openPoint.y + lineHeight - 1
                    guideShape.addHorizontal(contentStartX, closePoint.x, y)
                } else {
                    drawWrappedLogicalLine(
                        guideShape = guideShape,
                        editor = editor,
                        openEndOffset = openEndOffset,
                        closeOffset = closeOffset,
                        closeX = closePoint.x,
                        clip = g.clipBounds,
                    )
                }
                guideShape.paint()
                return
            }

            val anchorLine = guide.anchorLine.coerceIn(currentOpenLine, currentCloseLine)
            val anchorVisualLine = editor.logicalToVisualPosition(
                LogicalPosition(anchorLine, 0),
            ).line
            val guideX = editor.visualPositionToXY(
                VisualPosition(anchorVisualLine, guide.guideColumn),
            ).x
            val openBottomY = openPoint.y + lineHeight - 1
            val closeBottomY = closePoint.y + lineHeight - 1
            val verticalEndY = if (guideX == closePoint.x) closePoint.y else closeBottomY

            if (options.showVertical) {
                drawVerticalClipped(
                    guideShape = guideShape,
                    editor = editor,
                    guideColumn = guide.guideColumn,
                    x = guideX,
                    startY = openBottomY,
                    endY = verticalEndY,
                    openOffset = openOffset,
                    closeOffset = closeOffset,
                    clip = g.clipBounds,
                )
            }
            if (options.showHorizontal) {
                guideShape.addHorizontal(guideX, openPoint.x, openBottomY)
                guideShape.addHorizontal(guideX, closePoint.x, closeBottomY)
            }
            guideShape.paint()
        } finally {
            g.dispose()
        }
    }

    /**
     * VS Code-style horizontal guides follow each visual fragment when a single
     * logical line is soft-wrapped. The public Editor mapping API resolves the
     * two possible positions at a wrap boundary.
     */
    private fun drawWrappedLogicalLine(
        guideShape: GuideStrokeShape,
        editor: Editor,
        openEndOffset: Int,
        closeOffset: Int,
        closeX: Int,
        clip: Rectangle?,
    ) {
        val lineHeight = editor.lineHeight
        val openPoint = editor.offsetToXY(openEndOffset, false, true)
        val pairFirstLine = editor.yToVisualLine(openPoint.y)
        val pairLastLine = editor.offsetToVisualPosition(closeOffset).line
        val firstLine = clip?.let {
            maxOf(pairFirstLine, editor.yToVisualLine(it.y))
        } ?: pairFirstLine
        val lastLine = clip?.let {
            val lastY = it.y + (it.height - 1).coerceAtLeast(0)
            minOf(pairLastLine, editor.yToVisualLine(lastY))
        } ?: pairLastLine
        if (firstLine > lastLine) return

        for (visualLine in firstLine..lastLine) {
            val startX = if (visualLine == pairFirstLine) {
                openPoint.x
            } else {
                val lineStartOffset = editor.visualPositionToOffset(
                    VisualPosition(visualLine, 0),
                )
                editor.offsetToXY(lineStartOffset, false, false).x
            }
            val endX = if (visualLine == pairLastLine) {
                closeX
            } else {
                val nextLineOffset = editor.visualPositionToOffset(
                    VisualPosition(visualLine + 1, 0),
                )
                editor.offsetToXY(nextLineOffset, false, true).x
            }
            guideShape.addHorizontal(
                startX,
                endX,
                editor.visualLineToY(visualLine) + lineHeight - 1,
            )
        }
    }

    private fun drawVerticalClipped(
        guideShape: GuideStrokeShape,
        editor: Editor,
        guideColumn: Int,
        x: Int,
        startY: Int,
        endY: Int,
        openOffset: Int,
        closeOffset: Int,
        clip: Rectangle?,
    ) {
        if (startY >= endY) return
        val visibleStart = clip?.let { maxOf(startY, it.y) } ?: startY
        val visibleEnd = clip?.let { minOf(endY, it.y + it.height) } ?: endY
        if (visibleStart >= visibleEnd) return

        var segmentStart = visibleStart
        val softWrapModel = editor.softWrapModel
        val scanStartOffset = clip?.let {
            maxOf(
                openOffset,
                editor.visualPositionToOffset(
                    VisualPosition(editor.yToVisualLine(visibleStart), 0),
                ),
            )
        } ?: openOffset
        val scanEndOffset = clip?.let {
            val lastVisibleLine = editor.yToVisualLine(visibleEnd - 1)
            minOf(
                closeOffset,
                maxOf(
                    scanStartOffset,
                    editor.visualPositionToOffset(
                        VisualPosition(lastVisibleLine + 1, 0),
                    ),
                ),
            )
        } ?: closeOffset
        for (softWrap in softWrapModel.getSoftWrapsForRange(scanStartOffset, scanEndOffset)) {
            if (!softWrapModel.isVisible(softWrap) ||
                softWrap.indentInColumns >= guideColumn
            ) {
                continue
            }

            val wrappedVisualLine = editor.offsetToVisualLine(softWrap.start, false)
            val wrappedLineStartY = editor.visualLineToY(wrappedVisualLine)
            if (wrappedLineStartY >= visibleEnd) break
            if (wrappedLineStartY + editor.lineHeight <= segmentStart) continue

            if (segmentStart < wrappedLineStartY) {
                guideShape.addVertical(x, segmentStart, wrappedLineStartY - 1)
            }
            segmentStart = maxOf(segmentStart, wrappedLineStartY + editor.lineHeight)
        }

        if (segmentStart < visibleEnd) {
            guideShape.addVertical(x, segmentStart, visibleEnd)
        }
    }

    /**
     * Builds every visible guide segment from a centered stroke and fills the
     * combined outline once. This preserves square-cap stroke geometry while
     * applying a translucent color only once where segments overlap.
     */
    private class GuideStrokeShape(
        private val graphics: Graphics2D,
        lineWidth: Int,
    ) {
        private val centerLines = Path2D.Double(Path2D.WIND_NON_ZERO)
        private val thickness = PaintUtil.alignToInt(lineWidth.toDouble(), graphics)
            .coerceAtLeast(PaintUtil.devPixel(graphics))
        private var isEmpty = true

        fun addVertical(x: Int, startY: Int, endY: Int) {
            if (startY >= endY) return

            val centerX = PaintUtil.alignToInt(x.toDouble(), graphics)
            val top = PaintUtil.alignToInt(startY.toDouble(), graphics)
            val bottom = PaintUtil.alignToInt(endY.toDouble(), graphics)
            centerLines.moveTo(centerX, top)
            centerLines.lineTo(centerX, bottom)
            isEmpty = false
        }

        fun addHorizontal(firstX: Int, secondX: Int, y: Int) {
            if (firstX == secondX) return

            val left = PaintUtil.alignToInt(minOf(firstX, secondX).toDouble(), graphics)
            val right = PaintUtil.alignToInt(maxOf(firstX, secondX).toDouble(), graphics)
            val centerY = PaintUtil.alignToInt(y.toDouble(), graphics)
            centerLines.moveTo(left, centerY)
            centerLines.lineTo(right, centerY)
            isEmpty = false
        }

        fun paint() {
            if (isEmpty) return
            val outline = BasicStroke(
                thickness.toFloat(),
                BasicStroke.CAP_SQUARE,
                BasicStroke.JOIN_MITER,
            ).createStrokedShape(centerLines)
            PaintUtil.paintWithAA(
                graphics,
                RenderingHints.VALUE_ANTIALIAS_DEFAULT,
            ) {
                graphics.fill(outline)
            }
        }
    }
}
