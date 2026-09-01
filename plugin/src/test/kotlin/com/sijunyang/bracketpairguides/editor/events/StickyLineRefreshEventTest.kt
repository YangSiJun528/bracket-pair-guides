package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingFixture
import org.assertj.core.api.Assertions.assertThat

internal class StickyLineRefreshEventTest : BracketGuideHighlightingFixture() {
    fun testDisablingStickyLinesClearsStickyOnlyDecorationsWithoutScrolling() {
        val externalSettings = EditorSettingsExternalizable.getInstance()
        val originallyShown = externalSettings.areStickyLinesShown()
        externalSettings.setStickyLinesShown(true)
        try {
            val source = " ".repeat(12_000)
            myFixture.configureByText("StickySettingRefresh.txt", source)
            applyPass(
                pairs = { listOf(BracketPair(100, 1, 110, 1, 0, 0, 0)) },
                visibleRange = { TextRange(8_000, 8_256) },
                stickySourceRanges = {
                    if (externalSettings.areStickyLinesShown()) {
                        listOf(TextRange(90, 120))
                    } else {
                        emptyList()
                    }
                },
            )
            val stickyMarks = bracketColorHighlighters()
            assertThat(stickyMarks).hasSize(2)

            externalSettings.setStickyLinesShown(false)

            PlatformTestUtil.waitWithEventsDispatching(
                "disabling sticky lines clears token decorations",
                { bracketColorHighlighters().isEmpty() },
                10_000,
            )
            assertThat(stickyMarks).allMatch { !it.isValid }
        } finally {
            externalSettings.setStickyLinesShown(originallyShown)
        }
    }

    fun testStickyMarkupObservationEndsWithTheLastEditorForADocument() {
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument("scope")
        val firstEditor = factory.createEditor(document, project)
        val secondEditor = factory.createEditor(document, project)
        try {
            EditorGuideEvents.ensureInitialized(firstEditor)
            EditorGuideEvents.ensureInitialized(secondEditor)
            assertThat(EditorGuideEvents.isObservingStickyLineModel(firstEditor)).isTrue()
            assertThat(EditorGuideEvents.isObservingStickyLineModel(secondEditor)).isTrue()

            factory.releaseEditor(firstEditor)
            assertThat(EditorGuideEvents.isObservingStickyLineModel(firstEditor)).isFalse()
            assertThat(EditorGuideEvents.isObservingStickyLineModel(secondEditor)).isTrue()

            factory.releaseEditor(secondEditor)
            assertThat(EditorGuideEvents.isObservingStickyLineModel(secondEditor)).isFalse()
        } finally {
            if (!firstEditor.isDisposed) factory.releaseEditor(firstEditor)
            if (!secondEditor.isDisposed) factory.releaseEditor(secondEditor)
        }
    }

    fun testNativeStickyMarkerChangesRefreshDecorationsWithoutScrolling() {
        val source = " ".repeat(12_000)
        myFixture.configureByText("StickyMarkerRefresh.txt", source)
        val editor = myFixture.editor
        var stickyRanges: List<TextRange> = emptyList()
        applyPass(
            pairs = { listOf(BracketPair(100, 1, 110, 1, 0, 0, 0)) },
            visibleRange = { TextRange(8_000, 8_256) },
            stickySourceRanges = { stickyRanges },
        )
        assertThat(bracketColorHighlighters()).isEmpty()

        val markup = checkNotNull(
            DocumentMarkupModel.forDocument(editor.document, project, true),
        )
        val marker = markup.addRangeHighlighter(
            TextAttributesKey.createTextAttributesKey("STICKY_LINE_MARKER"),
            90,
            1_000,
            HighlighterLayer.SYNTAX,
            HighlighterTargetArea.EXACT_RANGE,
        )
        try {
            stickyRanges = listOf(TextRange(90, 120))
            PlatformTestUtil.waitWithEventsDispatching(
                "sticky marker addition refreshes token decorations",
                { bracketColorHighlighters().size == 2 },
                10_000,
            )
            val stickyMarks = bracketColorHighlighters()

            stickyRanges = emptyList()
            markup.removeHighlighter(marker)

            PlatformTestUtil.waitWithEventsDispatching(
                "sticky marker removal clears token decorations",
                { bracketColorHighlighters().isEmpty() },
                10_000,
            )
            assertThat(stickyMarks).allMatch { !it.isValid }
        } finally {
            if (marker.isValid) markup.removeHighlighter(marker)
        }
    }
}
