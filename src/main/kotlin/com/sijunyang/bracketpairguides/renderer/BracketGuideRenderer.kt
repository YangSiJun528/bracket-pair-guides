package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.settings.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.paint.LinePainter2D
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

internal data class GuideRenderOptions(
    val showVertical: Boolean,
    val showHorizontal: Boolean,
    val lineWidth: Int,
    val opacityPercent: Int,
) {
    companion object {
        val DEFAULT = GuideRenderOptions(
            showVertical = true,
            showHorizontal = true,
            lineWidth = 1,
            opacityPercent = 100,
        )
    }
}

/**
 * Paints one complete bracket-pair guide for one range highlighter.
 *
 * Geometry is resolved at paint time so editor zoom, soft wraps, folding, and
 * font changes use current visual positions. Graphics state is isolated from
 * the rest of the editor paint pipeline.
 */
internal object BracketGuideRenderer : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val guide = highlighter.getUserData(GuideLineHighlightingPass.GUIDE_KEY) ?: return
        val options = highlighter.getUserData(
            GuideLineHighlightingPass.GUIDE_RENDER_OPTIONS_KEY,
        ) ?: GuideRenderOptions.DEFAULT
        if (!highlighter.isValid || editor.isDisposed) return
        if (!options.showVertical && !options.showHorizontal) return

        val document = editor.document
        val openOffset = highlighter.startOffset
        val closeOffset = highlighter.endOffset - guide.pair.closeTokenLength
        if (openOffset !in 0 until document.textLength) return
        if (closeOffset !in openOffset until document.textLength) return
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

        val openEndOffset = (openOffset + guide.pair.openTokenLength)
            .coerceAtMost(document.textLength)
        val openPosition = editor.offsetToVisualPosition(openOffset)
        val closePosition = editor.offsetToVisualPosition(closeOffset)
        val openPoint = editor.offsetToXY(openOffset)
        val closePoint = editor.offsetToXY(closeOffset)
        val lineHeight = editor.lineHeight
        val baseColor = highlighter.getUserData(GuideLineHighlightingPass.GUIDE_COLOR_KEY)
            ?: BracketColorPalette.guideLineColor(
                editor.colorsScheme,
                PluginSettings.getInstance().state,
                guide.pair.depth,
            )
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
            g.stroke = BasicStroke(
                options.lineWidth.coerceAtLeast(1).toFloat(),
                BasicStroke.CAP_SQUARE,
                BasicStroke.JOIN_MITER,
            )

            if (currentOpenLine == currentCloseLine) {
                if (!options.showHorizontal) return
                if (openPosition.line == closePosition.line) {
                    val contentStartX = editor.offsetToXY(openEndOffset).x
                    val y = openPoint.y + lineHeight - 1
                    drawHorizontal(g, contentStartX, closePoint.x, y)
                } else {
                    drawWrappedLogicalLine(
                        g = g,
                        editor = editor,
                        openEndOffset = openEndOffset,
                        closeOffset = closeOffset,
                        closeX = closePoint.x,
                        clip = g.clipBounds,
                    )
                }
                return
            }

            val firstVisualLine = editor.logicalToVisualPosition(
                LogicalPosition(currentOpenLine, 0),
            ).line
            val guideX = editor.visualPositionToXY(
                VisualPosition(firstVisualLine, guide.guideColumn),
            ).x
            val openBottomY = openPoint.y + lineHeight - 1
            val closeBottomY = closePoint.y + lineHeight - 1
            val verticalEndY = if (guideX == closePoint.x) closePoint.y else closeBottomY

            if (options.showVertical) {
                drawVerticalClipped(
                    g = g,
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
                drawHorizontal(g, guideX, openPoint.x, openBottomY)
                drawHorizontal(g, guideX, closePoint.x, closeBottomY)
            }
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
        g: Graphics2D,
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
            drawHorizontal(
                g,
                startX,
                endX,
                editor.visualLineToY(visualLine) + lineHeight - 1,
            )
        }
    }

    private fun drawVerticalClipped(
        g: Graphics2D,
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
                drawVertical(g, x, segmentStart, wrappedLineStartY - 1)
            }
            segmentStart = maxOf(segmentStart, wrappedLineStartY + editor.lineHeight)
        }

        if (segmentStart < visibleEnd) {
            drawVertical(g, x, segmentStart, visibleEnd)
        }
    }

    private fun drawVertical(g: Graphics2D, x: Int, startY: Int, endY: Int) {
        if (startY < endY) {
            LinePainter2D.paint(g, x.toDouble(), startY.toDouble(), x.toDouble(), endY.toDouble())
        }
    }

    private fun drawHorizontal(g: Graphics2D, firstX: Int, secondX: Int, y: Int) {
        if (firstX == secondX) return
        LinePainter2D.paint(
            g,
            minOf(firstX, secondX).toDouble(),
            y.toDouble(),
            maxOf(firstX, secondX).toDouble(),
            y.toDouble(),
        )
    }
}
