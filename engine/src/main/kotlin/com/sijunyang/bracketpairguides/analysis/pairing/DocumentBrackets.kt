package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pipeline.AnalysisBudget

/**
 * Pairs tokens recognized by each token language's `lang.braceMatcher`.
 *
 * Recognition stays on the editor's token stream. This object neither scans
 * raw characters nor falls back to the legacy file-type brace matcher. A
 * language without a registered matcher is therefore deliberately ignored.
 */
internal class DocumentBrackets(
    private val editor: Editor,
    private val fileType: FileType,
    private val languages: BraceLanguageCatalog,
    private val isLanguageEnabled: (String) -> Boolean,
) {
    fun pairs(progress: ProgressIndicator): DocumentBracketState {
        val document = editor.document
        if (document.textLength == 0) return DocumentBracketState.Complete(PairTable.empty())

        val pairs = PairCollection(AnalysisBudget.pairCapacity)
        val iterator = editor.highlighter.createIterator(0)
        if (iterator.document !== document) {
            return DocumentBracketState.Complete(PairTable.empty())
        }
        val text = document.immutableCharSequence
        val checkCanceled = progress::checkCanceled
        val pairing = DocumentBraceGrammar(
            document = document,
            fileType = fileType,
            text = text,
            languages = languages,
            isLanguageEnabled = isLanguageEnabled,
        ).newSession(
            checkCanceled = checkCanceled,
            pairSink = pairs,
            maximumPendingOpens = AnalysisBudget.maximumPendingOpenCount,
        )
        var visitedTokens = 0

        try {
            while (!iterator.atEnd()) {
                if (visitedTokens++ and CANCELLATION_MASK == 0) {
                    progress.checkCanceled()
                }

                if (!pairing.accept(iterator)) {
                    return DocumentBracketState.Unavailable(
                        AnalysisLimit.PENDING_OPEN_CAPACITY,
                    )
                }
                iterator.advance()
            }
        } catch (_: PairCapacityReached) {
            return DocumentBracketState.Unavailable(AnalysisLimit.PAIR_CAPACITY)
        }

        progress.checkCanceled()
        return DocumentBracketState.Complete(checkNotNull(pairs.complete()))
    }

    private companion object {
        private const val CANCELLATION_MASK = 0xFF
    }
}

/** Authoritative document-pair recognition state. */
internal sealed interface DocumentBracketState {
    class Complete(val pairs: PairTable) : DocumentBracketState

    class Unavailable(val limit: AnalysisLimit) : DocumentBracketState
}
