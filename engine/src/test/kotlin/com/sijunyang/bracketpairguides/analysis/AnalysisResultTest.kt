package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.api.AnalysisRevision
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.analysis.index.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.index.BracketTokenIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisResultTest {
    @Test
    fun `active-pair query hides index references`() {
        val outer = pair(open = 0, close = 90, depth = 0)
        val inner = pair(open = 20, close = 40, depth = 1)
        val snapshot = snapshot(listOf(outer, inner))

        assertSame(outer, snapshot.activePairAt(10))
        assertSame(inner, snapshot.activePairAt(30))
        assertNull(snapshot.activePairAt(100))
    }

    @Test
    fun `visible token view preserves sorted metadata without token allocations`() {
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

    @Test
    fun `capped token view is centered and publishes a stable focus envelope`() {
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

    private fun snapshot(pairs: List<BracketPair>): AnalysisSnapshot = AnalysisSnapshot(
        revision = AnalysisRevision(
            documentStamp = 1L,
            tabSize = 4,
            highlighterIdentity = 1,
            fileType = PlainTextFileType.INSTANCE,
            capabilities = AnalysisCapabilities(
                tokens = true,
                activePair = true,
                guidePosition = false,
            ),
            disabledLanguageIds = emptySet(),
        ),
        pairs = pairs,
        tokenIndex = BracketTokenIndex.build(pairs),
        activeIndex = ActiveBracketPairIndex.build(pairs),
        positionIndex = null,
    )

    private fun pair(open: Int, close: Int, depth: Int): BracketPair = BracketPair(
        openOffset = open,
        openTokenLength = 1,
        closeOffset = close,
        closeTokenLength = 1,
        depth = depth,
        openLine = 0,
        closeLine = 0,
    )

    private fun com.sijunyang.bracketpairguides.analysis.api.VisibleTokens.offsets(): List<Int> =
        List(size, ::offsetAt)

    private fun com.sijunyang.bracketpairguides.analysis.api.VisibleTokens.lengths(): List<Int> =
        List(size, ::lengthAt)

    private fun com.sijunyang.bracketpairguides.analysis.api.VisibleTokens.depths(): List<Int> =
        List(size, ::depthAt)
}
