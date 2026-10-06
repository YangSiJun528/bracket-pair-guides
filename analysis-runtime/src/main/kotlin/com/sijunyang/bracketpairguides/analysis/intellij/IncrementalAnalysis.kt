package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.guide.GuidePositionIndex
import com.sijunyang.bracketpairguides.analysis.guide.LineIndentation
import com.sijunyang.bracketpairguides.analysis.matchesCapturedSource
import com.sijunyang.bracketpairguides.analysis.matchesCurrent
import com.sijunyang.bracketpairguides.analysis.pairing.BracketGroupId
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionLimits
import com.sijunyang.bracketpairguides.analysis.pairing.BracketRecognitionRefusal
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBracketRecognition
import com.sijunyang.bracketpairguides.analysis.pairing.PairCapacityReached
import com.sijunyang.bracketpairguides.analysis.pairing.PairCollection
import com.sijunyang.bracketpairguides.analysis.pairing.TokenKind
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import com.sijunyang.bracketpairguides.analysis.snapshot.CalculatedAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.SnapshotCalculation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.LinkedHashMap

/** Owns one sequential capture/compute workflow; all platform reads stay inside capture(). */
internal class IncrementalAnalysis(
    private val input: AnalysisInput,
    private val epoch: AnalysisReadEpoch,
    private val checkCanceled: () -> Unit,
) {
    private val textCapture = DocumentTextCapture(CONTINUED_PREFIX_CHARACTERS)
    private var attemptEpoch = 0L
    private var captureObserver: AnalysisCaptureObserver? = null

    suspend fun calculate(): CalculatedAnalysis? {
        captureObserver = currentCoroutineContext()[AnalysisCaptureObserver]
        val application = ApplicationManager.getApplication()
        check(!application.isDispatchThread && !application.isReadAccessAllowed) {
            "Incremental analysis must start on an independent worker without read access"
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            checkCanceled()
            attemptEpoch = epoch.current
            try {
                return calculateAttempt()
            } catch (_: CaptureInvalidated) {
                // A document/highlighter/layout change requires a new caller request. An unrelated
                // write only invalidates chunk consistency, so restart with the same captured input.
                if (!observedRead(AnalysisCaptureObserver.RETRY_VALIDATION) { isCurrent() }) return null
                yield()
            }
        }
    }

    private suspend fun calculateAttempt(): CalculatedAnalysis {
        val source = capture(AnalysisCaptureObserver.INITIAL_STATE) {
            Source(BracketTokenCapture(input), input.editor.document.textLength, input.editor.document.lineCount)
        }
        val recognition = if (input.coverage.pairs) {
            recognize(source.tokens)
        } else {
            DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.UNDETERMINED)
        }
        checkCanceled()
        val prepared = SnapshotCalculation.prepare(
            input.coverage,
            recognition,
            source.length,
            source.lines,
            checkCanceled,
        )
        val guides = prepared.guideLines?.let { guidePositions(it) }
        val calculated = prepared.finish(guides)
        capture(AnalysisCaptureObserver.FINAL_VALIDATION) {}
        return calculated
    }

    private suspend fun recognize(tokens: BracketTokenCapture): DocumentBracketRecognition {
        val pairs = PairCollection(BracketRecognitionLimits.completedPairs)
        val pairing = PairingMachine<TokenKind, BracketGroupId>().newSession(
            pairs,
            CancellationProbe(checkCanceled),
            BracketRecognitionLimits.MAXIMUM_PENDING_OPENS,
        )
        // Deterministic matcher answers may be reused, but cache size never depends on the
        // document's number of distinct comparisons. Eviction affects only work, not progress.
        val answers = LinkedHashMap<PairingMachine.RuleRequest<TokenKind, BracketGroupId>, Boolean>(16, 0.75f, true)
        var offset = 0
        try {
            while (true) {
                val batch =
                    capture(AnalysisCaptureObserver.TOKENS) { tokens.capture(offset, checkCanceled = checkCanceled) }
                if (!batch.sourceAvailable) {
                    return DocumentBracketRecognition.Complete(PairTable.empty(), BraceMatcherAvailability.UNDETERMINED)
                }
                for (index in 0 until batch.size) {
                    // Match the capture/core traversal checkpoint interval. Recovery loops and
                    // every externally supplied compatibility answer also check cancellation.
                    if (index and CANCELLATION_MASK == 0) checkCanceled()
                    var step = batch.begin(index, pairing)
                    while (step == PairingMachine.Step.NEEDS_RULE) {
                        val request = pairing.ruleRequest()
                        val answer =
                            answers[request]
                                ?: capture(AnalysisCaptureObserver.COMPATIBILITY) { tokens.matches(request) }.also {
                                    answers[request] = it
                                    if (answers.size > MAXIMUM_CACHED_RULES) {
                                        val oldest = answers.entries.iterator()
                                        oldest.next()
                                        oldest.remove()
                                    }
                                }
                        step = pairing.resume(answer)
                    }
                    if (step == PairingMachine.Step.PENDING_CAPACITY) {
                        return DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PENDING_OPEN_CAPACITY)
                    }
                }
                if (batch.end) {
                    return DocumentBracketRecognition.Complete(
                        checkNotNull(pairs.authoritativePairs()),
                        batch.matcherAvailability,
                    )
                }
                offset = batch.nextOffset
            }
        } catch (_: PairCapacityReached) {
            return DocumentBracketRecognition.Unavailable(BracketRecognitionRefusal.PAIR_CAPACITY)
        }
    }

    private suspend fun guidePositions(lines: IntRange): GuidePositionIndex {
        val builder = checkNotNull(GuidePositionIndex.builder(lines.first, lines.last - lines.first + 1, checkCanceled))
        var firstLine = lines.first
        while (firstLine <= lines.last) {
            val afterLine = minOf(firstLine.toLong() + LINES_PER_CAPTURE, lines.last.toLong() + 1).toInt()
            val prefixes = capture(AnalysisCaptureObserver.GUIDE_PREFIX) {
                val document = input.editor.document
                (firstLine until afterLine).map { line ->
                    val start = document.getLineStartOffset(line)
                    val end = document.getLineEndOffset(line)
                    val after = minOf(start.toLong() + INITIAL_PREFIX_CHARACTERS, end.toLong()).toInt()
                    LinePrefix(textCapture.copy(document, start, after), after, end)
                }
            }
            for (prefix in prefixes) {
                val indentation = LineIndentation(input.stamp.tabSize, checkCanceled)
                var after = prefix.after
                indentation.append(prefix.text, endOfLine = after == prefix.lineEnd)
                while (!indentation.isComplete) {
                    val next = minOf(after.toLong() + CONTINUED_PREFIX_CHARACTERS, prefix.lineEnd.toLong()).toInt()
                    val text = capture(AnalysisCaptureObserver.GUIDE_CONTINUATION) {
                        textCapture.copy(input.editor.document, after, next)
                    }
                    after = next
                    indentation.append(text, endOfLine = after == prefix.lineEnd)
                }
                builder.append(indentation.column)
            }
            firstLine = afterLine
        }
        return builder.seal()
    }

    private suspend fun <T> capture(phase: Int, action: () -> T): T = observedRead(phase) {
        // Every chunk checks mutable source identity. Pure computation always uses the captured
        // tab size, so repeated code-style lookup cannot improve chunk consistency. Validate the
        // current layout at admission and completion; EDT publication checks current settings again.
        val layoutRequired = phase == AnalysisCaptureObserver.INITIAL_STATE ||
            phase == AnalysisCaptureObserver.FINAL_VALIDATION
        checkCanceled()
        if (epoch.current != attemptEpoch || !isCurrent(layoutRequired)) throw CaptureInvalidated()
        val result = action()
        checkCanceled()
        if (epoch.current != attemptEpoch || !isCurrent(layoutRequired)) throw CaptureInvalidated()
        result
    }

    private suspend fun <T> observedRead(phase: Int, action: () -> T): T {
        val observer = captureObserver ?: return readAction(action)
        val requestId = observer.requested(phase)
        return readAction {
            val attemptId = observer.entered(requestId, phase)
            var completed = false
            var failure: Throwable? = null
            try {
                val result = action()
                completed = true
                result
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                observer.exited(attemptId, completed, failure)
            }
        }
    }

    private fun isCurrent(layoutRequired: Boolean = true): Boolean =
        !input.editor.isDisposed && input.editor.project?.isDisposed != true &&
            if (layoutRequired) {
                input.stamp.matchesCurrent(input.editor, input.fileType, input.coverage, input.disabledLanguageIds)
            } else {
                input.stamp.matchesCapturedSource(input.editor, input.fileType)
            }

    private class CaptureInvalidated : RuntimeException(null, null, false, false)
    private class Source(val tokens: BracketTokenCapture, val length: Int, val lines: Int)
    private class LinePrefix(val text: String, val after: Int, val lineEnd: Int)

    private companion object {
        const val CANCELLATION_MASK = 0xFF
        const val MAXIMUM_CACHED_RULES = 2_048
        const val LINES_PER_CAPTURE = 128
        const val INITIAL_PREFIX_CHARACTERS = 128
        const val CONTINUED_PREFIX_CHARACTERS = 4_096
    }
}
