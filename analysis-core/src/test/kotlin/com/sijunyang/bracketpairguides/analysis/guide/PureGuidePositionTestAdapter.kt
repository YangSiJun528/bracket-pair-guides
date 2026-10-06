package com.sijunyang.bracketpairguides.analysis.guide

/** Plain-text fixture conversion using the production indentation and index calculation. */
internal class PureGuidePositionTestAdapter(
    text: String,
    private val tabSize: Int,
    private val checkCanceled: () -> Unit,
) {
    private val sourceLines = text.split("\n")

    fun index(lines: IntRange): GuidePositionIndex? {
        checkCanceled()
        if (lines.isEmpty() || lines.last < 0 || lines.first >= sourceLines.size) return null
        val first = maxOf(0, lines.first)
        val last = minOf(lines.last, sourceLines.lastIndex)
        return GuidePositionIndex.from(first, last - first + 1, checkCanceled) { relative ->
            val indentation = LineIndentation(tabSize, checkCanceled)
            indentation.append(sourceLines[first + relative], endOfLine = true)
            indentation.column
        }
    }
}
