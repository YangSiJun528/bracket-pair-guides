package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class InputBoundaryContractTest {
    @Test fun earlyEndCannotPublishAnAuthoritativePrefix() = runBlocking<Unit> {
        rejects(input(10) { batch(next = 2, end = true) })
    }

    @Test fun aTokenCannotExtendPastItsCapturedBatchBoundary() = runBlocking<Unit> {
        rejects(input(10) { batch(next = 2, end = false, 0 to 3) })
    }

    @Test fun tokensWithinOneBatchCannotOverlap() = runBlocking<Unit> {
        rejects(input(10) { batch(next = 10, end = true, 0 to 2, 1 to 1) })
    }

    @Test fun aLaterBatchCannotReplayTokensBeforeItsRequestedBoundary() = runBlocking<Unit> {
        rejects(
            input(10) { offset ->
                if (offset == 0) batch(3, false, 0 to 1) else batch(10, true, 2 to 1)
            },
        )
    }

    @Test fun emptyBatchesCanAdvanceAcrossNonBracketLexerTokens() = runBlocking<Unit> {
        val source = input(10) { offset -> if (offset == 0) batch(3, false) else batch(10, true) }
        val result = calculate(source) as AnalysisResult.Available
        assertThat(result.view.visibleTokens(OffsetRange(0, 10), 0, 10).size).isZero()
    }

    @Test fun oneOverlongLexerTokenUsesOneVisitAndMaySpanMoreThan512Characters() = runBlocking<Unit> {
        assertThat(calculate(input(4096) { batch(4096, true, 0 to 4096) }))
            .isInstanceOf(AnalysisResult.Available::class.java)
    }

    @Test fun anEmptyDocumentCanFinishWithoutAdvancing() = runBlocking<Unit> {
        assertThat(calculate(input(0) { batch(0, true) })).isInstanceOf(AnalysisResult.Available::class.java)
    }

    private fun input(length: Int, capture: (Int) -> TokenBatch): BracketInput =
        object : BracketInput by RecordedInput("x".repeat(length)) {
            override suspend fun tokensAt(offset: Int) = capture(offset)
        }

    private fun batch(next: Int, end: Boolean, vararg tokens: Pair<Int, Int>): TokenBatch =
        TokenBatch.capture { collector ->
            for ((offset, length) in tokens) {
                collector.append(
                    TokenKind(), TokenGroup(0, 0), null,
                    false, TokenRole.OPEN, StructuralRole.NONE, offset, length, 0,
                )
            }
            TokenBatch.End(next, end, tokens.size, BraceMatcherAvailability.AVAILABLE)
        }

    private suspend fun calculate(input: BracketInput): AnalysisResult = BracketCalculator().analyze(
        input,
        AnalysisCoverage(true, true, false),
        coroutineControl(),
    )

    private suspend fun rejects(input: BracketInput) {
        var observed: Throwable? = null
        try {
            calculate(input)
        } catch (failure: Throwable) {
            observed = failure
        }
        assertThat(observed).isInstanceOf(IllegalArgumentException::class.java)
    }
}
