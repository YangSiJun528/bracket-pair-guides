package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiDocumentManager
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.intellij.AnalysisReadEpoch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield

/** Exclusive sequential ownership; any failed/retried read body discards this entire proof. */
internal class NativeMarkerInspection(
    private val editor: Editor,
    private val pair: BracketPair,
    private val resolveScope: Boolean,
    private val caretOffset: Int,
    private val blockCursor: Boolean,
    private val collapsed: Boolean,
    private val source: EditorHighlighter,
    private val stamp: Long,
    private val epoch: AnalysisReadEpoch,
    private val ticket: Long,
) {
    private val document = editor.document
    private val project = checkNotNull(editor.project)

    suspend fun resolve(checkCanceled: () -> Unit): NativeGuideConflictDetector.NativeMarkerSources {
        check(!ApplicationManager.getApplication().isDispatchThread) { "Native marker resolution must run off EDT" }
        val observer = currentCoroutineContext()[NativeCaptureObserver]
        try {
            val file = capture(NativeCaptureObserver.ADMISSION, observer, checkCanceled) {
                val manager = PsiDocumentManager.getInstance(project)
                if (!pair.hasWellFormedTokenRange(document.textLength) || !manager.isCommitted(document)) {
                    null
                } else {
                    manager.getPsiFile(document)?.takeIf { it.isValid }
                }
            } ?: return none()
            // Factory, lazy-range copy and lexer setText retain their existing read-access contract.
            val prepared = capture(NativeCaptureObserver.PREPARATION, observer, checkCanceled) {
                NativeLazyHighlighter.prepare(editor, file, caretOffset, checkCanceled)
            }
            val direct = capture(NativeCaptureObserver.DIRECT_CLASSIFICATION, observer, checkCanceled) {
                NativeBraceContext.begin(
                    prepared,
                    file,
                    document.charsSequence,
                    caretOffset,
                    blockCursor,
                    checkCanceled,
                )
            }
            while (!capture(NativeCaptureObserver.DIRECT_TRAVERSAL, observer, checkCanceled) {
                    val budget = budget(checkCanceled)
                    direct.advance(document.charsSequence, budget, checkCanceled).also {
                        observer?.progressed(NativeCaptureObserver.DIRECT_TRAVERSAL, budget.consumed())
                    }
                }
            ) {
                yield()
            }
            val context = direct.result()
            if (context != null) {
                return capture(NativeCaptureObserver.FINAL_VALIDATION, observer, checkCanceled) {
                    NativeGuideConflictDetector.NativeMarkerSources(context.resolves(pair, checkCanceled), false)
                }
            }
            val scope = capture(NativeCaptureObserver.SCOPE_CLASSIFICATION, observer, checkCanceled) {
                val chars = document.charsSequence
                if (!resolveScope || adjacentWhitespace(chars) || caretOffset !in 0 until document.textLength ||
                    collapsed
                ) {
                    null
                } else {
                    val iterator = NativeCursor.create(source, caretOffset, checkCanceled)
                    if (iterator.atEnd()) {
                        null
                    } else {
                        val type = BraceMatchingUtil.getFileType(file, caretOffset)
                        val onBrace = BraceMatchingUtil.isStructuralBraceToken(type, iterator, chars) &&
                            (
                                BraceMatchingUtil.isRBraceToken(iterator, chars, type) ||
                                    BraceMatchingUtil.isLBraceToken(iterator, chars, type)
                                )
                        if (onBrace) {
                            null
                        } else {
                            Scope(
                                type,
                                iterator.bookmark(),
                                NativeBraceMatching.structuralLeft(type, iterator),
                            )
                        }
                    }
                }
            } ?: return none()
            var step: NativeBraceMatching.Step
            do {
                step = advance(scope, NativeCaptureObserver.STRUCTURAL_TRAVERSAL, observer, checkCanceled)
                if (step == NativeBraceMatching.Step.MORE) yield()
            } while (step == NativeBraceMatching.Step.MORE)
            if (step != NativeBraceMatching.Step.MATCHED) return none()
            capture(NativeCaptureObserver.SCOPE_MATCH_CLASSIFICATION, observer, checkCanceled) {
                val iterator = scope.cursor.restore(source, checkCanceled)
                scope.leftStart = iterator.start
                scope.leftEnd = iterator.end
                scope.scanner =
                    NativeBraceMatching.matching(document.charsSequence, scope.type, iterator, true, checkCanceled)
                scope.cursor = iterator.bookmark()
            }
            do {
                step = advance(scope, NativeCaptureObserver.SCOPE_MATCH_TRAVERSAL, observer, checkCanceled)
                if (step == NativeBraceMatching.Step.MORE) yield()
            } while (step == NativeBraceMatching.Step.MORE)
            return capture(NativeCaptureObserver.FINAL_VALIDATION, observer, checkCanceled) {
                val iterator = scope.cursor.restore(source, checkCanceled)
                NativeGuideConflictDetector.NativeMarkerSources(
                    false,
                    step == NativeBraceMatching.Step.MATCHED && !iterator.atEnd() &&
                        scope.leftStart == pair.openOffset &&
                        scope.leftEnd.toLong() == pair.openOffset.toLong() + pair.openTokenLength &&
                        iterator.start == pair.closeOffset &&
                        iterator.end.toLong() == pair.closeOffset.toLong() + pair.closeTokenLength,
                )
            }
        } catch (_: NativeProofInvalidated) {
            return none()
        }
    }

    private suspend fun advance(
        scope: Scope,
        phase: Int,
        observer: NativeCaptureObserver?,
        checkCanceled: () -> Unit,
    ): NativeBraceMatching.Step = capture(phase, observer, checkCanceled) {
        val iterator = scope.cursor.restore(source, checkCanceled)
        val budget = budget(checkCanceled)
        scope.scanner.advance(document.charsSequence, iterator, budget, checkCanceled).also {
            scope.cursor = iterator.bookmark()
            observer?.progressed(phase, budget.consumed())
        }
    }

    private suspend fun <T> capture(
        phase: Int,
        observer: NativeCaptureObserver?,
        checkCanceled: () -> Unit,
        action: () -> T,
    ): T {
        val request = observer?.requested(phase)
        var executions = 0
        val result = readAction {
            val attempt = request?.let { observer.entered(it, phase) }
            var completed = false
            var failure: Throwable? = null
            try {
                // A canceled lambda may already have advanced its private continuation. Never replay it.
                if (++executions != 1) throw NativeProofInvalidated()
                checkCanceled()
                validate()
                action().also {
                    checkCanceled()
                    validate()
                    completed = true
                }
            } catch (problem: Throwable) {
                failure = problem
                throw problem
            } finally {
                if (attempt != null) observer.exited(attempt, completed, failure)
            }
        }
        observer?.released(phase)
        return result
    }

    private fun validate() {
        if (editor.isDisposed || project.isDisposed || editor.document !== document ||
            document.modificationStamp != stamp || editor.highlighter !== source || epoch.current != ticket ||
            editor.caretModel.primaryCaret.offset != caretOffset || editor.settings.isBlockCursor != blockCursor
        ) {
            throw NativeProofInvalidated()
        }
    }

    private fun budget(checkCanceled: () -> Unit) =
        NativeBraceMatching.WorkBudget(8192, System.nanoTime() + 2_000_000L, checkCanceled)
    private fun none() = NativeGuideConflictDetector.NativeMarkerSources.NONE
    private fun adjacentWhitespace(chars: CharSequence): Boolean = caretOffset < 0 || caretOffset > chars.length ||
        (caretOffset > 0 && (chars[caretOffset - 1] == ' ' || chars[caretOffset - 1] == '\t')) ||
        (caretOffset < chars.length && (chars[caretOffset] == ' ' || chars[caretOffset] == '\t'))

    private fun NativeBraceContext.Context.resolves(pair: BracketPair, checkCanceled: () -> Unit): Boolean {
        val matching = when (currentBraceOffset) {
            pair.openOffset -> pair.closeOffset to pair.closeTokenLength
            pair.closeOffset -> pair.openOffset to pair.openTokenLength
            else -> return false
        }
        val end = matching.first.toLong() + matching.second
        return (navigationOffset.toLong() == matching.first.toLong() || navigationOffset.toLong() == end) &&
            exact(pair.openOffset, pair.openTokenLength, checkCanceled) &&
            exact(pair.closeOffset, pair.closeTokenLength, checkCanceled)
    }

    private fun exact(start: Int, length: Int, checkCanceled: () -> Unit): Boolean {
        val end = start.toLong() + length
        if (start < 0 || length <= 0 || end > Int.MAX_VALUE) return false
        val iterator = NativeCursor.create(source, start, checkCanceled)
        return !iterator.atEnd() && iterator.start == start && iterator.end.toLong() == end
    }

    private class Scope(val type: FileType, var cursor: NativeCursor, var scanner: NativeBraceMatching.Session) {
        var leftStart = -1
        var leftEnd = -1
    }
}
