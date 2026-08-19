package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex
import org.assertj.core.api.Assertions.assertThat

class BracketSnapshotTest : BasePlatformTestCase() {
    fun testActivePairQueryHidesIndexReferences() {
        val outer = pair(open = 0, close = 90, depth = 0)
        val inner = pair(open = 20, close = 40, depth = 1)
        val snapshot = snapshot(listOf(outer, inner))

        assertThat(snapshot.activePairAt(10)).isEqualTo(outer)
        assertThat(snapshot.activePairAt(30)).isEqualTo(inner)
        assertThat(snapshot.activePairAt(31)).isSameAs(snapshot.activePairAt(30))
        assertThat(snapshot.activePairAt(100)).isNull()
    }

    fun testVisibleTokenWindowPreservesSortedMetadataWithoutTokenAllocations() {
        val pairs = listOf(
            pair(open = 0, close = 2, depth = 0),
            pair(open = 10, close = 12, depth = 1),
            pair(open = 20, close = 22, depth = 2),
        )
        val tokens = snapshot(pairs).visibleTokens(
            range = TextRange(1, 21),
            focusOffset = 10,
            limit = 10,
        )

        assertThat(tokens.isCapped).isFalse()
        assertThat(tokens.size).isEqualTo(5)
        assertThat(tokens.offsets()).containsExactly(0, 2, 10, 12, 20)
        assertThat(tokens.lengths()).containsExactly(1, 1, 1, 1, 1)
        assertThat(tokens.depths()).containsExactly(0, 0, 1, 1, 2)
        assertThat(tokens.stableFocusStartOffset).isEqualTo(1)
        assertThat(tokens.stableFocusEndOffset).isEqualTo(21)
    }

    fun testCappedTokenWindowIsCenteredAndPublishesAStableFocusEnvelope() {
        val pairs = List(5) { index ->
            pair(open = index * 10, close = index * 10 + 2, depth = index)
        }
        val tokens = snapshot(pairs).visibleTokens(
            range = TextRange(0, 100),
            focusOffset = 21,
            limit = 4,
        )

        assertThat(tokens.isCapped).isTrue()
        assertThat(tokens.offsets()).containsExactly(12, 20, 22, 30)
        assertThat(tokens.stableFocusStartOffset).isEqualTo(20)
        assertThat(tokens.stableFocusEndOffset).isEqualTo(32)
    }

    private fun snapshot(pairs: List<BracketPair>): BracketSnapshot {
        myFixture.configureByText("Snapshot.txt", " ".repeat(128))
        val pairTable = pairs.toPairTable()
        return BracketSnapshot(
            stamp = AnalysisInput(
                editor = myFixture.editor,
                fileType = myFixture.file.fileType,
                coverage = AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = false,
                ),
                disabledLanguageIds = emptySet(),
            ).stamp,
            matcherAvailability = BraceMatcherAvailability.AVAILABLE,
            indexes = BracketIndexes(
                pairs = pairTable,
                tokens = BracketTokenIndex.build(pairTable, NO_CANCELLATION),
                activePairs = ActiveBracketPairIndex.build(pairTable, NO_CANCELLATION),
                guidePositions = null,
            ),
        )
    }

    private fun pair(open: Int, close: Int, depth: Int): BracketPair = BracketPair(
        openOffset = open,
        openTokenLength = 1,
        closeOffset = close,
        closeTokenLength = 1,
        depth = depth,
        openLine = 0,
        closeLine = 0,
    )

    private fun TokenWindow.offsets(): List<Int> =
        List(size, ::offsetAt)

    private fun TokenWindow.lengths(): List<Int> =
        List(size, ::lengthAt)

    private fun TokenWindow.depths(): List<Int> =
        List(size, ::depthAt)

    private companion object {
        val NO_CANCELLATION: () -> Unit = {}
    }
}
