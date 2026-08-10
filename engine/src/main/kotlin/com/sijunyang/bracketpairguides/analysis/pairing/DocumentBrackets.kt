package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable

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
    fun pairs(progress: ProgressIndicator): PairTable {
        val document = editor.document
        if (document.textLength == 0) return PairTable.empty()

        val result = PairTable.draft()
        val iterator = editor.highlighter.createIterator(0)
        if (iterator.document !== document) return PairTable.empty()
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
            pairSink = result,
        )
        var visitedTokens = 0

        while (!iterator.atEnd()) {
            if (visitedTokens++ and CANCELLATION_MASK == 0) {
                progress.checkCanceled()
            }

            pairing.accept(iterator)
            iterator.advance()
        }

        progress.checkCanceled()
        return result.freeze()
    }

    private companion object {
        private const val CANCELLATION_MASK = 0xFF
    }
}
