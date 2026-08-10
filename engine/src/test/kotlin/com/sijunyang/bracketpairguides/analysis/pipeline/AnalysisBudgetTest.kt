package com.sijunyang.bracketpairguides.analysis.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisBudgetTest {
    @Test
    fun `product capacities bound completed and pending bracket state separately`() {
        assertEquals(100_000, AnalysisBudget.pairCapacity.maximumPairCount)
        assertEquals(50_000, AnalysisBudget.maximumPendingOpenCount)
    }
}
