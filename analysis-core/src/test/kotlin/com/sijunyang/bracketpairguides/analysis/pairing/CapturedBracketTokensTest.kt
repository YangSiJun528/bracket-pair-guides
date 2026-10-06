package com.sijunyang.bracketpairguides.analysis.pairing

import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole
import com.sijunyang.bracketpairguides.analysis.pairing.core.CancellationProbe
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class CapturedBracketTokensTest {
    @Test
    fun `late mixed group and context preserve earlier columns across growth and sealing`() {
        val kind = TokenKind()
        val firstGroup = BracketGroupId(0, 7)
        val equalDistinctGroup = BracketGroupId(0, 7)
        val foreignGroup = BracketGroupId(1, 7)
        val records = listOf(
            CapturedBracketToken(kind, firstGroup, null, true, BracketRole.OPEN, StructuralRole.NONE, 10, 2, 3),
            CapturedBracketToken(kind, firstGroup, null, false, BracketRole.CLOSE, StructuralRole.NONE, 20, 4, 5),
            CapturedBracketToken(kind, equalDistinctGroup, "tag", true, BracketRole.TOGGLE, StructuralRole.NONE, 30, 6, 7),
            CapturedBracketToken(kind, foreignGroup, null, false, BracketRole.OPEN, StructuralRole.NONE, 40, 8, 9),
            CapturedBracketToken(kind, firstGroup, "other", false, BracketRole.CLOSE, StructuralRole.NONE, 50, 10, 11),
        )
        val builder = CapturedBracketTokens.Builder(1)
        records.forEach { builder.append(it) }
        val batch = builder.seal(60, true, records.size, BraceMatcherAvailability.AVAILABLE)
        assertThat((0 until batch.size).map { batch[it] }).containsExactlyElementsOf(records)
        assertThat(batch[0].group).isSameAs(firstGroup)
        assertThat(batch[2].group).isSameAs(equalDistinctGroup)
        assertThat(batch[3].group).isSameAs(foreignGroup)
        assertThatThrownBy { builder.append(records[0]) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(batch[0]).isEqualTo(records[0])
    }

    @Test
    fun `direct pairing reads lazily introduced columns with exact strict contexts`() {
        val open = TokenKind()
        val close = TokenKind()
        val firstGroup = BracketGroupId(0, 7)
        val secondGroup = BracketGroupId(1, 7)
        val builder = CapturedBracketTokens.Builder(1)
        builder.append(CapturedBracketToken(open, firstGroup, null, false, BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0))
        builder.append(CapturedBracketToken(open, secondGroup, "tag", true, BracketRole.OPEN, StructuralRole.NONE, 1, 1, 0))
        builder.append(CapturedBracketToken(close, firstGroup, null, false, BracketRole.CLOSE, StructuralRole.NONE, 2, 1, 0))
        builder.append(CapturedBracketToken(close, secondGroup, "tag", true, BracketRole.CLOSE, StructuralRole.NONE, 3, 1, 0))
        val batch = builder.seal(4, true, 4, BraceMatcherAvailability.AVAILABLE)
        val draft = PairTable.draft()
        val session = PairingMachine<TokenKind, BracketGroupId>().newSession(draft, CancellationProbe {}, 10)
        for (index in 0 until batch.size) {
            var step = batch.begin(index, session)
            while (step == PairingMachine.Step.NEEDS_RULE) {
                val request = session.ruleRequest()
                step = session.resume(request.openToken() === open && request.closeToken() === close)
            }
            assertThat(step).isEqualTo(PairingMachine.Step.ACCEPTED)
        }
        val pairs = draft.freeze()
        assertThat(pairs.size()).isEqualTo(2)
        val geometry = (0 until pairs.size()).map { pairs.openOffsetAt(it) to pairs.closeOffsetAt(it) }
        assertThat(geometry).containsExactlyInAnyOrder(0 to 2, 1 to 3)
    }

    private fun CapturedBracketTokens.Builder.append(token: CapturedBracketToken) = append(
        token.kind, token.group, token.context, token.strictContext, token.role, token.structuralRole,
        token.offset, token.tokenLength, token.line,
    )
}
