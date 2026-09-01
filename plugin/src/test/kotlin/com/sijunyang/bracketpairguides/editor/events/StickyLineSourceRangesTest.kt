package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.ScrollingModel
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat
import java.awt.Rectangle

internal class StickyLineSourceRangesTest : BasePlatformTestCase() {
    fun testCalculatesOnlyTheNestedSourceLinesActuallyDisplayedByStickyLines() {
        val source =
            buildString {
                repeat(120) { line ->
                    append("line ")
                    append(line)
                    append('\n')
                }
            }
        myFixture.configureByText("StickyScopes.txt", source)
        val delegate = myFixture.editor
        val document = delegate.document
        val markup =
            checkNotNull(
                DocumentMarkupModel.forDocument(document, project, true),
            )
        val marker = TextAttributesKey.createTextAttributesKey("STICKY_LINE_MARKER")

        fun addScope(startLine: Int, endLine: Int) = markup.addRangeHighlighter(
            marker,
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine),
            HighlighterLayer.SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        )
        val outer = addScope(2, 100)
        val inner = addScope(10, 80)
        val tooNarrow = addScope(48, 50)
        val lineHeight = delegate.lineHeight
        val editor =
            StickyViewportEditor(
                delegate = delegate,
                visibleArea = Rectangle(0, lineHeight * 50, 800, lineHeight * 40),
                stickyLinesShown = true,
                stickyLinesLimit = 3,
            )

        try {
            assertThat(StickyLineSourceRanges.calculate(editor)).containsExactly(
                TextRange(
                    document.getLineStartOffset(2),
                    document.getLineEndOffset(2),
                ),
                TextRange(
                    document.getLineStartOffset(10),
                    document.getLineEndOffset(10),
                ),
            )
        } finally {
            markup.removeHighlighter(outer)
            markup.removeHighlighter(inner)
            markup.removeHighlighter(tooNarrow)
        }
    }

    fun testReturnsNoRangesWhenStickyLinesAreDisabled() {
        myFixture.configureByText("StickyDisabled.txt", "{\nvalue\n}\n")
        val delegate = myFixture.editor
        val editor =
            StickyViewportEditor(
                delegate = delegate,
                visibleArea = Rectangle(0, delegate.lineHeight * 2, 800, 400),
                stickyLinesShown = false,
                stickyLinesLimit = 3,
            )

        assertThat(StickyLineSourceRanges.calculate(editor)).isEmpty()
    }
}

private class StickyViewportEditor(
    private val delegate: Editor,
    visibleArea: Rectangle,
    stickyLinesShown: Boolean,
    stickyLinesLimit: Int,
) : Editor by delegate {
    private val fixedScrollingModel =
        object : ScrollingModel by delegate.scrollingModel {
            override fun getVisibleArea(): Rectangle = Rectangle(visibleArea)
        }
    private val fixedSettings =
        object : EditorSettings by delegate.settings {
            override fun areStickyLinesShown(): Boolean = stickyLinesShown

            override fun getStickyLinesLimit(): Int = stickyLinesLimit
        }

    override fun getScrollingModel(): ScrollingModel = fixedScrollingModel

    override fun getSettings(): EditorSettings = fixedSettings
}
