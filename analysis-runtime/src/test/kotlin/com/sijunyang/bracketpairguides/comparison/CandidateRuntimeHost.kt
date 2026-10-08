package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisReadEpoch
import com.sijunyang.bracketpairguides.runtime.capture.BracketTokenCapture
import com.sijunyang.bracketpairguides.runtime.capture.EditorSource
import com.sijunyang.bracketpairguides.runtime.capture.SourceIdentity
import com.sijunyang.bracketpairguides.runtime.nativeproof.NativeGuideConflictDetector
import com.sijunyang.bracketpairguides.runtime.session.DocumentCalculation
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.NativeInterest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Test-only access to actual owned runtime paths; never shipped in a plugin archive. */
class CandidateRuntimeHost : ComparisonHost {
    override val implementation = "candidate-redesign"
    private var epoch: AnalysisReadEpoch? = null
    private val calculations = java.util.WeakHashMap<com.intellij.openapi.editor.Document, DocumentCalculation>()
    private fun epoch(): AnalysisReadEpoch = epoch ?: AnalysisReadEpoch().also { epoch = it }
    override fun newReads(): ReadRecorder = object : ReadRecorder {
        private val recorder = CandidateReadMeasurements()
        override val context get() = recorder.context
        override val nativePhase get() = recorder.nativePhase
        override fun onNativeProgress(listener: ((String, Int) -> Unit)?) = recorder.onNativeProgress(listener)
        override fun reset() = recorder.reset()
        override fun requests() = recorder.requests().map {
            ReadRequest(it.requestId, it.phase, it.requestedNanos, it.threadId, it.edt, it.readAllowed)
        }
        override fun snapshot() = recorder.snapshot().map {
            ReadSample(
                it.requestId, it.attemptId, it.phase, it.requestedNanos, it.enteredNanos,
                it.exitedNanos, it.threadId, it.edt, it.readAllowed, it.allocatedBytes, it.completed, it.failureClass,
            )
        }
    }
    private data class Input(
        val source: EditorSource,
        val calculation: DocumentCalculation,
        val epoch: AnalysisReadEpoch,
        val control: CalculationControl,
    )
    private suspend fun input(editor: Editor, mode: String): Input {
        val context = currentCoroutineContext()
        val control = object : CalculationControl {
            override fun checkCanceled() {
                context.ensureActive()
                ProgressManager.checkCanceled()
            }
            override suspend fun yieldWork() = yield()
        }
        return withContext(kotlinx.coroutines.Dispatchers.EDT) {
            val epoch = epoch()
            val calculation = calculations.getOrPut(editor.document) { DocumentCalculation(editor.document) }
            val demand = GuideDemand(
                1,
                coverage(mode),
                emptySet(),
                true,
                1,
                null,
                GuideChange.CONTENT,
                NativeInterest(1, false),
            )
            Input(
                EditorSource(editor, SourceIdentity.capture(editor, demand), epoch, control, calculation),
                calculation,
                epoch,
                control,
            )
        }
    }
    override suspend fun analyze(editor: Editor, mode: String): AnalysisHandle {
        val input = input(editor, mode)
        val result = input.calculation.calculator.analyze(input.source, coverage(mode), input.control)
        return Result(result, editor.document.textLength)
    }
    override suspend fun repair(editor: Editor, result: AnalysisHandle, offset: Int, exact: Boolean): RepairShape? {
        val available = (result as Result).result as? AnalysisResult.Available ?: return null
        val pair = available.view.activePairAt(offset) ?: return null
        val input = input(editor, "all")
        return input.calculation.calculator.repair(
            input.source,
            RepairRequest(pair, exact, available.view.guideFor(pair)?.anchorLine),
            input.control,
        )?.let {
            RepairShape(it.pair.shape(), it.guideColumn)
        }
    }
    override suspend fun capture(editor: Editor, mode: String): CaptureHandle {
        val input = input(editor, mode)
        val adapter = readAction { BracketTokenCapture(input.source) }
        return object : CaptureHandle {
            private var closed = false
            override val retainedInput: Any get() = adapter
            override suspend fun next(offset: Int): CapturedChunk {
                check(!closed)
                val batch = readAction { adapter.capture(offset, 512, input.control::checkCanceled) }
                return CapturedChunk(batch, batch.nextOffset, batch.end, batch.size)
            }
            override fun close() {
                closed = true
            }
        }
    }
    override suspend fun native(
        editor: Editor,
        result: AnalysisHandle,
        pairOffset: Int,
        caret: Int,
        showVertical: Boolean,
    ): NativeShape {
        val available = (result as Result).result as? AnalysisResult.Available ?: return NativeShape(false, 0, 0)
        val pair = available.view.activePairAt(pairOffset) ?: return NativeShape(false, 0, 0)
        val resolve = withContext(kotlinx.coroutines.Dispatchers.EDT) {
            editor.caretModel.moveToOffset(caret)
            val epoch = epoch()
            NativeGuideConflictDetector.captureMarkerSources(editor, pair, showVertical, epoch)
        }
        val context = currentCoroutineContext()
        val sources = resolve {
            context.ensureActive()
            ProgressManager.checkCanceled()
        }
        return NativeShape(true, if (sources.direct) 1 else 0, if (sources.currentScope) 1 else 0)
    }
    override fun execution(
        editor: Editor,
        mode: String,
        reads: ReadRecorder,
        onPublication: () -> Unit,
    ): ExecutionHandle = error("Compose the UI-owned SDK measurement adapter in plugin sdkPerformance")
    override fun close() {
        epoch?.let(Disposer::dispose)
        epoch = null
        calculations.clear()
    }
    private fun coverage(mode: String) = when (mode) {
        "all" -> AnalysisCoverage(true, true, true)
        "tokens" -> AnalysisCoverage(true, false, false)
        else -> error("Unknown shared SDK mode: $mode")
    }
    private class Result(val result: AnalysisResult, val length: Int) : AnalysisHandle {
        override val payload: Any get() = result
        override fun shape(): ResultShape {
            val available = result as? AnalysisResult.Available
            val tokens = available?.view?.visibleTokens(OffsetRange(0, length), 0, Int.MAX_VALUE)
            var checksum = 0L
            if (tokens != null) {
                repeat(tokens.size) {
                    checksum = checksum * 31 + tokens.offsetAt(it)
                    checksum = checksum * 31 + tokens.lengthAt(it)
                    checksum = checksum * 31 + tokens.depthAt(it)
                }
            }
            val coverage = result.coverage
            val limit = when (val outcome = result) {
                is AnalysisResult.Available -> outcome.limit
                is AnalysisResult.Unavailable -> outcome.limit
            }
            return ResultShape(
                available != null,
                limit?.name,
                "${coverage.tokens}:${coverage.activePair}:${coverage.guidePosition}",
                result.matcherAvailability.name,
                tokens?.size ?: 0,
                checksum,
                tokens?.isCapped ?: false,
            )
        }
        override fun sample(offset: Int) = (result as? AnalysisResult.Available)?.view?.activePairAt(offset)?.shape()
    }
}
private fun BracketPair.shape() = PairShape(
    openOffset,
    openTokenLength,
    openLine,
    closeOffset,
    closeTokenLength,
    closeLine,
    depth,
)
