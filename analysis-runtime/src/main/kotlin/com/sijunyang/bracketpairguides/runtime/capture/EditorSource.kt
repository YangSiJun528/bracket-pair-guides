package com.sijunyang.bracketpairguides.runtime.capture

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import com.sijunyang.bracketpairguides.core.input.PrefixBatch
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.RetryCapture
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.runtime.session.DocumentCalculation
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import kotlinx.coroutines.currentCoroutineContext

/** Host identity never escapes into model results or UI. */
internal class SourceIdentity private constructor(
    val stamp: Long,
    val fileType: FileType,
    val highlighter: com.intellij.openapi.editor.highlighter.EditorHighlighter,
    val tabSize: Int,
    val disabledLanguageIds: Set<String>,
) {
    fun matches(editor: Editor, layout: Boolean): Boolean = !editor.isDisposed && editor.project?.isDisposed != true &&
        stamp == editor.document.modificationStamp && highlighter === editor.highlighter &&
        fileType === fileType(editor) &&
        (!layout || tabSize == editor.settings.getTabSize(editor.project).coerceAtLeast(1))

    companion object {
        fun capture(editor: Editor, demand: GuideDemand): SourceIdentity = SourceIdentity(
            editor.document.modificationStamp,
            fileType(editor),
            editor.highlighter,
            editor.settings.getTabSize(editor.project).coerceAtLeast(1),
            demand.disabledLanguageIds,
        )
        fun fileType(editor: Editor): FileType = (
            FileDocumentManager.getInstance().getFile(editor.document)
                ?: (editor as? EditorEx)?.virtualFile
            )?.fileType ?: PlainTextFileType.INSTANCE
    }
}

internal class SourceChanged : RuntimeException(null, null, false, false)

/** Bounded SDK reads only. Core owns the complete attempt and retry loop. */
internal class EditorSource(
    val editor: Editor,
    private val identity: SourceIdentity,
    private val epoch: AnalysisReadEpoch,
    private val control: CalculationControl,
    private val calculation: DocumentCalculation,
) : BracketInput {
    val fileType get() = identity.fileType
    val disabledLanguageIds get() = identity.disabledLanguageIds
    private val text = DocumentTextCapture(4096)
    private var attemptEpoch = 0L
    private var tokens: BracketTokenCapture? = null
    private var observer: AnalysisCaptureObserver? = null

    override suspend fun beginAttempt(): DocumentFacts {
        val application = ApplicationManager.getApplication()
        check(!application.isDispatchThread && !application.isReadAccessAllowed)
        observer = currentCoroutineContext()[AnalysisCaptureObserver]
        attemptEpoch = epoch.current
        return capture(AnalysisCaptureObserver.INITIAL_STATE, layout = true) {
            tokens = BracketTokenCapture(this)
            DocumentFacts(
                editor.document.textLength,
                editor.document.lineCount,
                identity.tabSize,
                calculation.revisionFor(identity.stamp),
            )
        }
    }

    override suspend fun tokensAt(offset: Int): TokenBatch = capture(AnalysisCaptureObserver.TOKENS) {
        checkNotNull(tokens).capture(offset, checkCanceled = control::checkCanceled)
    }

    override suspend fun areCompatible(open: TokenKind, close: TokenKind, group: TokenGroup): Boolean =
        capture(AnalysisCaptureObserver.COMPATIBILITY) { checkNotNull(tokens).matches(open, close, group) }

    override suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch =
        capture(AnalysisCaptureObserver.GUIDE_PREFIX) {
            require(lineCount in 0..128)
            val document = editor.document
            PrefixBatch(
                firstLine,
                (firstLine until firstLine + lineCount).map { line ->
                    val start = document.getLineStartOffset(line)
                    val end = document.getLineEndOffset(line)
                    val after = minOf(start.toLong() + 128, end.toLong()).toInt()
                    PrefixChunk(text.copy(document, start, after), after, end)
                },
            )
        }

    override suspend fun continuePrefix(line: Int, afterOffset: Int): PrefixChunk =
        capture(AnalysisCaptureObserver.GUIDE_CONTINUATION) {
            val document = editor.document
            val end = document.getLineEndOffset(line)
            val after = minOf(afterOffset.toLong() + 4096, end.toLong()).toInt()
            PrefixChunk(text.copy(document, afterOffset, after), after, end)
        }

    override suspend fun validateCurrent() = capture(AnalysisCaptureObserver.FINAL_VALIDATION, layout = true) { }

    private suspend fun <T> capture(phase: Int, layout: Boolean = false, action: () -> T): T {
        val observation = observer
        val requested = observation?.requested(phase)
        return readAction {
            val entered = requested?.let { observation.entered(it, phase) }
            var completed = false
            var failure: Throwable? = null
            try {
                validate(layout)
                val value = action()
                validate(layout)
                completed = true
                value
            } catch (
                problem: Throwable,
            ) {
                failure = problem
                throw problem
            } finally {
                if (entered != null) observation.exited(entered, completed, failure)
            }
        }
    }

    private fun validate(layout: Boolean) {
        control.checkCanceled()
        if (!identity.matches(editor, layout)) throw SourceChanged()
        if (epoch.current != attemptEpoch) throw RetryCapture()
    }
}
