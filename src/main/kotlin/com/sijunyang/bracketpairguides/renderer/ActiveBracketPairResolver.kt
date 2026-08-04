package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairingCore
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType

/** Result of a bounded active-pair lookup. */
internal sealed interface ActiveBracketPairResolution {
    data class Complete(val pair: BracketPair?) : ActiveBracketPairResolution

    /** The transition/deadline budget was exhausted, or no resolver is available. */
    data object Incomplete : ActiveBracketPairResolution
}

/** Fast-path recognition used while the full highlighting snapshot is absent or stale. */
internal fun interface ActiveBracketPairResolver {
    fun findInnermost(editor: Editor, caretOffset: Int): ActiveBracketPairResolution

    companion object {
        val NONE = ActiveBracketPairResolver { _, _ -> ActiveBracketPairResolution.Incomplete }
    }
}

internal fun interface MonotonicClock {
    fun nowNanos(): Long
}

private object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/**
 * Searches backward for opening-token candidates, then runs the same forward
 * pairing core as full analysis until that candidate matches or is discarded.
 * The default 512-transition and 4ms ceilings are shared by the entire lookup
 * and reserve most of a 16ms UI frame for input, layout, and painting.
 *
 * The elapsed ceiling is best-effort for a single language-matcher callback,
 * which cannot be interrupted while it is running.
 */
internal class EditorHighlighterActiveBracketPairResolver(
    private val fileType: FileType,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    private val isLanguageEnabled: (String) -> Boolean = { true },
    private val elapsedBudgetNanos: Long = DEFAULT_ELAPSED_BUDGET_NANOS,
    private val clock: MonotonicClock = SystemMonotonicClock,
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
        val budget = TraversalBudget(
            maximumTransitions = tokenBudget,
            maximumElapsedNanos = elapsedBudgetNanos,
            clock = clock,
        )
        val pairing = BracketPairingCore(
            document = document,
            fileType = fileType,
            text = text,
            isLanguageEnabled = isLanguageEnabled,
        )
        var iterator = highlighter.createIterator((caretOffset - 1).coerceAtMost(text.lastIndex))
        if (iterator.document !== document) return ActiveBracketPairResolution.Incomplete

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
                        openingKind == BracketPairingCore.OpeningKind.SYMMETRIC_TOGGLE,
                    budget = budget,
                )
                if (budget.exhausted) break
                if (candidate.requiresEarlierStructuralContext) {
                    return ActiveBracketPairResolution.Incomplete
                }
                val pair = candidate.pair
                if (pair != null && caretOffset < pair.closeOffset + pair.closeTokenLength) {
                    return ActiveBracketPairResolution.Complete(pair)
                }
            }

            if (!retreat(iterator, budget)) break
        }
        return if (budget.exhausted) {
            ActiveBracketPairResolution.Incomplete
        } else {
            ActiveBracketPairResolution.Complete(null)
        }
    }

    private fun matchCandidate(
        editor: Editor,
        pairing: BracketPairingCore,
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
        private val clock: MonotonicClock,
    ) {
        private var remaining = maximumTransitions.coerceAtLeast(1)
        private val elapsedLimitNanos = maximumElapsedNanos.coerceAtLeast(1L)
        private val startedAtNanos = clock.nowNanos()

        val exhausted: Boolean
            get() = remaining == 0 || elapsedNanos() >= elapsedLimitNanos

        fun consume(): Boolean {
            if (exhausted) return false
            remaining--
            return true
        }

        private fun elapsedNanos(): Long = clock.nowNanos() - startedAtNanos
    }

    private object TraversalBudgetExceeded : RuntimeException(null, null, false, false)

    companion object {
        private const val DEFAULT_TOKEN_BUDGET = 512
        private const val DEFAULT_ELAPSED_BUDGET_NANOS = 4_000_000L
    }
}
