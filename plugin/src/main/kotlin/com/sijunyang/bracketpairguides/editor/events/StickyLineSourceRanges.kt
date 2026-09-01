package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.TextRange
import java.awt.Point

/** Derives the source lines painted by IntelliJ's currently visible Sticky Lines. */
internal object StickyLineSourceRanges {
    fun calculate(editor: Editor): List<TextRange> {
        if (editor.isDisposed || !editor.settings.areStickyLinesShown()) return emptyList()
        val visibleArea = editor.scrollingModel.visibleArea
        val lineLimit = editor.settings.stickyLinesLimit.coerceAtLeast(0)
        val lineHeight = editor.lineHeight.coerceAtLeast(1)
        if (visibleArea.isEmpty || visibleArea.y < MINIMUM_STICKY_SCROLL_Y || lineLimit == 0) {
            return emptyList()
        }

        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return emptyList()
        val usesLegacyCandidateCollection =
            ApplicationInfo.getInstance().build.baselineVersion <= LEGACY_BASELINE_VERSION
        val startLine = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line
        if (startLine !in 0 until lineCount) return emptyList()
        val maximumPanelHeight =
            (lineHeight.toLong() * lineLimit + STICKY_BORDER_HEIGHT)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val queryEndY =
            (visibleArea.y.toLong() + maximumPanelHeight)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val reportedEndLine = editor.xyToLogicalPosition(Point(0, queryEndY)).line
        if (usesLegacyCandidateCollection && reportedEndLine !in 0 until lineCount) {
            return emptyList()
        }
        val endLine = reportedEndLine.coerceIn(startLine, lineCount - 1)
        val queryRange =
            TextRange(
                document.getLineStartOffset(startLine),
                document.getLineEndOffset(endLine),
            )
        val markup = documentMarkupModel(editor) ?: return emptyList()
        val scopes = ArrayList<TextRange>()
        val candidateLimit =
            if (usesLegacyCandidateCollection) {
                maxOf(1, (lineLimit * LEGACY_CANDIDATE_LIMIT_FACTOR).toInt())
            } else {
                Int.MAX_VALUE
            }
        markup.processRangeHighlightersOverlappingWith(
            queryRange.startOffset,
            queryRange.endOffset,
        ) { highlighter ->
            if (highlighter.isValid && isStickyLineMarker(highlighter)) {
                scopes += TextRange(highlighter.startOffset, highlighter.endOffset)
            }
            scopes.size < candidateLimit
        }
        if (scopes.isEmpty()) return emptyList()
        scopes.sortWith(
            compareBy(TextRange::getStartOffset)
                .thenByDescending(TextRange::getEndOffset),
        )

        val deduplicatedVisualLines = HashSet<Int>()
        val candidates = ArrayList<StickyCandidate>(scopes.size)
        for (scope in scopes) {
            val startOffset = scope.startOffset.coerceIn(0, document.textLength)
            val endOffset = scope.endOffset.coerceIn(startOffset, document.textLength)
            val primaryLogicalLine = document.getLineNumber(startOffset)
            val scopeLogicalLine = document.getLineNumber(endOffset)
            val primaryVisualLine =
                editor
                    .logicalToVisualPosition(
                        LogicalPosition(primaryLogicalLine, 0),
                    ).line
            if (!deduplicatedVisualLines.add(primaryVisualLine)) continue
            val scopeVisualLine =
                editor
                    .logicalToVisualPosition(
                        LogicalPosition(scopeLogicalLine, 0),
                    ).line
            if (scopeVisualLine - primaryVisualLine + 1 < MINIMUM_SCOPE_VISUAL_LINES) {
                continue
            }
            candidates +=
                StickyCandidate(
                    primaryLogicalLine = primaryLogicalLine,
                    primaryVisualLine = primaryVisualLine,
                    scopeVisualLine = scopeVisualLine,
                )
        }
        candidates.sortWith(
            compareBy(StickyCandidate::primaryVisualLine)
                .thenByDescending(StickyCandidate::scopeVisualLine),
        )
        return displayedSourceRanges(
            editor = editor,
            candidates = candidates,
            editorY = visibleArea.y,
            editorHeight = visibleArea.height,
            lineHeight = lineHeight,
            lineLimit = lineLimit,
        )
    }

    private fun displayedSourceRanges(
        editor: Editor,
        candidates: List<StickyCandidate>,
        editorY: Int,
        editorHeight: Int,
        lineHeight: Int,
        lineLimit: Int,
    ): List<TextRange> {
        if (panelIsTooLarge(0, lineHeight, editorHeight)) return emptyList()
        val document = editor.document
        val displayed = ArrayList<TextRange>(minOf(candidates.size, lineLimit))
        var panelHeight = 0
        for ((primaryLogicalLine, primaryVisualLine, scopeVisualLine) in candidates) {
            val primaryBottomY = editor.visualLineToY(primaryVisualLine) + lineHeight
            val scopeTopY = editor.visualLineToY(scopeVisualLine)
            val scopeBottomY = scopeTopY + lineHeight
            val stickyBottomY = editorY + panelHeight + lineHeight
            if (stickyBottomY.toLong() in
                (primaryBottomY.toLong() + 1L)..scopeBottomY.toLong()
            ) {
                val overlap =
                    if (stickyBottomY <= scopeTopY) {
                        0
                    } else {
                        stickyBottomY - scopeTopY
                    }
                if (overlap < lineHeight) {
                    displayed +=
                        TextRange(
                            document.getLineStartOffset(primaryLogicalLine),
                            document.getLineEndOffset(primaryLogicalLine),
                        )
                    panelHeight += lineHeight - overlap
                }
                if (overlap > 0 ||
                    displayed.size >= lineLimit ||
                    panelIsTooLarge(panelHeight, lineHeight, editorHeight)
                ) {
                    break
                }
            }
        }
        return displayed
    }

    private fun panelIsTooLarge(panelHeight: Int, lineHeight: Int, editorHeight: Int): Boolean =
        panelHeight.toLong() + 2L * lineHeight > editorHeight / 2L

    fun documentMarkupModel(editor: Editor): MarkupModelEx? {
        val project = editor.project?.takeUnless { it.isDisposed } ?: return null
        return DocumentMarkupModel.forDocument(editor.document, project, false)
            as? MarkupModelEx
    }

    fun isStickyLineMarker(highlighter: RangeHighlighter): Boolean =
        highlighter.textAttributesKey?.externalName == STICKY_LINE_MARKER

    private data class StickyCandidate(
        val primaryLogicalLine: Int,
        val primaryVisualLine: Int,
        val scopeVisualLine: Int,
    )

    private const val STICKY_LINE_MARKER = "STICKY_LINE_MARKER"
    private const val MINIMUM_STICKY_SCROLL_Y = 3
    private const val MINIMUM_SCOPE_VISUAL_LINES = 5
    private const val STICKY_BORDER_HEIGHT = 1
    private const val LEGACY_BASELINE_VERSION = 241
    private const val LEGACY_CANDIDATE_LIMIT_FACTOR = 1.7
}
