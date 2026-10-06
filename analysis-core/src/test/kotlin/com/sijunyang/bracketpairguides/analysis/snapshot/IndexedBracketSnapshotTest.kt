package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class IndexedBracketSnapshotTest {
    @Test
    fun sharedIndexesKeepAnIndependentPairMemoForEachQueryView() {
        val indexes = indexes()
        val firstEditor = indexes.newSnapshot(stamp(), BraceMatcherAvailability.AVAILABLE)
        val secondEditor = indexes.newSnapshot(stamp(), BraceMatcherAvailability.AVAILABLE)
        val firstPair = checkNotNull(firstEditor.activePairAt(1))
        val secondPair = checkNotNull(secondEditor.activePairAt(1))
        assertThat(firstEditor.activePairAt(2)).isSameAs(firstPair)
        assertThat(secondEditor.activePairAt(2)).isSameAs(secondPair)
        assertThat(firstPair).isEqualTo(secondPair).isNotSameAs(secondPair)
        assertThat(firstEditor.activePairAt(0)).isNull()
        assertThat(firstEditor.activePairAt(4)).isNull()
    }

    @Test
    fun readOnlyTokenQueriesPreserveCappingAndRejectAccessOutsideTheWindow() {
        val result = indexes().newSnapshot(stamp(), BraceMatcherAvailability.AVAILABLE)
        val window = result.visibleTokens(0, 4, 3, 1)
        assertThat(window.isCapped).isTrue()
        assertThat(window.size).isEqualTo(1)
        assertThat(window.offsetAt(0)).isEqualTo(3)
        assertThat(window.lengthAt(0)).isEqualTo(1)
        assertThat(window.depthAt(0)).isZero()
        assertThatThrownBy { window.offsetAt(1) }.isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThatThrownBy { result.visibleTokens(0, 4, 3, 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun stamp() = AnalysisStamp(
        documentStamp = 7,
        fileType = Any(),
        coverage = AnalysisCoverage(tokens = true, activePair = true, guidePosition = false),
        disabledLanguageIds = emptySet(),
        tabSize = 4,
        highlighter = Any(),
    )

    private fun indexes(): BracketIndexes {
        val draft = PairTable.draft()
        draft.accept(0, 1, 3, 1, 0, 0, 0)
        return (
            SnapshotCalculation.prepare(
                AnalysisCoverage(tokens = true, activePair = true, guidePosition = false),
                DocumentBracketRecognition.Complete(draft.freeze(), BraceMatcherAvailability.AVAILABLE),
                documentLength = 4,
                documentLineCount = 1,
                checkCanceled = {},
            ).finish(null) as CalculatedAnalysis.Available
            ).indexes
    }
}
