package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.analysis.pairing.IntellijBracketPairingEngine

private val systemMonotonicClock: () -> Long = System::nanoTime

/**
 * Searches backward for opening-token candidates, then runs the same forward
 * pairing core as full analysis until that candidate matches or is discarded.
 * The default 512-transition and 4ms ceilings are shared by the entire lookup
 * and reserve most of a 16ms UI frame for input, layout, and painting.
 *
 * The elapsed ceiling is best-effort for a single language-matcher callback,
 * which cannot be interrupted while it is running.
 */
internal class EditorHighlighterActiveBracketPairResolver internal constructor(
    private val fileType: FileType,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    private val isLanguageEnabled: (String) -> Boolean = { true },
    private val elapsedBudgetNanos: Long = DEFAULT_ELAPSED_BUDGET_NANOS,
    private val clock: () -> Long = systemMonotonicClock,
) {
    public constructor(
        fileType: FileType,
        isLanguageEnabled: (String) -> Boolean = { true },
    ) : this(
        fileType = fileType,
        tokenBudget = DEFAULT_TOKEN_BUDGET,
        isLanguageEnabled = isLanguageEnabled,
        elapsedBudgetNanos = DEFAULT_ELAPSED_BUDGET_NANOS,
        clock = systemMonotonicClock,
    )

    public fun findInnermost(
        editor: Editor,
        caretOffset: Int,
    ): ActivePairResult {
        val document = editor.document
        if (caretOffset <= 0 || caretOffset > document.textLength || document.textLength == 0) {
            return ActivePairResult.Complete(null)
        }

        val highlighter = editor.highlighter
        val text = document.immutableCharSequence
        val budget = TraversalBudget(
            maximumTransitions = tokenBudget,
            maximumElapsedNanos = elapsedBudgetNanos,
            clock = clock,
        )
        val pairing = IntellijBracketPairingEngine(
            document = document,
            fileType = fileType,
            text = text,
            isLanguageEnabled = isLanguageEnabled,
        )
        var iterator = highlighter.createIterator((caretOffset - 1).coerceAtMost(text.lastIndex))
        if (iterator.document !== document) return ActivePairResult.Incomplete

        while (!iterator.atEnd() && !budget.exhausted) {
            val tokenStart = iterator.start
            if (tokenStart >= caretOffset) {
                if (!retreat(iterator, budget)) break
                continue
            }

            val openingKind = pairing.openingKind(iterator)
            if (openingKind != null) {
                if (budget.exhausted) break
                val candidate = matchCandidate(
                    editor = editor,
                    pairing = pairing,
                    candidateOffset = tokenStart,
                    replayFromStart =
                        openingKind == IntellijBracketPairingEngine.OpeningKind.SYMMETRIC_TOGGLE,
                    budget = budget,
                )
                if (budget.exhausted) break
                if (candidate.requiresEarlierStructuralContext) {
                    return ActivePairResult.Incomplete
                }
                val pair = candidate.pair
                if (pair != null && caretOffset < pair.closeOffset + pair.closeTokenLength) {
                    return ActivePairResult.Complete(pair)
                }
            }

            if (!retreat(iterator, budget)) break
        }
        return if (budget.exhausted) {
            ActivePairResult.Incomplete
        } else {
            ActivePairResult.Complete(null)
        }
    }

    private fun matchCandidate(
        editor: Editor,
        pairing: IntellijBracketPairingEngine,
        candidateOffset: Int,
        replayFromStart: Boolean,
        budget: TraversalBudget,
    ): CandidateMatch {
        val iterator = editor.highlighter.createIterator(
            if (replayFromStart) 0 else candidateOffset,
        )
        if (iterator.atEnd()) return CandidateMatch.NONE
        val session = pairing.newSession(
            checkCanceled = {
                if (budget.exhausted) throw TraversalBudgetExceeded
            },
            trackedOpenOffset = candidateOffset,
        )
        var candidateAccepted = false

        try {
            while (!iterator.atEnd() && !budget.exhausted) {
                val currentOffset = iterator.start
                val pair = session.accept(iterator)
                if (budget.exhausted) return CandidateMatch.NONE

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
                if (!advance(iterator, budget)) return CandidateMatch.NONE
            }
        } catch (_: TraversalBudgetExceeded) {
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
        budget: TraversalBudget,
    ): Boolean {
        if (!budget.consume()) return false
        iterator.advance()
        return true
    }

    private fun retreat(
        iterator: HighlighterIterator,
        budget: TraversalBudget,
    ): Boolean {
        if (!budget.consume()) return false
        iterator.retreat()
        return true
    }

    private class TraversalBudget(
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

    private object TraversalBudgetExceeded : RuntimeException(null, null, false, false)

    private companion object {
        private const val DEFAULT_TOKEN_BUDGET = 512
        private const val DEFAULT_ELAPSED_BUDGET_NANOS = 4_000_000L
    }
}
