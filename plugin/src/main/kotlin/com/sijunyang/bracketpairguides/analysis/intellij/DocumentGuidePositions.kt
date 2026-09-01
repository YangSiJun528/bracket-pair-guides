package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.editor.Document
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.guide.VisualColumn

/** IntelliJ document view used to create one exact guide-position index. */
internal class DocumentGuidePositions(
    private val document: Document,
    tabSize: Int,
    private val checkCanceled: () -> Unit,
) {
    private val tabSize = tabSize.coerceAtLeast(1)

    fun index(indexedLineRange: IntRange): GuidePositionIndex? {
        checkCanceled()
        val documentLineCount = document.lineCount
        if (documentLineCount <= 0 ||
            indexedLineRange.isEmpty() ||
            indexedLineRange.last < 0 ||
            indexedLineRange.first >= documentLineCount
        ) {
            return null
        }

        val baseLine = maxOf(indexedLineRange.first, 0)
        val lastLine = minOf(indexedLineRange.last, documentLineCount - 1)
        val lineCount = (lastLine.toLong() - baseLine + 1L).toInt()
        val text = document.immutableCharSequence
        return GuidePositionIndex.from(
            baseLine = baseLine,
            lineCount = lineCount,
            checkCanceled = checkCanceled,
        ) { relativeLine ->
            val line = baseLine + relativeLine
            indentationColumn(
                text = text,
                start = document.getLineStartOffset(line),
                end = document.getLineEndOffset(line),
            )
        }
    }

    private fun indentationColumn(text: CharSequence, start: Int, end: Int): Int {
        var column = 0
        for (offset in start until end) {
            if ((offset - start) and CANCELLATION_CHARACTER_MASK == 0) {
                checkCanceled()
            }
            column =
                when (text[offset]) {
                    ' ' -> VisualColumn.afterSpace(column)
                    '\t' -> VisualColumn.afterTab(column, tabSize)
                    else -> return column
                }
        }
        return VisualColumn.BLANK_LINE_COLUMN
    }

    private companion object {
        private const val CANCELLATION_CHARACTER_MASK: Int = 0xFFF
    }
}
