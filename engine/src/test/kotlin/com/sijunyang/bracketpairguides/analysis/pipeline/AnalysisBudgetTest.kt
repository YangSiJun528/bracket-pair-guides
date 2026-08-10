package com.sijunyang.bracketpairguides.analysis.pipeline

import com.sijunyang.bracketpairguides.analysis.AnalysisLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisBudgetTest {
    @Test
    fun `initial product capacities are two hundred thousand`() {
        assertEquals(200_000, AnalysisBudget.pairCapacity.maximumPairCount)
        assertEquals(200_000, AnalysisBudget.maximumPendingOpenCount)
    }

    @Test
    fun `all current index layouts admit the product pair capacity`() {
        val pairCount = AnalysisBudget.pairCapacity.maximumPairCount

        assertNull(AnalysisBudget.limitAt(pairCount, tokenOnlyLayout(), 0L))
        assertNull(AnalysisBudget.limitAt(pairCount, activeOnlyLayout(), 0L))
        assertNull(AnalysisBudget.limitAt(pairCount, fullLayout(), 0L))
        assertNull(
            AnalysisBudget.limitAt(
                pairCount = pairCount,
                layout = fullLayout(guidePosition = true),
                guidePayloadBytes = MAXIMUM_GUIDE_PAYLOAD_BYTES,
            ),
        )
    }

    @Test
    fun `pair count is rejected independently of the selected layout`() {
        val overCapacity = AnalysisBudget.pairCapacity.maximumPairCount + 1

        assertEquals(
            AnalysisLimit.PAIR_CAPACITY,
            AnalysisBudget.limitAt(overCapacity, tokenOnlyLayout(), 0L),
        )
    }

    @Test
    fun `concurrently retained guide and pair indexes share the working ceiling`() {
        assertEquals(
            AnalysisLimit.WORKING_MEMORY,
            AnalysisBudget.limitAt(
                pairCount = AnalysisBudget.pairCapacity.maximumPairCount,
                layout = fullLayout(guidePosition = true),
                guidePayloadBytes = 40L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun `byte arithmetic fails closed on overflow`() {
        assertEquals(
            AnalysisLimit.WORKING_MEMORY,
            AnalysisBudget.limitAt(
                pairCount = 1,
                layout = fullLayout(guidePosition = true),
                guidePayloadBytes = Long.MAX_VALUE,
            ),
        )
    }

    private fun tokenOnlyLayout(): IndexLayout = IndexLayout(
        activePair = false,
        tokenStorage = TokenStorage.DETACHED,
        guidePosition = false,
    )

    private fun activeOnlyLayout(): IndexLayout = IndexLayout(
        activePair = true,
        tokenStorage = TokenStorage.NONE,
        guidePosition = false,
    )

    private fun fullLayout(guidePosition: Boolean = false): IndexLayout = IndexLayout(
        activePair = true,
        tokenStorage = TokenStorage.ATTACHED,
        guidePosition = guidePosition,
    )

    private companion object {
        const val MAXIMUM_GUIDE_PAYLOAD_BYTES = 16L * 1024 * 1024
    }
}
