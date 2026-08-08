package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.pairing.IntellijBracketPairingEngine

/**
 * Pairs tokens recognized by each token language's `lang.braceMatcher`.
 *
 * Recognition stays on the editor's token stream. The analyzer neither scans
 * raw characters nor falls back to the legacy file-type brace matcher. A
 * language without a registered matcher is therefore deliberately ignored.
 */
internal class BracketPairAnalyzer(
    private val editor: Editor,
    private val fileType: FileType,
    private val isLanguageEnabled: (String) -> Boolean = { true },
) : BracketPairProvider {
    constructor(editor: Editor) : this(
        editor = editor,
        fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
            ?: PlainTextFileType.INSTANCE,
    )

    constructor(
        editor: Editor,
        isLanguageEnabled: (String) -> Boolean,
    ) : this(
        editor = editor,
        fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
            ?: PlainTextFileType.INSTANCE,
        isLanguageEnabled = isLanguageEnabled,
    )

    override fun collect(progress: ProgressIndicator): List<BracketPair> {
        val document = editor.document
        if (document.textLength == 0) return emptyList()

        val result = ArrayList<BracketPair>()
        val iterator = editor.highlighter.createIterator(0)
        if (iterator.document !== document) return emptyList()
        val text = document.immutableCharSequence
        val checkCanceled = progress::checkCanceled
        val pairing = IntellijBracketPairingEngine(
            document = document,
            fileType = fileType,
            text = text,
            isLanguageEnabled = isLanguageEnabled,
        ).newSession(checkCanceled)
        var visitedTokens = 0

        while (!iterator.atEnd()) {
            if (visitedTokens++ and CANCELLATION_MASK == 0) {
                progress.checkCanceled()
            }

            pairing.accept(iterator)?.let(result::add)
            iterator.advance()
        }

        progress.checkCanceled()
        return result
    }

    private companion object {
        const val CANCELLATION_MASK = 0xFF
    }
}
