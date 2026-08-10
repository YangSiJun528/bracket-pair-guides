package com.sijunyang.bracketpairguides.analysis.active

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.ActivePairKnowledge
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.pairing.DocumentBraceGrammar

/**
 * Searches backward for opening-token candidates, then runs the same forward
 * pairing core as full analysis until that candidate matches or is discarded.
 * The default 512-transition and 4ms ceilings are shared by the entire lookup
 * and reserve most of a 16ms UI frame for input, layout, and painting.
 *
 * The elapsed ceiling is best-effort for a single language-matcher callback,
 * which cannot be interrupted while it is running.
 */
internal class CaretBracketSearch(
    private val fileType: FileType,
    private val languages: BraceLanguageCatalog,
    private val isLanguageEnabled: (String) -> Boolean,
) {
    fun findInnermost(
        editor: Editor,
        caretOffset: Int,
    ): ActivePairKnowledge {
        val document = editor.document
        if (caretOffset <= 0 || caretOffset > document.textLength || document.textLength == 0) {
            return ActivePairKnowledge.Known(null)
        }

        val highlighter = editor.highlighter
        val text = document.immutableCharSequence
        val allowance = TraversalAllowance(
            maximumTransitions = DEFAULT_TOKEN_BUDGET,
            maximumElapsedNanos = DEFAULT_ELAPSED_BUDGET_NANOS,
            clock = System::nanoTime,
        )
        val pairing = DocumentBraceGrammar(
            document = document,
            fileType = fileType,
            text = text,
            languages = languages,
            isLanguageEnabled = isLanguageEnabled,
        )
        var iterator = highlighter.createIterator((caretOffset - 1).coerceAtMost(text.lastIndex))
        if (iterator.document !== document) return ActivePairKnowledge.Unknown

        while (!iterator.atEnd() && !allowance.exhausted) {
            val tokenStart = iterator.start
            if (tokenStart >= caretOffset) {
                if (!retreat(iterator, allowance)) break
                continue
            }

            val openingKind = pairing.openingKind(iterator)
            if (openingKind != null) {
                if (allowance.exhausted) break
                val candidate = matchCandidate(
                    editor = editor,
                    pairing = pairing,
                    candidateOffset = tokenStart,
                    replayFromStart =
                        openingKind == DocumentBraceGrammar.OpeningKind.SYMMETRIC_TOGGLE,
                    allowance = allowance,
                )
                if (allowance.exhausted) break
                if (candidate.requiresEarlierStructuralContext) {
                    return ActivePairKnowledge.Unknown
                }
                val pair = candidate.pair
                if (pair != null && caretOffset < pair.closeOffset + pair.closeTokenLength) {
                    return ActivePairKnowledge.Known(pair)
                }
            }

            if (!retreat(iterator, allowance)) break
        }
        return if (allowance.exhausted) {
            ActivePairKnowledge.Unknown
        } else {
            ActivePairKnowledge.Known(null)
        }
    }

    private fun matchCandidate(
        editor: Editor,
        pairing: DocumentBraceGrammar,
        candidateOffset: Int,
        replayFromStart: Boolean,
        allowance: TraversalAllowance,
    ): CandidateMatch {
        val iterator = editor.highlighter.createIterator(
            if (replayFromStart) 0 else candidateOffset,
        )
        if (iterator.atEnd()) return CandidateMatch.NONE
        val session = pairing.newSession(
            checkCanceled = {
                if (allowance.exhausted) throw TraversalAllowanceExceeded
            },
            trackedOpenOffset = candidateOffset,
        )
        var candidateAccepted = false

        try {
            while (!iterator.atEnd() && !allowance.exhausted) {
                val currentOffset = iterator.start
                val pair = session.accept(iterator)
                if (allowance.exhausted) return CandidateMatch.NONE

                if (candidateAccepted && pair?.openOffset == candidateOffset) {
                    return if (session.requiresEarlierStructuralContext) {
                        CandidateMatch.REQUIRES_EARLIER_STRUCTURAL_CONTEXT
                    } else {
                        CandidateMatch(pair)
                    }
                }
                if (currentOffset == candidateOffset) {
                    candidateAccepted = true
                } else if (!candidateAccepted && currentOffset > candidateOffset) {
                    return CandidateMatch.NONE
                }
                if (candidateAccepted && !session.hasOpenAt(candidateOffset)) {
                    return if (session.requiresEarlierStructuralContext) {
                        CandidateMatch.REQUIRES_EARLIER_STRUCTURAL_CONTEXT
                    } else {
                        CandidateMatch.NONE
                    }
                }
                if (!advance(iterator, allowance)) return CandidateMatch.NONE
            }
        } catch (_: TraversalAllowanceExceeded) {
            return CandidateMatch.NONE
        }
        return if (session.requiresEarlierStructuralContext) {
            CandidateMatch.REQUIRES_EARLIER_STRUCTURAL_CONTEXT
        } else {
            CandidateMatch.NONE
        }
    }

    private data class CandidateMatch(
        val pair: BracketPair? = null,
        val requiresEarlierStructuralContext: Boolean = false,
    ) {
        companion object {
            val NONE = CandidateMatch()
            val REQUIRES_EARLIER_STRUCTURAL_CONTEXT = CandidateMatch(
                requiresEarlierStructuralContext = true,
            )
        }
    }

    private fun advance(
        iterator: HighlighterIterator,
        allowance: TraversalAllowance,
    ): Boolean {
        if (!allowance.consume()) return false
        iterator.advance()
        return true
    }

    private fun retreat(
        iterator: HighlighterIterator,
        allowance: TraversalAllowance,
    ): Boolean {
        if (!allowance.consume()) return false
        iterator.retreat()
        return true
    }

    private class TraversalAllowance(
        maximumTransitions: Int,
        maximumElapsedNanos: Long,
        private val clock: () -> Long,
    ) {
        private var remaining = maximumTransitions.coerceAtLeast(1)
        private val elapsedLimitNanos = maximumElapsedNanos.coerceAtLeast(1L)
        private val startedAtNanos = clock()

        val exhausted: Boolean
            get() = remaining == 0 || elapsedNanos() >= elapsedLimitNanos

        fun consume(): Boolean {
            if (exhausted) return false
            remaining--
            return true
        }

        private fun elapsedNanos(): Long = clock() - startedAtNanos
    }

    private object TraversalAllowanceExceeded : RuntimeException(null, null, false, false)

    private companion object {
        private const val DEFAULT_TOKEN_BUDGET = 512
        private const val DEFAULT_ELAPSED_BUDGET_NANOS = 4_000_000L
    }
}
