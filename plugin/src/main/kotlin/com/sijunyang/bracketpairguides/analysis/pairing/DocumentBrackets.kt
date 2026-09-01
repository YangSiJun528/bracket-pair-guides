package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

/**
 * Pairs tokens recognized by IntelliJ's effective brace matcher.
 *
 * Recognition stays on the editor's token stream and follows the platform's
 * token-language, legacy file-type, and host-language fallback order. This
 * object never scans raw characters.
 */
internal class DocumentBrackets(
    private val editor: Editor,
    private val fileType: FileType,
    private val languages: BraceLanguageCatalog,
    private val isLanguageEnabled: (String) -> Boolean,
) {
    fun recognize(progress: ProgressIndicator): DocumentBracketRecognition {
        val document = editor.document
        if (document.textLength == 0) {
            return DocumentBracketRecognition.Complete(
                PairTable.empty(),
                BraceMatcherAvailability.UNDETERMINED,
            )
        }

        val pairs = PairCollection(BracketRecognitionLimits.completedPairs)
        val iterator = editor.highlighter.createIterator(0)
        if (iterator.document !== document) {
            return DocumentBracketRecognition.Complete(
                PairTable.empty(),
                BraceMatcherAvailability.UNDETERMINED,
            )
        }
        val text = document.immutableCharSequence
        val checkCanceled = progress::checkCanceled
        val grammar =
            DocumentBraceGrammar(
                document = document,
                fileType = fileType,
                text = text,
                languages = languages,
                isLanguageEnabled = isLanguageEnabled,
            )
        val pairing =
            grammar.newSession(
                checkCanceled = checkCanceled,
                pairSink = pairs,
                maximumPendingOpens = BracketRecognitionLimits.MAXIMUM_PENDING_OPENS,
            )
        var visitedTokens = 0

        try {
            while (!iterator.atEnd()) {
                if (visitedTokens++ and CANCELLATION_MASK == 0) {
                    progress.checkCanceled()
                }

                if (!pairing.accept(iterator)) {
                    return DocumentBracketRecognition.Unavailable(
                        BracketRecognitionRefusal.PENDING_OPEN_CAPACITY,
                    )
                }
                iterator.advance()
            }
        } catch (_: PairCapacityReached) {
            return DocumentBracketRecognition.Unavailable(
                BracketRecognitionRefusal.PAIR_CAPACITY,
            )
        }

        progress.checkCanceled()
        return DocumentBracketRecognition.Complete(
            checkNotNull(pairs.authoritativePairs()),
            grammar.matcherAvailability(),
        )
    }

    private companion object {
        private const val CANCELLATION_MASK = 0xFF
    }
}

/** Authoritative document-pair recognition state. */
internal sealed interface DocumentBracketRecognition {
    class Complete(val pairs: PairTable, val matcherAvailability: BraceMatcherAvailability) :
        DocumentBracketRecognition

    class Unavailable(val refusal: BracketRecognitionRefusal) : DocumentBracketRecognition
}

internal enum class BracketRecognitionRefusal {
    PAIR_CAPACITY,
    PENDING_OPEN_CAPACITY,
}
