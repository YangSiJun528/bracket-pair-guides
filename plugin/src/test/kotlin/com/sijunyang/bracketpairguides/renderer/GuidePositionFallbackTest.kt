package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

class GuidePositionFallbackTest : BasePlatformTestCase() {
    fun testSnapshotWithOutOfRangePositionIndexUsesTheBoundedActiveGuideResolver() {
        val body = List(300) { index ->
            if (index == 260) "value" else "        value"
        }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("OversizedGuideFallback.txt", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.lastIndexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 301,
        )
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val exactIndex = checkNotNull(
            GuidePositionIndex.from(editor.document, 4, EmptyProgressIndicator()),
        )
        assertEquals(0, exactIndex.guideFor(pair).guideColumn)
        val unrelatedIndex = checkNotNull(
            GuidePositionIndex.from(
                editor.document,
                4,
                EmptyProgressIndicator(),
                indexedLineRange = 261..261,
            ),
        )
        assertEquals(0, unrelatedIndex.guideFor(pair).guideColumn)
        assertEquals(null, unrelatedIndex.guideForOrNull(pair))

        val options = PluginOptions(colorBracketTokens = false)
        val stamp = AnalysisStamp.current(editor, AnalysisCapabilities.from(options))
        val session = EditorGuideSession.detached(
            editor = editor,
            options = options,
            visibleRangeProvider = { TextRange(0, editor.document.textLength) },
        )
        try {
            session.accept(
                AnalysisSnapshot(
                    stamp = stamp,
                    pairs = listOf(pair),
                    tokenIndex = BracketTokenIndex.build(emptyList()),
                    activeIndex = ActiveBracketPairIndex.build(listOf(pair)),
                    positionIndex = unrelatedIndex,
                ),
            )

            val guide = checkNotNull(
                session.activeGuide?.getUserData(GUIDE_PAINT_STATE_KEY)?.guide,
            )
            assertEquals(4, guide.guideColumn)
            assertEquals(pair.closeLine, guide.anchorLine)
        } finally {
            session.dispose()
        }
    }
}
