package com.sijunyang.bracketpairguides.core

import com.sijunyang.bracketpairguides.core.api.BracketCalculator
import com.sijunyang.bracketpairguides.core.input.BracketInput
import com.sijunyang.bracketpairguides.core.input.DocumentFacts
import com.sijunyang.bracketpairguides.core.input.PrefixBatch
import com.sijunyang.bracketpairguides.core.input.PrefixChunk
import com.sijunyang.bracketpairguides.core.input.StructuralRole
import com.sijunyang.bracketpairguides.core.input.TokenBatch
import com.sijunyang.bracketpairguides.core.input.TokenGroup
import com.sijunyang.bracketpairguides.core.input.TokenKind
import com.sijunyang.bracketpairguides.core.input.TokenRole
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.OffsetRange
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Pairing semantics are checked through the public input/result boundary, never the state machine. */
class TokenSemanticsContractTest {
    private val group = TokenGroup(0, 0)
    private val open = TokenKind()
    private val close = TokenKind()

    @Test fun strictContextsRecoverOnlyTheMatchingOpenContext() = runBlocking<Unit> {
        val source = Events(
            listOf(
                Event(open, TokenRole.OPEN, context = "outer", strict = true),
                Event(open, TokenRole.OPEN, context = "inner", strict = true),
                Event(close, TokenRole.CLOSE, context = "outer", strict = true),
            ),
        )
        val view = calculate(source)
        assertThat(view.activePairAt(2)?.let { it.openOffset to it.closeOffset }).isEqualTo(0 to 2)
        assertThat(offsets(view, 3)).containsExactly(0, 2)
    }

    @Test fun languageGroupsCannotConsumeEachOthersPendingTokens() = runBlocking<Unit> {
        val other = TokenGroup(1, 0)
        val view = calculate(
            Events(
                listOf(
                    Event(open, TokenRole.OPEN),
                    Event(open, TokenRole.OPEN, group = other),
                    Event(close, TokenRole.CLOSE),
                    Event(close, TokenRole.CLOSE, group = other),
                ),
            ),
        )
        assertThat(offsets(view, 4)).containsExactly(0, 1, 2, 3)
        assertThat(view.activePairAt(2)?.openOffset).isEqualTo(1)
    }

    @Test fun ordinaryRecoveryCannotCrossAStructuralBarrier() = runBlocking<Unit> {
        val structuralOpen = TokenKind()
        val structuralClose = TokenKind()
        val source = Events(
            listOf(
                Event(open, TokenRole.OPEN),
                Event(structuralOpen, TokenRole.OPEN, structural = StructuralRole.OPEN),
                Event(close, TokenRole.CLOSE),
                Event(structuralClose, TokenRole.CLOSE, structural = StructuralRole.CLOSE),
                Event(close, TokenRole.CLOSE),
            ),
            structuralOpen to structuralClose,
        )
        assertThat(offsets(calculate(source), 5)).containsExactly(0, 1, 3, 4)
    }

    @Test fun toggleTokensAlternateOpenAndCloseWithoutRetainingUnmatchedOccurrences() = runBlocking<Unit> {
        val quote = TokenKind()
        val source = Events(List(5) { Event(quote, TokenRole.TOGGLE) }, quote to quote)
        assertThat(offsets(calculate(source), 5)).containsExactly(0, 1, 2, 3)
    }

    @Test fun compatibilitySuspensionDoesNotReplayAlreadyConsumedTokens() = runBlocking<Unit> {
        val source = Events(
            List(400) {
                Event(
                    if (it % 2 == 0) open else close,
                    if (it % 2 == 0) TokenRole.OPEN else TokenRole.CLOSE,
                )
            },
        )
        val view = calculate(source)
        assertThat(offsets(view, 400)).hasSize(400)
        assertThat(source.answers).isEqualTo(1)
    }

    private suspend fun calculate(source: Events) = (
        BracketCalculator().analyze(
            source,
            AnalysisCoverage(true, true, false),
            coroutineControl(),
        ) as AnalysisResult.Available
        ).view

    private fun offsets(view: com.sijunyang.bracketpairguides.model.result.BracketView, length: Int): List<Int> {
        val window = view.visibleTokens(OffsetRange(0, length), 0, 512)
        return (0 until window.size).map(window::offsetAt)
    }

    private inner class Event(
        val kind: TokenKind,
        val role: TokenRole,
        val context: String? = null,
        val strict: Boolean = false,
        val structural: StructuralRole = StructuralRole.NONE,
        val group: TokenGroup = this@TokenSemanticsContractTest.group,
    )

    private inner class Events(private val events: List<Event>, private vararg val extra: Pair<TokenKind, TokenKind>) :
        BracketInput {
        var answers = 0
        override suspend fun beginAttempt() = DocumentFacts(events.size, 1, 4, 1)
        override suspend fun tokensAt(offset: Int): TokenBatch = TokenBatch.capture { sink ->
            val after = minOf(events.size, offset + 7)
            for (position in offset until after) {
                val event = events[position]
                sink.append(
                    event.kind, event.group, event.context, event.strict,
                    event.role, event.structural, position, 1, 0,
                )
            }
            TokenBatch.End(after, after == events.size, after - offset, BraceMatcherAvailability.AVAILABLE)
        }
        override suspend fun areCompatible(open: TokenKind, close: TokenKind, group: TokenGroup): Boolean {
            answers++
            yield()
            return (open === this@TokenSemanticsContractTest.open && close === this@TokenSemanticsContractTest.close) ||
                extra.any { it.first === open && it.second === close }
        }
        override suspend fun initialPrefix(line: Int): PrefixChunk = error("Guides were not requested")
        override suspend fun initialPrefixes(firstLine: Int, lineCount: Int): PrefixBatch =
            error("Guides were not requested")
        override suspend fun continuePrefix(line: Int, afterOffset: Int): PrefixChunk =
            error("Guides were not requested")
        override suspend fun validateCurrent() = Unit
    }
}
