package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollingModel
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import org.assertj.core.api.Assertions.assertThat
import java.awt.Rectangle
import javax.swing.JComponent

class EditorContentRepaintTest : BasePlatformTestCase() {
    fun testCaretMoveRepaintsOnlyTheVisibleEditorArea() {
        val visibleArea = Rectangle(7, 11, 222, 123)

        val repaints = moveCaretBetweenPairs(visibleArea)

        assertThat(repaints.regions).containsExactly(visibleArea)
        assertThat(repaints.fullCount).isZero()
    }

    fun testCaretMoveFallsBackToFullRepaintWithoutAViewport() {
        val repaints = moveCaretBetweenPairs(Rectangle())

        assertThat(repaints.regions).isEmpty()
        assertThat(repaints.fullCount).isEqualTo(1)
    }

    private fun moveCaretBetweenPairs(visibleArea: Rectangle): RecordingComponent {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("ViewportRepaint.txt", source)
        val delegate = myFixture.editor
        val repaints = RecordingComponent()
        val editor = FixedViewportEditor(delegate, visibleArea, repaints)
        val options = BracketGuidePreferences(colorBracketTokens = false)
        val outer =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
            )
        val inner =
            BracketPair(
                source.indexOf('('),
                1,
                source.indexOf(')'),
                1,
                1,
                0,
                0,
            )
        delegate.caretModel.moveToOffset(source.indexOf("inner"))
        val session =
            EditorGuideSession(
                editor = editor,
                visibleRange = { TextRange(0, it.document.textLength) },
                options = options,
            )
        val input =
            AnalysisInput(
                editor = editor,
                fileType = myFixture.file.fileType,
                coverage = options.analysisCoverage(),
                disabledLanguageIds = emptySet(),
            )

        try {
            session.accept(
                AnalysisOutcome.Complete(input.bracketSnapshot(listOf(outer, inner))),
            )
            repaints.reset()

            delegate.caretModel.moveToOffset(source.indexOf("tail"))
            session.caretMoved()
        } finally {
            session.dispose()
        }
        return repaints
    }
}

private class FixedViewportEditor(
    private val delegate: Editor,
    visibleArea: Rectangle,
    private val recordedContentComponent: JComponent,
) : Editor by delegate {
    private val fixedScrollingModel =
        object : ScrollingModel by delegate.scrollingModel {
            override fun getVisibleArea(): Rectangle = Rectangle(visibleArea)
        }

    override fun getScrollingModel(): ScrollingModel = fixedScrollingModel

    override fun getContentComponent(): JComponent = recordedContentComponent
}

private class RecordingComponent : JComponent() {
    val regions = mutableListOf<Rectangle>()
    var fullCount = 0
        private set
    private var recording = true

    override fun repaint() {
        if (recording) {
            fullCount++
        } else {
            super.repaint()
        }
    }

    override fun repaint(rectangle: Rectangle?) {
        if (recording && rectangle != null) {
            regions += Rectangle(rectangle)
        } else {
            super.repaint(rectangle)
        }
    }

    fun reset() {
        recording = false
        regions.clear()
        fullCount = 0
        recording = true
    }
}
