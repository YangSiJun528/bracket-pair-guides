package com.sijunyang.bracketpairguides.analysis.guide

import com.intellij.openapi.editor.Document

/** Test fixture input conversion; indentation and index calculation use production pure modules. */
internal class GuidePositionTestAdapter(
    private val document: Document,
    private val tabSize: Int,
    private val checkCanceled: () -> Unit,
) {
    fun index(lines: IntRange): GuidePositionIndex? {
        checkCanceled()
        if (lines.isEmpty() || lines.last < 0 || lines.first >= document.lineCount) return null
        val first = maxOf(0, lines.first)
        val last = minOf(lines.last, document.lineCount - 1)
        val text = document.immutableCharSequence
        return GuidePositionIndex.from(first, last - first + 1, checkCanceled) { relative ->
            val line = first + relative
            val indentation = LineIndentation(tabSize, checkCanceled)
            indentation.append(
                text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString(),
                endOfLine = true,
            )
            indentation.column
        }
    }
}
