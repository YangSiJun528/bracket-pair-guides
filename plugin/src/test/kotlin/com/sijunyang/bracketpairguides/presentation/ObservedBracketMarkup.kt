package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat

/** Markup observable through the IntelliJ editor API after a plugin interaction. */
internal data class ObservedBracketMarkup(
    val tokenMarks: List<RangeHighlighter>,
    val activePairMarks: List<RangeHighlighter>,
    val guideMarks: List<RangeHighlighter>,
) {
    val allMarks: List<RangeHighlighter>
        get() = tokenMarks + activePairMarks + guideMarks
}

internal fun Editor.observedBracketMarkup(): ObservedBracketMarkup {
    val marks = markupModel.allHighlighters.filter(RangeHighlighter::isValid)
    val tokenMarks = marks.filter(RangeHighlighter::isBracketTokenMark)
    val guideMarks = marks.filter { mark -> mark.customRenderer is BracketGuideDrawing }
    val activePairMarks =
        marks.filter { mark ->
            mark.layer == HighlighterLayer.ELEMENT_UNDER_CARET &&
                mark !in tokenMarks &&
                mark !in guideMarks
        }
    return ObservedBracketMarkup(tokenMarks, activePairMarks, guideMarks)
}

private fun RangeHighlighter.isBracketTokenMark(): Boolean {
    val key = textAttributesKey ?: return false
    return (0 until StoredColorFormat.COLOR_COUNT).any { level ->
        key === BracketColorPalette.levelKey(level)
    }
}
