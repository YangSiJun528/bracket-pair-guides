package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.presentation.BracketColorPalette
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import java.awt.Color

internal class VisibleTokenWindowTest : BracketGuideHighlightingFixture() {
    fun testStickyTokenOutsidePaddedViewportSurvivesScrollingWithoutRecognition() {
        val source = " ".repeat(24_000)
        myFixture.configureByText("StickyViewport.txt", source)
        val pair = BracketPair(100, 1, 110, 1, 0, 0, 0)
        var collections = 0
        var visibleRange = TextRange(8_000, 8_256)
        val stickyRanges = listOf(TextRange(90, 120))

        applyPass(
            pairs = {
                collections++
                listOf(pair)
            },
            visibleRange = { visibleRange },
            stickySourceRanges = { stickyRanges },
        )

        val initialMarks = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(initialMarks.keys).contains(100, 110)
        assertThat(collections).isEqualTo(1)

        for (startOffset in listOf(12_000, 16_000, 20_000)) {
            visibleRange = TextRange(startOffset, startOffset + 256)
            session().visibleAreaChanged()

            val scrolledMarks = bracketColorHighlighters().associateBy { it.startOffset }
            assertThat(scrolledMarks.keys).contains(100, 110)
            assertThat(scrolledMarks[100]).isSameAs(initialMarks[100])
            assertThat(scrolledMarks[110]).isSameAs(initialMarks[110])
        }
        assertThat(collections).isEqualTo(1)
    }

    fun testStickyRangesAreRemovedAndDeduplicatedWithTheViewport() {
        val source = " ".repeat(20_000)
        myFixture.configureByText("StickyLifecycle.txt", source)
        val pairs =
            listOf(
                BracketPair(100, 1, 3_000, 1, 0, 0, 0),
                BracketPair(1_000, 1, 2_000, 1, 1, 0, 0),
            )
        var visibleRange = TextRange(10_000, 10_256)
        var stickyRanges = listOf(TextRange(90, 120), TextRange(990, 1_020))

        applyPass(
            pairs = { pairs },
            visibleRange = { visibleRange },
            stickySourceRanges = { stickyRanges },
        )

        val stickyMarks = bracketColorHighlighters()
        assertThat(stickyMarks.map { it.startOffset }.sorted())
            .containsExactly(100, 1_000)
        val initialByOffset = stickyMarks.associateBy { it.startOffset }

        visibleRange = TextRange(90, 120)
        session().visibleAreaChanged()

        val overlappingMarks = bracketColorHighlighters()
        assertThat(overlappingMarks).hasSize(2)
        val overlappingViewport = overlappingMarks.associateBy { it.startOffset }
        assertThat(overlappingViewport.keys).containsExactlyInAnyOrder(100, 1_000)
        assertThat(overlappingViewport[100]).isSameAs(initialByOffset[100])
        assertThat(overlappingViewport[1_000]).isSameAs(initialByOffset[1_000])

        visibleRange = TextRange(10_000, 10_256)
        session().visibleAreaChanged()
        val stickyOnlyAgain = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(stickyOnlyAgain[100]).isSameAs(initialByOffset[100])
        assertThat(stickyOnlyAgain[1_000]).isSameAs(initialByOffset[1_000])

        stickyRanges = emptyList()
        session().visibleAreaChanged()
        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(stickyMarks).allMatch { !it.isValid }
    }

    fun testStickyTokensKeepIdentityInsideADensePaddedViewportWindow() {
        val pairCount = 12_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("StickyDenseBoundary.txt", source)
        var collections = 0
        var visibleRange = TextRange(8_000, 8_256)

        applyPass(
            pairs = {
                collections++
                sequentialPairs(pairCount)
            },
            visibleRange = { visibleRange },
            stickySourceRanges = { listOf(TextRange(100, 102)) },
        )
        val initial = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(initial.keys).contains(100, 101)

        // The 4,096-character padding now contains the sticky range, while the
        // capped viewport slice is focused thousands of tokens away from it.
        visibleRange = TextRange(4_100, 8_196)
        session().visibleAreaChanged()
        val denseWindow = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(denseWindow).hasSize(2_048)
        assertThat(denseWindow[100]).isSameAs(initial[100])
        assertThat(denseWindow[101]).isSameAs(initial[101])

        visibleRange = TextRange(0, 256)
        session().visibleAreaChanged()
        val overlappingViewport = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(overlappingViewport[100]).isSameAs(initial[100])
        assertThat(overlappingViewport[101]).isSameAs(initial[101])

        visibleRange = TextRange(4_100, 8_196)
        session().visibleAreaChanged()
        val stickyOnlyAgain = bracketColorHighlighters().associateBy { it.startOffset }
        assertThat(stickyOnlyAgain[100]).isSameAs(initial[100])
        assertThat(stickyOnlyAgain[101]).isSameAs(initial[101])
        assertThat(collections).isEqualTo(1)
    }

    fun testStickyTokensHavePriorityInsideTheGlobalDecorationCap() {
        val pairCount = 20_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("StickyDenseViewport.txt", source)
        val pairs = sequentialPairs(pairCount)

        applyPass(
            pairs = { pairs },
            visibleRange = { TextRange(20_000, 36_384) },
            stickySourceRanges = { listOf(TextRange(0, 2)) },
        )

        val decorations = bracketColorHighlighters()
        assertThat(decorations).hasSize(2_048)
        assertThat(decorations.map { it.startOffset }).contains(0, 1)
        assertThat(decorations).anyMatch { highlighter ->
            highlighter.startOffset in 20_000 until 36_384
        }
    }

    fun testDenseFirstStickyRangeDoesNotStarveALaterStickyRange() {
        val pairCount = 20_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("StickyDenseLines.txt", source)

        applyPass(
            pairs = { sequentialPairs(pairCount) },
            visibleRange = { TextRange(20_000, 36_384) },
            stickySourceRanges = {
                listOf(TextRange(0, 4_000), TextRange(10_000, 10_002))
            },
        )

        val decorations = bracketColorHighlighters()
        assertThat(decorations).hasSize(2_048)
        assertThat(decorations.map { it.startOffset }).contains(10_000, 10_001)
    }

    fun testStickyDecorationsFollowColorizationAndSessionLifecycle() {
        val source = " ".repeat(12_000)
        myFixture.configureByText("StickyCleanup.txt", source)
        val enabled = BracketGuideSettings.getInstance().options

        applyPass(
            pairs = { listOf(BracketPair(100, 1, 110, 1, 1, 0, 0)) },
            visibleRange = { TextRange(8_000, 8_256) },
            stickySourceRanges = { listOf(TextRange(90, 120)) },
        )
        val initiallyVisible = bracketColorHighlighters()
        assertThat(initiallyVisible).hasSize(2)
        assertThat(initiallyVisible).allMatch { highlighter ->
            highlighter.textAttributesKey == BracketColorPalette.levelKey(1)
        }

        val recoloredOptions =
            enabled.copy(
                levelBaseColors = enabled.levelBaseColors.updated(1, 0x123456),
            )
        applyOptions(recoloredOptions)
        val recolored = bracketColorHighlighters()
        assertThat(recolored).containsExactlyElementsOf(initiallyVisible)
        assertThat(recolored).allMatch { highlighter ->
            highlighter.getTextAttributes(myFixture.editor.colorsScheme)?.foregroundColor ==
                Color(0x123456)
        }

        applyOptions(recoloredOptions.copy(colorBracketTokens = false))

        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(initiallyVisible).allMatch { !it.isValid }

        applyOptions(recoloredOptions)
        val restored = bracketColorHighlighters()
        assertThat(restored).hasSize(2)

        EditorGuideSessions.dispose(myFixture.editor)

        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(restored).allMatch { !it.isValid }
    }

    fun testDocumentEditImmediatelyRemovesStickyOnlyDecorations() {
        val source = " ".repeat(12_000)
        myFixture.configureByText("StickyEditCleanup.txt", source)
        applyPass(
            pairs = { listOf(BracketPair(100, 1, 110, 1, 0, 0, 0)) },
            visibleRange = { TextRange(8_000, 8_256) },
            stickySourceRanges = { listOf(TextRange(90, 120)) },
        )
        val stickyMarks = bracketColorHighlighters()
        assertThat(stickyMarks).hasSize(2)

        resizeDocument(source.length + 1)

        assertThat(bracketColorHighlighters()).isEmpty()
        assertThat(stickyMarks).allMatch { !it.isValid }
    }

    fun testViewportBoundsMarkupForFiftyThousandPairsAndScrollReusesRecognition() {
        val pairCount = 50_000
        val source = "()".repeat(pairCount)
        myFixture.configureByText("Viewport.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        val recognizedPairs =
            List(pairCount) { index ->
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
        val pairs =
            List(pairCount) { index ->
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
        val pairs =
            List(pairCount) { index ->
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
        val pairs =
            List(pairCount) { index ->
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
                bracketColorHighlighters()
                    .minOfOrNull { it.startOffset }
                    ?.let { it > initialLastOffset } == true &&
                    viewportRequests == initialViewportRequests + 1
            },
            10_000,
        )
        assertThat(bracketColorHighlighters()).hasSize(decorationLimit)
        assertThat(viewportRequests).isEqualTo(initialViewportRequests + 1)
    }
}
