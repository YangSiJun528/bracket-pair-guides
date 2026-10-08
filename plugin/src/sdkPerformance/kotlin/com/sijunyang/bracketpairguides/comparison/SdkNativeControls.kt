package com.sijunyang.bracketpairguides.comparison

import com.intellij.lang.Language
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.tree.ILazyParseableElementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Setup only: deliberately selects the actual SDK lazy-language branch. */
suspend fun forceNativeLazyLanguage(editor: Editor, offset: Int) {
    readAction {
        val file = checkNotNull(PsiDocumentManager.getInstance(editor.project!!).getPsiFile(editor.document))
        var element = checkNotNull(file.findElementAt(offset))
        val codeBlock = Class.forName("com.intellij.psi.PsiCodeBlock")
        while (!codeBlock.isInstance(element)) element = checkNotNull(element.parent)
        val language = checkNotNull(Language.findLanguageByID("JAVA"))
        element.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, language)
        check(element.node.getUserData(ILazyParseableElementType.LANGUAGE_KEY) === language)
    }
}

/** One operation's observer signals atomics only. An independent thread dispatches its writer. */
class NativeWriterProbe(private val reads: ReadRecorder, private val mode: String, private val writerMode: String) :
    AutoCloseable {
    private data class Trigger(val at: Long, val phase: String, val operations: Int)
    private val trigger = AtomicReference<Trigger?>()
    private val chunks = AtomicInteger()
    private val lexerAdvances = AtomicInteger()
    private val ended = AtomicLong()
    private val stop = AtomicBoolean()
    private val done = AtomicBoolean(writerMode == "none")
    private val queued = AtomicLong()
    private val requested = AtomicLong()
    private val acquired = AtomicLong()
    private val writerEnded = AtomicLong()
    private val writerFailure = AtomicReference<Throwable?>()
    private var installed: Pair<Language, SyntaxHighlighterFactory>? = null
    private val dispatcher = if (writerMode == "none") {
        null
    } else {
        Thread(::dispatchWriter, "issue97-native-writer").also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun dispatchWriter() {
        while (!stop.get() || trigger.get() != null) {
            if (trigger.get() != null) {
                queued.set(System.nanoTime())
                ApplicationManager.getApplication().invokeLater {
                    try {
                        requested.set(System.nanoTime())
                        ApplicationManager.getApplication().runWriteAction { acquired.set(System.nanoTime()) }
                    } catch (failure: Throwable) {
                        writerFailure.set(failure)
                    } finally {
                        writerEnded.set(System.nanoTime())
                        done.set(true)
                    }
                }
                return
            }
            LockSupport.parkNanos(100_000)
        }
    }

    init {
        reads.onNativeProgress { phase, operations ->
            val expected = if (mode == "scope") "native-structural-traversal" else "native-direct-traversal"
            if (writerMode == "late-traversal" && phase == expected && chunks.incrementAndGet() == 9) {
                trigger.compareAndSet(null, Trigger(System.nanoTime(), phase, operations))
            }
        }
    }

    suspend fun installLazyLexer(editor: Editor) {
        if (writerMode != "late-lazy-lexer") return
        withContext(Dispatchers.EDT) {
            val language = checkNotNull(Language.findLanguageByID("JAVA"))
            val file = checkNotNull(PsiDocumentManager.getInstance(editor.project!!).getPsiFile(editor.document))
            val original =
                checkNotNull(SyntaxHighlighterFactory.getSyntaxHighlighter(language, editor.project, file.virtualFile))
            val factory = object : SyntaxHighlighterFactory() {
                override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
                    object : SyntaxHighlighter by original {
                        override fun getHighlightingLexer(): Lexer =
                            AdvancingLexer(original.highlightingLexer) { lexer ->
                                val advances = lexerAdvances.incrementAndGet()
                                val latePreparation = trigger.get() == null &&
                                    reads.nativePhase == "native-preparation" && lexer.bufferEnd > 0 &&
                                    lexer.tokenStart.toLong() * 4 >= lexer.bufferEnd.toLong() * 3
                                if (latePreparation) {
                                    trigger.compareAndSet(
                                        null,
                                        Trigger(System.nanoTime(), "native-preparation", advances),
                                    )
                                }
                            }
                    }
            }
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(language, factory)
            installed = language to factory
        }
    }

    fun resolutionEnded() {
        ended.set(System.nanoTime())
    }

    suspend fun finish(): Map<String, Any?> = withContext(NonCancellable) {
        // Stop/join prevents a dispatch race when the calculation completed without a trigger.
        stop.set(true)
        dispatcher?.join(5_000)
        check(dispatcher?.isAlive != true) { "Native external dispatcher did not finish" }
        if (queued.get() != 0L) {
            withTimeout(5_000) {
                while (!done.get()) delay(1)
            }
        }
        writerFailure.get()?.let { throw it }
        val signal = trigger.get()
        val bodies = reads.snapshot()
        val request = requested.get()
        mapOf(
            "writerMode" to writerMode, "triggerObserved" to (signal != null),
            "triggerNanos" to signal?.at, "triggerPhase" to signal?.phase,
            "triggerOwnedOperations" to signal?.operations, "traversalProgressEvents" to chunks.get(),
            "lexerAdvances" to lexerAdvances.get(), "queuedNanos" to queued.get(),
            "requestedNanos" to request, "acquiredNanos" to acquired.get(),
            "writerEndedNanos" to writerEnded.get(), "resolutionEndedNanos" to ended.get(),
            "externalDispatchGapNs" to signal?.let { if (queued.get() > 0) queued.get() - it.at else null },
            "edtQueueDelayNs" to if (request > 0) request - queued.get() else null,
            "writeWaitNs" to if (acquired.get() > 0) acquired.get() - request else null,
            "resolutionCompletedBeforeWriteRequest" to (request > 0 && ended.get() <= request),
            "writeRequestedInsideTriggeredPhase" to bodies.any {
                request > 0 && it.phase == signal?.phase && it.enteredNanos <= request && it.exitedNanos >= request
            },
            "writeRequestedInsideObservedRead" to bodies.any {
                request > 0 && it.enteredNanos <= request && it.exitedNanos >= request
            },
            "unstagedExternalWriter" to true,
            "dispatchDifferenceFromReference" to
                "observer only signals atomic; separate thread queues write; misses retained without retry",
        )
    }

    override fun close() {
        stop.set(true)
        reads.onNativeProgress(null)
        installed?.let { (language, factory) ->
            SyntaxHighlighterFactory.LANGUAGE_FACTORY.removeExplicitExtension(language, factory)
        }
        installed = null
    }
}

/** Delegates every lexer operation; only a real advance is observed. */
private class AdvancingLexer(private val actual: Lexer, private val advanced: (Lexer) -> Unit) : Lexer() {
    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) =
        actual.start(buffer, startOffset, endOffset, initialState)
    override fun getState() = actual.state
    override fun getTokenType() = actual.tokenType
    override fun getTokenStart() = actual.tokenStart
    override fun getTokenEnd() = actual.tokenEnd
    override fun advance() {
        actual.advance()
        advanced(actual)
    }
    override fun getBufferSequence() = actual.bufferSequence
    override fun getBufferEnd() = actual.bufferEnd
    override fun getCurrentPosition(): LexerPosition = actual.currentPosition
    override fun restore(position: LexerPosition) = actual.restore(position)
}
