package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.lang.java.JavaLanguage
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.awaitPerformanceEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in adapter measurements; UI eligibility and notification publication are not measured. */
class NativeConflictMeasurementTest : BasePlatformTestCase() {
    fun testNativeConflictMeasurements() = verifyMeasurements()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyMeasurements() {
        if (!java.lang.Boolean.getBoolean("issue93.measure.native")) return
        val output = Path.of(System.getProperty("issue93.measure.native.output", "build/reports/issue93-native.jsonl"))
        output.parent?.let { Files.createDirectories(it) }
        val runId = UUID.randomUUID().toString()
        val warmups = Integer.getInteger("issue93.measure.warmups", 3).coerceAtLeast(0)
        val repeats = Integer.getInteger("issue93.measure.native.repeats", 5).coerceAtLeast(1)
        emit(
            output, "runId" to runId, "kind" to "environment",
            "ideBuild" to ApplicationInfo.getInstance().build.asString(),
            "javaVersion" to System.getProperty("java.version"),
            "scope" to "production captureMarkerSources adapter; UI eligibility/publication excluded",
            "readMetric" to "exact observer entered/exited for every actual read body; preparation separately tagged",
            "writeMetric" to "unstaged actual EDT no-op WriteAction request-to-acquisition",
            "hostEpochValidation" to true, "warmups" to warmups, "repeats" to repeats,
        )
        val ownedJob = SupervisorJob()
        val scope = CoroutineScope(ownedJob + Dispatchers.Default)
        try {
            for (corpus in corpora()) {
                myFixture.configureByText(corpus.filename, corpus.source)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                val editor = myFixture.editor
                editor.settings.isBlockCursor = false
                editor.caretModel.moveToOffset(corpus.openOffset)
                if (corpus.forceLazyPreparation) {
                    // Reuses the real platform lazy-language branch; marked explicitly in output.
                    // Fixture setup/PSI materialization is excluded from measured EDT capture.
                    ReadAction.run<RuntimeException> {
                        val block =
                            checkNotNull(
                                PsiTreeUtil.getParentOfType(
                                    myFixture.file.findElementAt(corpus.openOffset),
                                    PsiCodeBlock::class.java,
                                    false,
                                ),
                            )
                        block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
                    }
                }
                // Establish real token endpoints outside measured samples, on Default under readAction.
                val setup = scope.async {
                    readAction {
                        val context =
                            checkNotNull(
                                NativeBraceContext.compute(editor, myFixture.file, corpus.openOffset, false) {
                                    ProgressManager.checkCanceled()
                                },
                            )
                        val open = editor.highlighter.createIterator(corpus.openOffset)
                        val close = editor.highlighter.createIterator(context.navigationOffset - 1)
                        BracketPair(
                            openOffset = open.start,
                            openTokenLength = open.end - open.start,
                            closeOffset = close.start,
                            closeTokenLength = close.end - close.start,
                            depth = 0,
                            openLine = editor.document.getLineNumber(open.start),
                            closeLine = editor.document.getLineNumber(close.start),
                        )
                    }
                }
                PlatformTestUtil.waitWithEventsDispatching("native corpus setup", { setup.isCompleted }, 120)
                val pair = setup.getCompleted()
                emit(
                    output,
                    "runId" to runId,
                    "kind" to "corpus",
                    "corpus" to corpus.name,
                    "characters" to corpus.source.length,
                    "sha256Utf8" to sha256(corpus.source),
                    "forcedLazyLanguageBranch" to corpus.forceLazyPreparation,
                )
                for ((mode, offset) in corpus.carets) {
                    editor.caretModel.moveToOffset(offset)
                    repeat(warmups) {
                        val captured = NativeGuideConflictDetector.captureMarkerSources(editor, pair, true)
                        val worker = scope.async { captured { ProgressManager.checkCanceled() } }
                        PlatformTestUtil.waitWithEventsDispatching("native warmup", { worker.isCompleted }, 120)
                        worker.getCompleted()
                    }
                    val writers = if (corpus.forceLazyPreparation) {
                        listOf(
                            "none",
                            "late-traversal",
                            "late-lazy-lexer",
                        )
                    } else {
                        listOf("none", "late-traversal")
                    }
                    for (writer in writers) {
                        repeat(repeats) { iteration ->
                            measure(scope, output, runId, corpus.name, mode, pair, writer, iteration)
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
            PlatformTestUtil.waitWithEventsDispatching("native measurement shutdown", { ownedJob.isCompleted }, 120)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun measure(
        scope: CoroutineScope,
        output: Path,
        runId: String,
        corpus: String,
        mode: String,
        pair: BracketPair,
        writer: String,
        iteration: Int,
    ) {
        val app = ApplicationManager.getApplication()
        val modality = ModalityState.current()
        val withWriter = writer != "none"
        val queued = AtomicBoolean()
        val writerDone = AtomicBoolean(!withWriter)
        val active = AtomicBoolean(true)
        val queuedAt = AtomicLong()
        val requestedAt = AtomicLong()
        val acquiredAt = AtomicLong()
        val resolutionEnded = AtomicLong()
        val triggerPhase = AtomicInteger(-1)
        val triggerOperations = AtomicLong()
        val lexerAdvances = AtomicLong()
        val writerFailure = AtomicReference<Throwable?>()
        val bodies = ArrayList<Body>()
        val requests = HashMap<Long, Request>()
        val ids = AtomicLong()
        val traversalChunks = AtomicInteger()
        val currentPhase = AtomicInteger(-1)
        fun queueWrite(phase: Int, operations: Long) {
            if (!withWriter || !queued.compareAndSet(false, true)) return
            triggerPhase.set(phase)
            triggerOperations.set(operations)
            queuedAt.set(System.nanoTime())
            app.invokeLater({
                try {
                    if (active.get()) {
                        requestedAt.set(System.nanoTime())
                        WriteAction.run<RuntimeException> { acquiredAt.set(System.nanoTime()) }
                    }
                } catch (failure: Throwable) {
                    writerFailure.set(failure)
                } finally {
                    writerDone.set(true)
                }
            }, modality)
        }
        val observer = object : NativeCaptureObserver() {
            override fun requested(phase: Int): Long {
                val id = ids.incrementAndGet()
                requests[id] = Request(phase, System.nanoTime())
                return id
            }
            override fun entered(requestId: Long, phase: Int): Long {
                check(app.isReadAccessAllowed && !app.isDispatchThread)
                val id = ids.incrementAndGet()
                bodies += Body(id, requestId, phase, System.nanoTime())
                currentPhase.set(phase)
                return id
            }
            override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) {
                val now = System.nanoTime()
                bodies.last { it.id == attemptId }.apply {
                    exitedNs = now
                    this.completed = completed
                    this.failure = failure?.javaClass?.name
                }
                currentPhase.set(-1)
            }
            override fun progressed(phase: Int, ownedOperations: Int) {
                bodies.last().operations = ownedOperations
                val traversal = if (mode == "scope") STRUCTURAL_TRAVERSAL else DIRECT_TRAVERSAL
                if (writer == "late-traversal" && phase == traversal && traversalChunks.incrementAndGet() == 9) {
                    queueWrite(phase, ownedOperations.toLong())
                }
            }
        }
        // A test-only factory delegates the original lexer unchanged, observing a real late advance.
        val originalSyntax = if (writer == "late-lazy-lexer") {
            checkNotNull(
                SyntaxHighlighterFactory.getSyntaxHighlighter(
                    JavaLanguage.INSTANCE,
                    project,
                    myFixture.file.virtualFile,
                ),
            )
        } else {
            null
        }
        val factory = originalSyntax?.let { syntax ->
            object : SyntaxHighlighterFactory() {
                override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
                    object : SyntaxHighlighter by syntax {
                        override fun getHighlightingLexer(): Lexer = ObservedLexer(syntax.highlightingLexer) { lexer ->
                            val advances = lexerAdvances.incrementAndGet()
                            if (currentPhase.get() == NativeCaptureObserver.PREPARATION && lexer.bufferEnd > 0 &&
                                lexer.tokenStart.toLong() * 4 >= lexer.bufferEnd.toLong() * 3
                            ) {
                                queueWrite(NativeCaptureObserver.PREPARATION, advances)
                            }
                        }
                    }
            }
        }
        if (factory !=
            null
        ) {
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(JavaLanguage.INSTANCE, factory)
        }
        try {
            val captureStart = System.nanoTime()
            val captured = NativeGuideConflictDetector.captureMarkerSources(myFixture.editor, pair, true)
            val captureNs = System.nanoTime() - captureStart
            val start = System.nanoTime()
            val worker = scope.async(observer) {
                val context = currentCoroutineContext()
                try {
                    captured {
                        context.ensureActive()
                        ProgressManager.checkCanceled()
                    }.also { check(!app.isReadAccessAllowed) }
                } finally {
                    resolutionEnded.set(System.nanoTime())
                    if (!queued.get()) writerDone.set(true)
                }
            }
            try {
                val pump = awaitPerformanceEvents("native exact-body/write sample", 120) {
                    worker.isCompleted &&
                        writerDone.get()
                }
                val resolution = runCatching { worker.getCompleted() }
                val result = resolution.getOrNull()
                val resolutionFailure = resolution.exceptionOrNull()
                val sampleId = "$runId:$corpus:$mode:$writer:$iteration"
                for (body in bodies) {
                    val request = checkNotNull(requests[body.requestId])
                    emit(
                        output, "runId" to runId, "kind" to "read-body", "sampleId" to sampleId,
                        "corpus" to corpus, "mode" to mode, "writerMode" to writer, "iteration" to iteration,
                        "attemptId" to body.id, "requestId" to body.requestId, "phase" to body.phase,
                        "requestedNs" to request.ns, "enteredNs" to body.enteredNs, "exitedNs" to body.exitedNs,
                        "readBodyNs" to body.exitedNs - body.enteredNs,
                        "readAcquisitionNs" to body.enteredNs - request.ns,
                        "ownedOperations" to body.operations, "completed" to body.completed, "failure" to body.failure,
                        "writerRequestedInsideBody" to
                            (requestedAt.get() != 0L && requestedAt.get() in body.enteredNs..body.exitedNs),
                    )
                }
                val preparation = bodies.filter { it.phase == NativeCaptureObserver.PREPARATION }
                val writerInsideAnyBody = bodies.any {
                    requestedAt.get() != 0L &&
                        requestedAt.get() in it.enteredNs..it.exitedNs
                }
                emit(
                    output, "runId" to runId, "kind" to "sample", "sampleId" to sampleId,
                    "corpus" to corpus, "mode" to mode, "writer" to withWriter, "writerMode" to writer,
                    "iteration" to iteration,
                    "captureEdtNs" to captureNs, "resolutionNs" to resolutionEnded.get() - start,
                    "readBodyCount" to bodies.size,
                    "readBodyMaximumNs" to bodies.maxOfOrNull { it.exitedNs - it.enteredNs },
                    "preparationBodyCount" to preparation.size,
                    "preparationMaximumNs" to preparation.maxOfOrNull { it.exitedNs - it.enteredNs },
                    "traversalChunksObserved" to traversalChunks.get(),
                    "lexerAdvanceObserverInstalled" to (factory != null), "lexerAdvances" to lexerAdvances.get(),
                    "writerTriggered" to queued.get(), "writerTriggerPhase" to triggerPhase.get(),
                    "writerTriggerOperations" to triggerOperations.get(),
                    "writeQueuedAtNs" to queuedAt.get(), "writeRequestedAtNs" to requestedAt.get(),
                    "writeAcquiredAtNs" to acquiredAt.get(),
                    "writeQueueDelayNs" to if (requestedAt.get() != 0L) requestedAt.get() - queuedAt.get() else null,
                    "writeWaitNs" to if (acquiredAt.get() != 0L) acquiredAt.get() - requestedAt.get() else null,
                    "writerRequestedInsideAnyBody" to writerInsideAnyBody,
                    "resolutionFinishedBeforeWriteRequest" to
                        if (requestedAt.get() != 0L) resolutionEnded.get() <= requestedAt.get() else null,
                    "pumpChecks" to pump.checks, "pumpEvents" to pump.events, "pumpParks" to pump.parks,
                    "pumpParkedNs" to pump.parkedNs, "pumpMaximumCheckGapNs" to pump.maximumCheckGapNs,
                    "status" to
                        if (resolutionFailure !=
                            null
                        ) {
                            "failed"
                        } else if (result?.direct != true &&
                            result?.currentScope != true
                        ) {
                            "empty-or-invalidated"
                        } else {
                            "completed"
                        },
                    "resolutionFailure" to resolutionFailure?.javaClass?.name,
                    "writerFailure" to writerFailure.get()?.javaClass?.name,
                    "direct" to result?.direct, "scope" to result?.currentScope,
                )
                assertThat(resolutionFailure).isNull()
                if (!withWriter) {
                    val completed = checkNotNull(result)
                    if (mode ==
                        "scope"
                    ) {
                        assertThat(completed.currentScope).isTrue()
                    } else {
                        assertThat(completed.direct).isTrue()
                    }
                }
                writerFailure.get()?.let { throw it }
            } finally {
                active.set(false)
                worker.cancel()
            }
        } finally {
            if (factory !=
                null
            ) {
                SyntaxHighlighterFactory.LANGUAGE_FACTORY.removeExplicitExtension(JavaLanguage.INSTANCE, factory)
            }
        }
    }

    private data class Request(val phase: Int, val ns: Long)
    private data class Body(
        val id: Long,
        val requestId: Long,
        val phase: Int,
        val enteredNs: Long,
        var exitedNs: Long = 0,
        var operations: Int = 0,
        var completed: Boolean = false,
        var failure: String? = null,
    )

    private class ObservedLexer(private val delegate: Lexer, private val beforeAdvance: (Lexer) -> Unit) : Lexer() {
        override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) =
            delegate.start(buffer, startOffset, endOffset, initialState)
        override fun getState(): Int = delegate.state
        override fun getTokenType(): IElementType? = delegate.tokenType
        override fun getTokenStart(): Int = delegate.tokenStart
        override fun getTokenEnd(): Int = delegate.tokenEnd
        override fun advance() {
            beforeAdvance(delegate)
            delegate.advance()
        }
        override fun getBufferSequence(): CharSequence = delegate.bufferSequence
        override fun getBufferEnd(): Int = delegate.bufferEnd
        override fun getCurrentPosition(): LexerPosition = delegate.currentPosition
        override fun restore(position: LexerPosition) = delegate.restore(position)
    }

    private data class Corpus(
        val name: String,
        val filename: String,
        val source: String,
        val openOffset: Int,
        val carets: List<Pair<String, Int>>,
        val forceLazyPreparation: Boolean = false,
    )

    private fun corpora(): List<Corpus> {
        val java = "class NativeMeasure {\n  void run() {\n" + "    call(1);\n".repeat(20_000) + "  }\n}\n"
        val methodOpen = java.indexOf('{', java.indexOf("void"))
        val xml = "<root>\n" + "  <item><value>x</value></item>\n".repeat(5_000) + "</root>\n"
        return listOf(
            Corpus(
                "large-java",
                "NativeMeasure.java",
                java,
                methodOpen,
                listOf("direct" to methodOpen, "scope" to java.lastIndexOf("call") + 1),
            ),
            Corpus(
                "large-java-lazy",
                "NativeLazyMeasure.java",
                java,
                methodOpen,
                listOf("direct" to methodOpen, "scope" to java.lastIndexOf("call") + 1),
                forceLazyPreparation = true,
            ),
            Corpus("large-xml", "NativeMeasure.xml", xml, 0, listOf("direct" to 0)),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun emit(output: Path, vararg fields: Pair<String, Any?>) {
        val line = fields.joinToString(prefix = "{", postfix = "}\n") { (key, value) ->
            "${quoted(key)}:" +
                when (value) {
                    null -> "null"
                    is Number, is Boolean -> value.toString()
                    else -> quoted(value.toString())
                }
        }
        Files.writeString(output, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\""
}
