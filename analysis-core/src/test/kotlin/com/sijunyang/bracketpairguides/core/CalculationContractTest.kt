package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.core.input.RetryCapture
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenCollector
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.AnalysisLimit
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.model.result.BracketView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException

class CalculationContractTest {
    private val full = AnalysisCoverage(true, true, true)
    private val tokens = AnalysisCoverage(true, false, false)

    @Test fun nestingQueriesAgreeWithAnIndependentBalancedExpressionOracle() = runBlocking<Unit> {
        val random = Random(197)
        repeat(40) {
            val stack = ArrayList<Int>()
            val pairs = ArrayList<Pair<Int, Int>>()
            val text = buildString {
                repeat(40) {
                    if (stack.isEmpty() || random.nextBoolean()) {
                        stack.add(length)
                        append('(')
                    } else {
                        pairs.add(stack.removeAt(stack.lastIndex) to length)
                        append(')')
                    }
                }
                while (stack.isNotEmpty()) {
                    pairs.add(stack.removeAt(stack.lastIndex) to length)
                    append(')')
                }
            }
            val result = available(RecordedInput(text, chunk = 7))
            for (offset in 0..text.length) {
                val expected = pairs.filter { (open, close) ->
                    open < offset && offset <= close
                }.maxByOrNull { it.first }
                assertThat(
                    result.view.activePairAt(offset)?.let {
                        it.openOffset to it.closeOffset
                    },
                ).isEqualTo(expected)
            }
            val window = result.view.visibleTokens(OffsetRange(0, text.length), 0, 2048)
            assertThat(window.size).isEqualTo(text.length)
            assertThat((0 until window.size).map(window::offsetAt)).containsExactlyElementsOf(text.indices.toList())
        }
    }

    @Test fun capturePartitionDoesNotChangeQueriesOrGuideGeometry() = runBlocking<Unit> {
        val text = "{\n\t  [\n      ()\n  ]\n}"
        val results = listOf(1, 3, 17, 512).map { available(RecordedInput(text, chunk = it)).view }
        for (offset in 0..text.length) {
            assertThat(results.map { it.activePairAt(offset) }.distinct()).hasSize(1)
            val pair = results.first().activePairAt(offset)
            if (pair != null) assertThat(results.map { it.guideFor(pair) }.distinct()).hasSize(1)
        }
        val outer = checkNotNull(results.first().activePairAt(1))
        assertThat(results.first().guideFor(outer)?.guideColumn).isZero()
    }

    @Test fun tokenWindowsAreBoundedAndOwnNoMutableInputStorage() = runBlocking<Unit> {
        val input = RecordedInput("()".repeat(2000))
        val result = available(input)
        val window = result.view.visibleTokens(OffsetRange(0, input.text.length), 2000, 17)
        assertThat(window.size).isEqualTo(17)
        assertThat(window.isCapped).isTrue()
        assertThatThrownBy { window.depthAt(17) }.isInstanceOf(IndexOutOfBoundsException::class.java)
        val stable = (0 until window.size).map(window::offsetAt)
        available(input) // reinitializes the input's token identity maps
        assertThat((0 until window.size).map(window::offsetAt)).isEqualTo(stable)
    }

    @Test fun anUnavailableLaterChunkDiscardsAnEarlierPairPrefix() = runBlocking<Unit> {
        val input = RecordedInput("()()", chunk = 2)
        input.onTokens = { offset -> if (offset > 0) input.unavailable = true }
        val result = available(input)
        assertThat(result.matcherAvailability).isEqualTo(BraceMatcherAvailability.UNDETERMINED)
        assertThat(result.view.activePairAt(1)).isNull()
        assertThat(result.view.visibleTokens(OffsetRange(0, 4), 1, 10).size).isZero()
    }

    @Test fun unrelatedWriteRetriesDiscardAllPartialAttemptState() = runBlocking<Unit> {
        val input = RecordedInput("()()", chunk = 2)
        var retried = false
        input.onTokens = { offset ->
            if (offset > 0 && !retried) {
                retried = true
                throw RetryCapture()
            }
        }
        val result = available(input)
        assertThat(input.attempts).isEqualTo(2)
        assertThat(result.view.visibleTokens(OffsetRange(0, 4), 1, 10).size).isEqualTo(4)
    }

    @Test fun hostSourceChangesPropagateInsteadOfBeingRetriedOrPublished() = runBlocking<Unit> {
        val sourceChanged = IllegalStateException("source changed")
        val input = RecordedInput("()")
        input.onValidate = { throw sourceChanged }
        var observed: Throwable? = null
        try {
            available(input)
        } catch (error: Throwable) {
            observed = error
        }
        assertThat(observed).isSameAs(sourceChanged)
        assertThat(input.attempts).isEqualTo(1)
    }

    @Test fun actualCoroutineCancellationStopsAnOutstandingInputRead() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val input = RecordedInput("()")
        input.onTokens = {
            reached.complete(Unit)
            awaitCancellation()
        }
        val task = async { available(input) }
        reached.await()
        task.cancelAndJoin()
        assertThat(task.isCancelled).isTrue()
    }

    @Test fun cancellationAtPureCheckpointsDoesNotBecomeARefusalOrSuccess() = runBlocking<Unit> {
        for (cutoff in listOf(1, 2, 5, 10, 30)) {
            var checks = 0
            val cancellation = CancellationException("checkpoint")
            val control = object : CalculationControl {
                override fun checkCanceled() {
                    if (++checks == cutoff) throw cancellation
                }
                override suspend fun yieldWork() = yield()
            }
            var observed: Throwable? = null
            try {
                BracketCalculator().analyze(RecordedInput("()".repeat(2000)), full, control)
            } catch (
                error: Throwable,
            ) {
                observed = error
            }
            assertThat(observed).isSameAs(cancellation)
        }
    }

    @Test fun completedPairCapacityNeverPublishesAPartialGraph() = runBlocking<Unit> {
        val result = BracketCalculator().analyze(RecordedInput("()".repeat(100001)), tokens, coroutineControl())
        assertThat(result).isInstanceOf(AnalysisResult.Unavailable::class.java)
        assertThat((result as AnalysisResult.Unavailable).limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
    }

    @Test fun pendingCapacityAppliesBeforeAnotherOpenCanBeRetained() = runBlocking<Unit> {
        val result = BracketCalculator().analyze(RecordedInput("(".repeat(50001)), full, coroutineControl())
        assertThat((result as AnalysisResult.Unavailable).limit).isEqualTo(AnalysisLimit.PENDING_OPEN_CAPACITY)
    }

    @Test fun guideCapacityPreservesExactLowerFacetsWithoutCapturingGuideText() = runBlocking<Unit> {
        val input = RecordedInput("{\n" + "\n".repeat(1_032_192) + "}")
        val result = available(input)
        assertThat(result.limit).isEqualTo(AnalysisLimit.GUIDE_CAPACITY)
        assertThat(result.coverage).isEqualTo(AnalysisCoverage(true, true, false))
        assertThat(result.view.activePairAt(1)).isNotNull()
        assertThat(result.view.visibleTokens(OffsetRange(0, input.text.length), 1, 10).size).isEqualTo(2)
        assertThat(input.prefixRequests).isEmpty()
    }

    @Test fun longWhitespaceContinuesInBoundedReadsAndTiesChooseEarliestLine() = runBlocking<Unit> {
        val input = RecordedInput("{\n" + " ".repeat(9000) + "x\n  y\n  }")
        val result = available(input)
        assertThat(input.continuationReads).isEqualTo(3)
        val guide = checkNotNull(result.view.guideFor(checkNotNull(result.view.activePairAt(1))))
        assertThat(guide.guideColumn).isEqualTo(2)
        assertThat(guide.anchorLine).isEqualTo(2)
    }

    @Test fun repairConsumesPrefixThroughContentButNotUnusedSuffix() = runBlocking<Unit> {
        val input = RecordedInput("{\nx" + "z".repeat(100000) + "\n}")
        val pair = BracketPair(0, 1, input.text.lastIndex, 1, 0, 0, 2)
        val guide = BracketCalculator().repair(input, RepairRequest(pair, exact = true), coroutineControl())
        assertThat(guide?.guideColumn).isZero()
        assertThat(guide?.anchorLine).isEqualTo(1)
        assertThat(input.continuationReads).isZero()
    }

    @Test fun exactRepairRefusalLeavesGeometryAbsent() = runBlocking<Unit> {
        val input = RecordedInput("{\n" + " ".repeat(32769) + "x\n}")
        val pair = BracketPair(0, 1, input.text.lastIndex, 1, 0, 0, 2)
        assertThat(BracketCalculator().repair(input, RepairRequest(pair, true), coroutineControl())).isNull()
    }

    @Test fun sharedStorageDoesNotSharePairMemosAndOldCompletionCannotRollbackCache() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val newest = available(RecordedInput("(())", revision = 2), calculator)
        val older = available(RecordedInput("()", revision = 1), calculator)
        val sameNewest = available(RecordedInput("(())", revision = 2), calculator)

        // Storage identity is the resource contract under test, never a production query getter.
        fun storage(view: BracketView): Any = view.javaClass.getDeclaredField("indexes").let { field ->
            field.isAccessible =
                true
            field.get(view)
        }
        assertThat(storage(newest.view)).isSameAs(storage(sameNewest.view))
        assertThat(storage(older.view)).isNotSameAs(storage(newest.view))
        val first = newest.view.activePairAt(2)
        assertThat(first).isEqualTo(sameNewest.view.activePairAt(2)).isNotSameAs(sameNewest.view.activePairAt(2))
        assertThat(newest.view.activePairAt(2)).isSameAs(first)
    }

    @Test fun guideReuseSeparatesTabLayoutsWithinOneTextRevision() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val text = "{\n\tx\n\t}"
        val four = available(RecordedInput(text, tabSize = 4), calculator)
        val eight = available(RecordedInput(text, tabSize = 8), calculator)
        assertThat(four.view.guideFor(checkNotNull(four.view.activePairAt(1)))?.guideColumn).isEqualTo(4)
        assertThat(eight.view.guideFor(checkNotNull(eight.view.activePairAt(1)))?.guideColumn).isEqualTo(8)
    }

    @Test fun captureCollectorsAreRevokedAndCannotAlterASealedBatch() {
        lateinit var captured: TokenCollector
        val kind = TokenKind()
        val group = TokenGroup(0, 0)
        val batch = TokenBatch.capture { sink ->
            captured = sink
            sink.append(kind, group, null, false, TokenRole.OPEN, StructuralRole.NONE, 0, 1, 0)
            TokenBatch.End(1, true, 1, BraceMatcherAvailability.AVAILABLE)
        }
        assertThat(batch.size).isEqualTo(1)
        assertThatThrownBy { captured.append(kind, group, null, false, TokenRole.CLOSE, StructuralRole.NONE, 1, 1, 0) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test fun exactPairAndPendingBoundariesRemainAccepted() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val closed = calculator.analyze(RecordedInput("()".repeat(100000)), tokens, coroutineControl())
        assertThat(closed).isInstanceOf(AnalysisResult.Available::class.java)
        val pending = calculator.analyze(RecordedInput("(".repeat(50000), revision = 2), tokens, coroutineControl())
        assertThat(pending).isInstanceOf(AnalysisResult.Available::class.java)
        assertThat((pending as AnalysisResult.Available).view.visibleTokens(OffsetRange(0, 50000), 0, 10).size).isZero()
    }

    @Test fun absenceOfRequestedFacetsDoesNotReadTokensOrLines() = runBlocking<Unit> {
        val input = RecordedInput("{\n x\n}")
        val result = BracketCalculator().analyze(input, AnalysisCoverage(false, false, false), coroutineControl())
        assertThat(result).isInstanceOf(AnalysisResult.Available::class.java)
        assertThat(input.tokenCaptures).isZero()
        assertThat(input.prefixRequests).isEmpty()
    }

    @Test fun aLateConcurrentAttemptCannotReplaceTheNewerCanonicalStorage() = runBlocking<Unit> {
        val calculator = BracketCalculator()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldInput = RecordedInput("()", revision = 1)
        oldInput.onValidate = {
            reached.complete(Unit)
            release.await()
        }
        val older = async { available(oldInput, calculator) }
        reached.await()
        val newest = available(RecordedInput("(())", revision = 2), calculator)
        release.complete(Unit)
        older.await()
        val newestAgain = available(RecordedInput("(())", revision = 2), calculator)
        fun storage(view: BracketView): Any = view.javaClass.getDeclaredField("indexes").let {
            it.isAccessible = true
            it.get(view)
        }
        assertThat(storage(newest.view)).isSameAs(storage(newestAgain.view))
    }

    @Test fun retryRemainsCancellableInsteadOfSpinningOnAnUnavailableReadLock() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val input = RecordedInput("()")
        input.onTokens = {
            reached.complete(Unit)
            throw RetryCapture()
        }
        val task = async { available(input) }
        reached.await()
        task.cancelAndJoin()
        assertThat(task.isCancelled).isTrue()
        assertThat(input.attempts).isGreaterThanOrEqualTo(1)
    }

    @Test fun aBatchCannotRetainMoreThanItsReadBudget() {
        val kind = TokenKind()
        val group = TokenGroup(0, 0)
        assertThatThrownBy {
            TokenBatch.capture { sink ->
                repeat(513) { sink.append(kind, group, null, false, TokenRole.OPEN, StructuralRole.NONE, it, 1, 0) }
                TokenBatch.End(513, true, 513, BraceMatcherAvailability.AVAILABLE)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private suspend fun available(
        input: RecordedInput,
        calculator: BracketCalculator = BracketCalculator(),
    ): AnalysisResult.Available = calculator.analyze(input, full, coroutineControl()) as AnalysisResult.Available
}
