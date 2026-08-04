package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.tree.IElementType

/** Result of a bounded active-pair lookup. */
internal sealed interface ActiveBracketPairResolution {
    data class Complete(val pair: BracketPair?) : ActiveBracketPairResolution

    /** The token budget was exhausted, or no resolver is available. */
    data object Incomplete : ActiveBracketPairResolution
}

/** Fast-path recognition used while the full highlighting snapshot is absent or stale. */
internal fun interface ActiveBracketPairResolver {
    fun findInnermost(editor: Editor, caretOffset: Int): ActiveBracketPairResolution

    companion object {
        val NONE = ActiveBracketPairResolver { _, _ -> ActiveBracketPairResolution.Incomplete }
    }
}

/**
 * Finds the containing pair with the platform matcher, but only after the
 * token language passes the same `lang.braceMatcher` capability gate as the
 * full analyzer. A shared token budget keeps this synchronous EDT path bounded.
 */
internal class EditorHighlighterActiveBracketPairResolver(
    private val fileType: FileType,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) : ActiveBracketPairResolver {
    override fun findInnermost(
        editor: Editor,
        caretOffset: Int,
    ): ActiveBracketPairResolution {
        val document = editor.document
        if (caretOffset <= 0 || caretOffset > document.textLength || document.textLength == 0) {
            return ActiveBracketPairResolution.Complete(null)
        }

        val highlighter = editor.highlighter
        val text = document.immutableCharSequence
        val budget = TraversalBudget(tokenBudget)
        val languageSupport = HashMap<Language, Boolean>()
        var iterator = highlighter.createIterator((caretOffset - 1).coerceAtMost(text.lastIndex))
        if (iterator.document !== document) return ActiveBracketPairResolution.Incomplete

        while (!iterator.atEnd() && !budget.exhausted) {
            val tokenStart = iterator.start
            val tokenEnd = iterator.end
            if (tokenStart >= caretOffset) {
                retreat(iterator, budget)
                continue
            }

            if (!iterator.hasLanguageMatcher(languageSupport)) {
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
                        return ActiveBracketPairResolution.Complete(
                            matched.toPair(
                                closeOffset = tokenStart,
                                closeTokenLength = tokenEnd - tokenStart,
                                document = document,
                            ),
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
                if (matched != null && caretOffset < matched.end) {
                    return ActiveBracketPairResolution.Complete(
                        Match(tokenStart, tokenEnd).toPair(
                            closeOffset = matched.start,
                            closeTokenLength = matched.end - matched.start,
                            document = document,
                        ),
                    )
                }
            }

            retreat(iterator, budget)
        }
        return if (budget.exhausted) {
            ActiveBracketPairResolution.Incomplete
        } else {
            ActiveBracketPairResolution.Complete(null)
        }
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

    private fun HighlighterIterator.hasLanguageMatcher(
        cache: HashMap<Language, Boolean>,
    ): Boolean {
        val language = tokenType?.language ?: return false
        return cache.getOrPut(language) { LanguageBraceMatchers.isRegistered(language) }
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
