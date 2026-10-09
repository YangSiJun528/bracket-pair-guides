package com.sijunyang.bracketpairguides.runtime.nativeproof

import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketGuide
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.ui.work.DisplayedGuide
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.NativeInterest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Admission and final authorization use the same owner exercised by the real SDK pipeline. */
class NativeEvidenceContractTest {
    private val a = BracketGuide(BracketPair(0, 1, 20, 1, 0, 0, 2), 2)
    private val b = BracketGuide(BracketPair(3, 1, 10, 1, 1, 0, 1), 4)
    private fun demand(revision: Long) = GuideDemand(
        0,
        AnalysisCoverage(true, true, true),
        emptySet(),
        true,
        revision,
        null,
        GuideChange.PRESENTATION,
        NativeInterest(0, true),
    )

    @Test fun `A to B to A never resurrects an old proof even with equal geometry`() {
        val gate = NativeEvidenceGate()
        val old = gate.admit(DisplayedGuide(0, a), 5, 0, demand(0))!!
        gate.invalidate()
        gate.admit(DisplayedGuide(1, b), 6, 0, demand(1))!!
        gate.invalidate()
        val current = gate.admit(DisplayedGuide(2, a), 5, 0, demand(2))!!
        assertThat(gate.isCurrent(old, demand(2), 5, 0)).isFalse()
        assertThat(gate.isCurrent(current, demand(2), 5, 0)).isTrue()
    }

    @Test fun `duplicate paint does not replace pending proof`() {
        val gate = NativeEvidenceGate()
        val request = demand(0)
        val proof = gate.admit(DisplayedGuide(0, a), 5, 0, request)!!
        assertThat(gate.admit(DisplayedGuide(0, a), 5, 0, request)).isNull()
        assertThat(gate.isCurrent(proof, request, 5, 0)).isTrue()
    }

    @Test fun `source episode caret visibility and close revoke proof authority`() {
        val gate = NativeEvidenceGate()
        val request = demand(0)
        val proof = gate.admit(DisplayedGuide(0, a), 5, 0, request)!!
        assertThat(gate.isCurrent(proof, request, 5, 1)).isFalse()
        assertThat(gate.isCurrent(proof, request, 6, 0)).isFalse()
        assertThat(gate.isCurrent(proof, request.copy(visible = false), 5, 0)).isFalse()
        assertThat(gate.isCurrent(proof, request.copy(nativeInterest = NativeInterest(1, true)), 5, 0)).isFalse()
        gate.invalidate()
        assertThat(gate.isCurrent(proof, request, 5, 0)).isFalse()
    }
}
