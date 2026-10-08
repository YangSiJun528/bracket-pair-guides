package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.editor.Editor
import kotlin.coroutines.CoroutineContext

/** New test-only common contract. Opaque owners never become production interfaces. */
interface ComparisonHost : AutoCloseable {
    val implementation: String
    fun newReads(): ReadRecorder
    suspend fun analyze(editor: Editor, mode: String): AnalysisHandle
    suspend fun repair(editor: Editor, result: AnalysisHandle, offset: Int, exact: Boolean): RepairShape?
    suspend fun capture(editor: Editor, mode: String): CaptureHandle
    fun execution(editor: Editor, mode: String, reads: ReadRecorder, onPublication: () -> Unit): ExecutionHandle
    suspend fun native(
        editor: Editor,
        result: AnalysisHandle,
        pairOffset: Int,
        caret: Int,
        showVertical: Boolean,
    ): NativeShape
}
interface ReadRecorder {
    val context: CoroutineContext
    val nativePhase: String?

    /** Listener may only update atomic evidence; external writer owns SDK dispatch. */
    fun onNativeProgress(listener: ((String, Int) -> Unit)?)
    fun reset()
    fun requests(): List<ReadRequest>
    fun snapshot(): List<ReadSample>
}
data class ReadRequest(
    val requestId: Long,
    val phase: String,
    val requestedNanos: Long,
    val threadId: Long,
    val edt: Boolean,
    val readAllowed: Boolean,
)
data class ReadSample(
    val requestId: Long,
    val attemptId: Long,
    val phase: String,
    val requestedNanos: Long,
    val enteredNanos: Long,
    val exitedNanos: Long,
    val threadId: Long,
    val edt: Boolean,
    val readAllowed: Boolean,
    val allocatedBytes: Long?,
    val completed: Boolean,
    val failureClass: String?,
)
interface AnalysisHandle {
    val payload: Any
    fun shape(): ResultShape
    fun sample(offset: Int): PairShape?
}
data class ResultShape(
    val available: Boolean,
    val limit: String?,
    val coverage: String,
    val matcher: String,
    val tokens: Int,
    val tokenChecksum: Long,
    val capped: Boolean,
)
data class PairShape(
    val open: Int,
    val openLength: Int,
    val openLine: Int,
    val close: Int,
    val closeLength: Int,
    val closeLine: Int,
    val depth: Int,
)
data class RepairShape(val pair: PairShape, val guideColumn: Int)
data class NativeShape(val available: Boolean, val directMarkers: Int, val scopeMarkers: Int)
interface CaptureHandle : AutoCloseable {
    /** Retained deliberately during release probes; individual returned payloads must not retain SDK inputs. */
    val retainedInput: Any
    suspend fun next(offset: Int): CapturedChunk
}
data class CapturedChunk(val payload: Any, val nextOffset: Int, val exhausted: Boolean, val size: Int)
interface ExecutionHandle : AutoCloseable {
    fun request()
    fun markupCount(): Int
    fun refresh()
    fun markup(): List<Any>
    fun workerActive(): Boolean
}
