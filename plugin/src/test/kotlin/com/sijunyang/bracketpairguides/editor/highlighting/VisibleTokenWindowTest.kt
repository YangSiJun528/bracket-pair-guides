package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import org.assertj.core.api.Assertions.assertThat

internal class VisibleTokenWindowTest : BracketGuideHighlightingFixture() {
    fun testViewportBoundsMarkupForFiftyThousandPairsAndScrollReusesRecognition() {
        val pairCount = 50_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("Viewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val recognizedPairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }
        var collections = 0
        var visibleRange = TextRange(0, 256)
        val pairs = {
            collections++
            recognizedPairs
        }

        applyPass(pairs) { visibleRange }

        assertThat(collections).isEqualTo(1)
        assertThat(bracketColorHighlighters()).isNotEmpty()
        assertThat(bracketColorHighlighters().size).isLessThanOrEqualTo(1_024)

        visibleRange = TextRange(50_000, 50_256)
        session().visibleAreaChanged()

        assertThat(collections).isEqualTo(1)
        val scrolledDecorations = bracketColorHighlighters()
        assertThat(scrolledDecorations).isNotEmpty()
        assertThat(scrolledDecorations.size).isLessThanOrEqualTo(1_536)
        assertThat(scrolledDecorations).anyMatch {
            it.startOffset in visibleRange.startOffset until visibleRange.endOffset
        }
    }

    fun testOversizedDenseViewportStaysAnchoredAwayFromCaretAndCapsDecorations() {
        val pairCount = 50_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }

        applyPass({ pairs }) {
            TextRange(50_000, source.length)
        }

        val decorations = bracketColorHighlighters()
        assertThat(decorations).isNotEmpty()
        assertThat(decorations.size).isLessThan(pairs.size * 2)
        assertThat(decorations.minOf { it.startOffset })
            .describedAs("Decorations should follow the reported viewport instead of the off-screen caret")
            .isGreaterThan(50_000)
    }

    fun testCappedDenseDecorationsRecenterWhenScrollingInsideTheCachedPadding() {
        val pairCount = 25_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewportScroll.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }
        var visibleRange = TextRange(20_000, 36_384)

        applyPass({ pairs }) { visibleRange }
        val initialDecorations = bracketColorHighlighters()
        assertThat(initialDecorations.size).isLessThan(pairs.size * 2)
        val decorationLimit = initialDecorations.size
        val initialLastOffset = initialDecorations.maxOf { it.startOffset }

        // This viewport still fits in the first padded character window. The
        // capped token slice nevertheless has to follow its new center.
        visibleRange = TextRange(24_000, 40_384)
        session().visibleAreaChanged()

        val scrolledDecorations = bracketColorHighlighters()
        assertThat(scrolledDecorations).hasSize(decorationLimit)
        assertThat(scrolledDecorations.minOf { it.startOffset })
            .describedAs("Capped decorations must be recentered within a cached character window")
            .isGreaterThan(initialLastOffset)
    }

    fun testCappedDenseDecorationsFollowCaretMovementWithoutScrolling() {
        val pairCount = 5_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("DenseViewportCaret.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val pairs = List(pairCount) { index ->
            BracketPair(
                openOffset = index * 2,
                openTokenLength = 1,
                closeOffset = index * 2 + 1,
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        }

        var viewportRequests = 0
        applyPass({ pairs }) {
            viewportRequests++
            TextRange(0, source.length)
        }
        val initialDecorations = bracketColorHighlighters()
        assertThat(initialDecorations.size).isLessThan(pairs.size * 2)
        val decorationLimit = initialDecorations.size
        val initialLastOffset = initialDecorations.maxOf { it.startOffset }
        val initialViewportRequests = viewportRequests

        repeat(8) { step ->
            editor.caretModel.moveToOffset(source.length - 1 - step * 2)
        }

        PlatformTestUtil.waitWithEventsDispatching(
            "capped token window follows caret",
            {
                bracketColorHighlighters().minOfOrNull { it.startOffset }
                    ?.let { it > initialLastOffset } == true &&
                    viewportRequests == initialViewportRequests + 1
            },
            10_000,
        )
        assertThat(bracketColorHighlighters()).hasSize(decorationLimit)
        assertThat(viewportRequests).isEqualTo(initialViewportRequests + 1)
    }
}
