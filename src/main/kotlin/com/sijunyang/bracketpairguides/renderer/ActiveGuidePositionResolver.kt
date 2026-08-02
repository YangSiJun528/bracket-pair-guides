package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.editor.Editor

/** Bounded indentation lookup used only while the authoritative snapshot is stale. */
internal object ActiveGuidePositionResolver {
    fun resolve(
        editor: Editor,
        pair: BracketPair,
        previous: BracketGuide?,
        currentAnchorLine: Int?,
        change: DocumentChange?,
    ): BracketGuide {
        if (pair.openLine == pair.closeLine) return BracketGuide(pair, 0)

        val document = editor.document
        val firstLine = (pair.openLine + 1).coerceIn(0, document.lineCount - 1)
        val lastLine = pair.closeLine.coerceIn(firstLine, document.lineCount - 1)
        val previousGuide = previous?.takeIf {
            it.pair.openOffset <= pair.closeOffset && it.pair.closeOffset >= pair.openOffset
        }
        if (previousGuide != null && change?.mayAffectGuidePosition != true) {
            return previousGuide.withPair(pair, currentAnchorLine, firstLine, lastLine)
        }

        if (lastLine - firstLine + 1 <= MAX_SYNCHRONOUS_LINES) {
            return scan(editor, pair, firstLine, lastLine)
        }

        if (previousGuide != null) {
            val changedLine = change?.offset
                ?.coerceIn(0, document.textLength)
                ?.let(document::getLineNumber)
            if (changedLine != null && changedLine in firstLine..lastLine) {
                val changedIndent = indentationColumn(editor, changedLine)
                if (changedIndent < previousGuide.guideColumn) {
                    return BracketGuide(pair, changedIndent, changedLine)
                }
            }
            return previousGuide.withPair(pair, currentAnchorLine, firstLine, lastLine)
        }

        val closingIndent = indentationColumn(editor, lastLine)
        return BracketGuide(
            pair = pair,
            guideColumn = closingIndent.takeUnless { it == GuidePositionIndex.NO_INDENT } ?: 0,
            anchorLine = lastLine,
        )
    }

    private fun BracketGuide.withPair(
        pair: BracketPair,
        currentAnchorLine: Int?,
        firstLine: Int,
        lastLine: Int,
    ): BracketGuide = BracketGuide(
        pair = pair,
        guideColumn = guideColumn,
        anchorLine = currentAnchorLine?.coerceIn(firstLine, lastLine)
            ?: anchorLine.coerceIn(firstLine, lastLine),
    )

    private fun scan(
        editor: Editor,
        pair: BracketPair,
        firstLine: Int,
        lastLine: Int,
    ): BracketGuide {
        var minimum = GuidePositionIndex.NO_INDENT
        var anchorLine = firstLine
        var line = firstLine
        while (line <= lastLine) {
            val indentation = indentationColumn(editor, line)
            if (indentation < minimum) {
                minimum = indentation
                anchorLine = line
                if (minimum == 0) break
            }
            line++
        }
        return BracketGuide(
            pair = pair,
            guideColumn = minimum.takeUnless { it == GuidePositionIndex.NO_INDENT } ?: 0,
            anchorLine = anchorLine,
        )
    }

    private fun indentationColumn(editor: Editor, line: Int): Int {
        val document = editor.document
        val text = document.immutableCharSequence
        val start = document.getLineStartOffset(line)
        val end = minOf(document.getLineEndOffset(line), start + MAX_SYNCHRONOUS_LINE_LENGTH)
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        var column = 0
        var offset = start
        while (offset < end) {
            when (text[offset]) {
                ' ' -> column++
                '\t' -> column += tabSize - column % tabSize
                else -> return column
            }
            offset++
        }
        return GuidePositionIndex.NO_INDENT
    }

    private const val MAX_SYNCHRONOUS_LINES = 512
    private const val MAX_SYNCHRONOUS_LINE_LENGTH = 4_096
}
