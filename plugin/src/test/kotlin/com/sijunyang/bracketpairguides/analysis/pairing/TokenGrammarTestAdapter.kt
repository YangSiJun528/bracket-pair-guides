package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.tree.IElementType
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairSink
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine

/**
 * Test-only token traversal adapter for production classifier unit tests and explicit synchronous references.
 * Synchronous pairing glue stays here; production grammar only classifies occurrences.
 * Pairs tokens recognized by IntelliJ's effective brace matcher.
 *
 * Recognition stays on the editor's token stream and follows the platform's
 * token-language, legacy file-type, and host-language fallback order. This
 * object never scans raw characters.
 */
internal class TokenGrammarTestAdapter(
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
                fileType = fileType,
                text = text,
                languages = languages,
                isLanguageEnabled = isLanguageEnabled,
            )
        val pairing =
            Session(
                grammar = grammar,
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

                if (!pairing.accept(iterator, document)) {
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

    private class Session(
        private val grammar: DocumentBraceGrammar,
        checkCanceled: () -> Unit,
        pairSink: PairSink,
        maximumPendingOpens: Int,
    ) {
        private val pairing =
            PairingMachine<IElementType, BraceGroup> { group ->
                group.definition
            }.newSession(
                pairSink,
                CancellationProbe(checkCanceled),
                maximumPendingOpens,
            )

        private val token = DocumentBraceGrammar.Classification()

        /** Returns false before an opener would cross the pending-open capacity. */
        fun accept(iterator: HighlighterIterator, document: Document): Boolean {
            if (!grammar.classifyInto(iterator, token)) return true
            val offset = iterator.start
            val tokenLength = iterator.end - iterator.start
            val line = document.getLineNumber(offset)
            return pairing.accept(
                BraceGroup(token.language, token.tokenGroup, token.definition),
                token.type,
                token.context,
                token.strictContext,
                token.role,
                token.structuralRole,
                offset,
                tokenLength,
                line,
            )
        }
    }

    /** Equality intentionally preserves the original language + numeric group key. */
    private class BraceGroup(val language: Language, val tokenGroup: Int, val definition: BraceLanguageDefinition) {
        override fun equals(other: Any?): Boolean =
            other is BraceGroup && language == other.language && tokenGroup == other.tokenGroup

        override fun hashCode(): Int = 31 * language.hashCode() + tokenGroup
    }

    private companion object {
        private const val CANCELLATION_MASK = 0xFF
    }
}
