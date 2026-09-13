package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class NativeEditorSettingsRefreshTest : BasePlatformTestCase() {
    fun testBraceRefreshRetriggersCaretListenersWithoutMovingTheCaret() {
        myFixture.configureByText("StationaryCaret.txt", "before <caret>{ after")
        val editor = myFixture.editor
        val caretModel = editor.caretModel
        val caret = caretModel.primaryCaret
        val originalOffset = caret.offset
        val originalLogicalPosition = caret.logicalPosition
        val originalVisualPosition = caret.visualPosition
        val originalSelection = caret.selectionRange
        val originalCaretCount = caretModel.caretCount
        var observed: CaretEvent? = null
        val listener =
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    observed = event
                }
            }
        caretModel.addCaretListener(listener)
        try {
            assertThat(NativeBraceHighlightingRefresh.retrigger(editor)).isTrue()
        } finally {
            caretModel.removeCaretListener(listener)
        }

        assertThat(observed).isNotNull
        assertThat(observed?.caret).isSameAs(caret)
        assertThat(observed?.oldPosition).isEqualTo(originalLogicalPosition)
        assertThat(observed?.newPosition).isEqualTo(originalLogicalPosition)
        assertThat(caret.offset).isEqualTo(originalOffset)
        assertThat(caret.logicalPosition).isEqualTo(originalLogicalPosition)
        assertThat(caret.visualPosition).isEqualTo(originalVisualPosition)
        assertThat(caret.selectionRange).isEqualTo(originalSelection)
        assertThat(caretModel.caretCount).isEqualTo(originalCaretCount)
    }

    fun testCaretRefreshContractRejectsAnUnrelatedMethod() {
        assertThat(CaretEventRefreshContract().methodFor(UnrelatedCaretModel::class.java)).isNull()
    }

    private class UnrelatedCaretModel {
        @Suppress("unused")
        private fun fireCaretPositionChanged(event: String) = event
    }
}
