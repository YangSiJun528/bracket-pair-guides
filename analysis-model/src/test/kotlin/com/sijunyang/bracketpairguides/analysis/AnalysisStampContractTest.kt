package com.sijunyang.bracketpairguides.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class AnalysisStampContractTest {
    private val fileType = Any()
    private val highlighter = Any()
    private val full = AnalysisCoverage(tokens = true, activePair = true, guidePosition = true)
    private val tokens = AnalysisCoverage(tokens = true, activePair = false, guidePosition = false)

    @Test
    fun languageSelectionIsAnOwnedImmutableCopy() {
        val disabled = mutableSetOf("XML")
        val captured = stamp(disabled = disabled)
        disabled.clear()
        assertThat(captured.covers(stamp(disabled = setOf("XML")))).isTrue()
        assertThat(captured.covers(stamp(disabled = emptySet()))).isFalse()
    }

    @Test
    fun coverageNarrowingPreservesSourceIdentityAndRejectsExpansion() {
        val captured = stamp()
        assertThat(captured.withCoverage(full)).isSameAs(captured)
        val narrowed = captured.withCoverage(tokens)
        assertThat(captured.covers(narrowed)).isTrue()
        assertThat(narrowed.covers(captured)).isFalse()
        assertThat(narrowed.matchesCapturedSource(7, highlighter, fileType)).isTrue()
        assertThatThrownBy { narrowed.withCoverage(full) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun layoutOnlyAffectsCoverageThatRequiresGuides() {
        val captured = stamp(tabSize = 4)
        assertThat(captured.covers(stamp(coverage = tokens, tabSize = 8))).isTrue()
        assertThat(captured.covers(stamp(tabSize = 8))).isFalse()
        assertThat(captured.matchesCurrent(7, highlighter, fileType, tokens, emptySet(), 8)).isTrue()
        assertThat(captured.matchesCurrent(7, highlighter, fileType, full, emptySet(), 8)).isFalse()
    }

    @Test
    fun equalHostObjectsCannotSubstituteForCapturedIdentity() {
        val firstType = String(charArrayOf('x'))
        val secondType = String(charArrayOf('x'))
        val firstHighlighter = String(charArrayOf('y'))
        val secondHighlighter = String(charArrayOf('y'))
        val captured = AnalysisStamp(7, firstType, full, emptySet(), 4, firstHighlighter)
        assertThat(firstType).isEqualTo(secondType)
        assertThat(firstHighlighter).isEqualTo(secondHighlighter)
        assertThat(captured.covers(AnalysisStamp(7, secondType, full, emptySet(), 4, firstHighlighter))).isFalse()
        assertThat(captured.covers(AnalysisStamp(7, firstType, full, emptySet(), 4, secondHighlighter))).isFalse()
        assertThat(captured.matchesCapturedSource(8, firstHighlighter, firstType)).isFalse()
    }

    private fun stamp(coverage: AnalysisCoverage = full, disabled: Set<String> = emptySet(), tabSize: Int = 4) =
        AnalysisStamp(7, fileType, coverage, disabled, tabSize, highlighter)
}
