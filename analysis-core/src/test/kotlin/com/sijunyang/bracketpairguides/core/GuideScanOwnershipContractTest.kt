package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.PrefixBatch
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.RetryCapture
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class GuideScanOwnershipContractTest {
    @Test fun fullAnalysisUsesBatchesWhileRepairUsesOnlySingleLineCaptures() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val source = RecordedInput("{\n  x\n  }")
        val batchOnly = object : BracketInput by source {
            override suspend fun initialPrefix(line: Int): PrefixChunk =
                error("Full analysis must batch initial prefixes")
        }
        val result = calculator.analyze(
            batchOnly,
            AnalysisCoverage(true, true, true),
            coroutineControl(),
        ) as AnalysisResult.Available
        val pair = checkNotNull(result.view.activePairAt(1))
        assertThat(source.prefixRequests).containsExactly(1 to 2)
        source.prefixRequests.clear()
        val singleOnly = object : BracketInput by source {
            override suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch =
                error("Repair must capture a single prefix directly")
        }
        val repaired = calculator.repair(singleOnly, RepairRequest(pair, true), coroutineControl())
        assertThat(repaired).isEqualTo(result.view.guideFor(pair))
        assertThat(source.prefixRequests).containsExactly(1 to 1, 2 to 1)
    }

    @Test fun prefixBatchesSnapshotCallerListsAtEveryStorageSize() {
        for (size in listOf(0, 1, 3, 128)) {
            val original = MutableList(size) { PrefixChunk("x", it + 1, it + 1) }
            val expected = original.toList()
            val batch = PrefixBatch(0, original)
            original.clear()
            assertThat(batch.prefixes).containsExactlyElementsOf(expected)
            if (size > 0) {
                assertThatThrownBy { (batch.prefixes as MutableList<PrefixChunk>)[0] = PrefixChunk("y", 1, 1) }
                    .isInstanceOf(UnsupportedOperationException::class.java)
            }
        }
    }

    @Test fun fullGuideScanningResetsAcrossLinesLateRetriesAndLaterCalls() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val first = RecordedInput("{\n        a\n\n\tb\n  c\n    }")
        var invalidated = false
        first.onValidate = {
            if (!invalidated) {
                invalidated = true
                throw RetryCapture()
            }
        }
        val result = calculator.analyze(
            first,
            AnalysisCoverage(true, true, true),
            coroutineControl(),
        ) as AnalysisResult.Available
        val pair = checkNotNull(result.view.activePairAt(1))
        assertThat(first.attempts).isEqualTo(2)
        assertThat(result.view.guideFor(pair)?.guideColumn).isEqualTo(2)
        assertThat(result.view.guideFor(pair)?.anchorLine).isEqualTo(4)
        val later = RecordedInput("{\n      z\n      }", revision = 2)
        val next = calculator.analyze(
            later,
            AnalysisCoverage(true, true, true),
            coroutineControl(),
        ) as AnalysisResult.Available
        val nextPair = checkNotNull(next.view.activePairAt(1))
        assertThat(next.view.guideFor(nextPair)?.guideColumn).isEqualTo(6)
        assertThat(next.view.guideFor(nextPair)?.anchorLine).isEqualTo(1)
        // Immutable already-published storage must also survive the later attempt.
        assertThat(result.view.guideFor(pair)?.guideColumn).isEqualTo(2)
    }

    @Test fun repairScanningResetsAcrossLinesLateRetriesAndLaterCalls() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val input = RecordedInput("{\n        a\n\n\tb\n  c\n    }")
        val pair = BracketPair(0, 1, input.text.lastIndex, 1, 0, 0, 5)
        var invalidated = false
        input.onValidate = {
            if (!invalidated) {
                invalidated = true
                throw RetryCapture()
            }
        }
        val repaired = calculator.repair(input, RepairRequest(pair, true), coroutineControl())
        assertThat(input.attempts).isEqualTo(2)
        assertThat(repaired?.guideColumn).isEqualTo(2)
        assertThat(repaired?.anchorLine).isEqualTo(4)
        val later = RecordedInput("{\n      z\n      }", revision = 2)
        val nextPair = BracketPair(0, 1, later.text.lastIndex, 1, 0, 0, 2)
        val next = calculator.repair(later, RepairRequest(nextPair, true), coroutineControl())
        assertThat(next?.guideColumn).isEqualTo(6)
        assertThat(next?.anchorLine).isEqualTo(1)
    }

    @Test fun provisionalRepairKeepsCloserThenOldAnchorThenEarliestTieOrder() = runBlocking<Unit> {
        val input = RecordedInput("{\n  a\n  b\n  }")
        val pair = BracketPair(0, 1, input.text.lastIndex, 1, 0, 0, 3)
        val guide = BracketCalculator().repair(input, RepairRequest(pair, false, 2), coroutineControl())
        assertThat(input.prefixRequests).containsExactly(3 to 1, 2 to 1, 1 to 1)
        assertThat(guide?.guideColumn).isEqualTo(2)
        assertThat(guide?.anchorLine).isEqualTo(1)
    }

    @Test fun exactRepairAcceptsTheLastAdmittedLineButRefusesOneMore() = runBlocking<Unit> {
        for (lineCount in listOf(256, 257)) {
            val input = RecordedInput("{\n" + "  x\n".repeat(lineCount - 1) + "  }")
            val pair = BracketPair(0, 1, input.text.lastIndex, 1, 0, 0, lineCount)
            val result = BracketCalculator().repair(input, RepairRequest(pair, true), coroutineControl())
            assertThat(input.prefixRequests).hasSize(256)
            if (lineCount == 256) {
                assertThat(result?.guideColumn).isEqualTo(2)
                assertThat(result?.anchorLine).isEqualTo(1)
            } else {
                assertThat(result).isNull()
            }
        }
    }
}
