package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.tree.IElementType

/** Fast-path recognition used only while the full highlighting pass is stale. */
internal fun interface ActiveBracketPairResolver {
    fun findInnermost(editor: Editor, caretOffset: Int): BracketPair?

    companion object {
        val NONE = ActiveBracketPairResolver { _, _ -> null }
    }
}

/**
 * Finds the containing pair with the same public brace matcher used by the IDE.
 * A shared token budget keeps this synchronous EDT fast path bounded; the normal
 * background highlighting pass remains the authoritative fallback.
 */
internal class EditorHighlighterActiveBracketPairResolver(
    private val fileType: FileType,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) : ActiveBracketPairResolver {
    override fun findInnermost(editor: Editor, caretOffset: Int): BracketPair? {
        val document = editor.document
        if (caretOffset <= 0 || caretOffset > document.textLength || document.textLength == 0) {
            return null
        }

        val highlighter = editor.highlighter
        val text = document.immutableCharSequence
        val budget = TraversalBudget(tokenBudget)
        var iterator = highlighter.createIterator((caretOffset - 1).coerceAtMost(text.lastIndex))
        if (iterator.document !== document) return null

        while (!iterator.atEnd() && !budget.exhausted) {
            val tokenStart = iterator.start
            val tokenEnd = iterator.end
            if (tokenStart >= caretOffset) {
                retreat(iterator, budget)
                continue
            }

            if (BraceMatchingUtil.isRBraceToken(iterator, text, fileType)) {
                val matched = matchFrom(
                    editor = editor,
                    text = text,
                    offset = tokenStart,
                    forward = false,
                    budget = budget,
                )
                if (matched != null) {
                    if (caretOffset < tokenEnd) {
                        return matched.toPair(
                            closeOffset = tokenStart,
                            closeTokenLength = tokenEnd - tokenStart,
                            document = document,
                        )
                    }

                    iterator = highlighter.createIterator(matched.start)
                    retreat(iterator, budget)
                    continue
                }
            }

            if (BraceMatchingUtil.isLBraceToken(iterator, text, fileType)) {
                val matched = matchFrom(
                    editor = editor,
                    text = text,
                    offset = tokenStart,
                    forward = true,
                    budget = budget,
                )
                if (matched != null &&
                    caretOffset > tokenStart &&
                    caretOffset < matched.end
                ) {
                    return Match(tokenStart, tokenEnd).toPair(
                        closeOffset = matched.start,
                        closeTokenLength = matched.end - matched.start,
                        document = document,
                    )
                }
            }

            retreat(iterator, budget)
        }
        return null
    }

    private fun matchFrom(
        editor: Editor,
        text: CharSequence,
        offset: Int,
        forward: Boolean,
        budget: TraversalBudget,
    ): Match? {
        val iterator = BudgetedHighlighterIterator(
            editor.highlighter.createIterator(offset),
            budget,
        )
        if (iterator.atEnd() ||
            !BraceMatchingUtil.matchBrace(text, fileType, iterator, forward) ||
            iterator.atEnd()
        ) {
            return null
        }
        return Match(iterator.start, iterator.end)
    }

    private fun retreat(iterator: HighlighterIterator, budget: TraversalBudget) {
        if (budget.consume()) iterator.retreat()
    }

    private data class Match(val start: Int, val end: Int) {
        fun toPair(
            closeOffset: Int,
            closeTokenLength: Int,
            document: Document,
        ): BracketPair {
            return BracketPair(
                openOffset = start,
                openTokenLength = end - start,
                closeOffset = closeOffset,
                closeTokenLength = closeTokenLength,
                depth = 0,
                openLine = document.getLineNumber(start),
                closeLine = document.getLineNumber(closeOffset),
            )
        }
    }

    private class TraversalBudget(maximumTokens: Int) {
        private var remaining = maximumTokens.coerceAtLeast(1)

        val exhausted: Boolean
            get() = remaining == 0

        fun consume(): Boolean {
            if (remaining == 0) return false
            remaining--
            return true
        }
    }

    private class BudgetedHighlighterIterator(
        private val delegate: HighlighterIterator,
        private val budget: TraversalBudget,
    ) : HighlighterIterator {
        override fun getTextAttributes(): TextAttributes = delegate.textAttributes

        override fun getStart(): Int = delegate.start

        override fun getEnd(): Int = delegate.end

        override fun getTokenType(): IElementType? = delegate.tokenType

        override fun advance() {
            if (budget.consume()) delegate.advance()
        }

        override fun retreat() {
            if (budget.consume()) delegate.retreat()
        }

        override fun atEnd(): Boolean = budget.exhausted || delegate.atEnd()

        override fun getDocument(): Document = delegate.document
    }

    companion object {
        private const val DEFAULT_TOKEN_BUDGET = 4_096
    }
}
