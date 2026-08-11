package com.sijunyang.bracketpairguides.analysis.snapshot

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.pairing.toPairTable
import com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class BracketSnapshotTest : BasePlatformTestCase() {
    fun testActivePairQueryHidesIndexReferences() {
        val outer = pair(open = 0, close = 90, depth = 0)
        val inner = pair(open = 20, close = 40, depth = 1)
        val snapshot = snapshot(listOf(outer, inner))

        assertEquals(outer, snapshot.activePairAt(10))
        assertEquals(inner, snapshot.activePairAt(30))
        assertSame(snapshot.activePairAt(30), snapshot.activePairAt(31))
        assertNull(snapshot.activePairAt(100))
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

        assertFalse(tokens.isCapped)
        assertEquals(5, tokens.size)
        assertEquals(listOf(0, 2, 10, 12, 20), tokens.offsets())
        assertEquals(listOf(1, 1, 1, 1, 1), tokens.lengths())
        assertEquals(listOf(0, 0, 1, 1, 2), tokens.depths())
        assertEquals(1, tokens.stableFocusStartOffset)
        assertEquals(21, tokens.stableFocusEndOffset)
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

        assertTrue(tokens.isCapped)
        assertEquals(listOf(12, 20, 22, 30), tokens.offsets())
        assertEquals(20, tokens.stableFocusStartOffset)
        assertEquals(32, tokens.stableFocusEndOffset)
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
