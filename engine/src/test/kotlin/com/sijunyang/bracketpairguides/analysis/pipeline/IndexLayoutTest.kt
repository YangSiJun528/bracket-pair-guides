package com.sijunyang.bracketpairguides.analysis.pipeline

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexLayoutTest {
    @Test
    fun `token metadata detaches when active pairs are not retained`() {
        val plan = IndexLayout.forCoverage(coverage(tokens = true))

        assertFalse(plan.activePair)
        assertEquals(TokenStorage.DETACHED, plan.tokenStorage)
    }

    @Test
    fun `token metadata stays attached to retained active pairs`() {
        val plan = IndexLayout.forCoverage(
            coverage(tokens = true, activePair = true),
        )

        assertTrue(plan.activePair)
        assertEquals(TokenStorage.ATTACHED, plan.tokenStorage)
    }

    @Test
    fun `disabled token presentation omits its index`() {
        val plan = IndexLayout.forCoverage(coverage(activePair = true))

        assertEquals(TokenStorage.NONE, plan.tokenStorage)
    }

    private fun coverage(
        tokens: Boolean = false,
        activePair: Boolean = false,
    ): AnalysisCoverage = AnalysisCoverage(
        tokens = tokens,
        activePair = activePair,
        guidePosition = false,
    )
}
