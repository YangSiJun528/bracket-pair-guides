package com.sijunyang.bracketpairguides.analysis.snapshot

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class IndexLayoutTest {
    @Test
    fun `token metadata detaches when active pairs are not retained`() {
        val plan = IndexLayout.forCoverage(coverage(tokens = true))

        assertThat(plan.activePair).isFalse()
        assertThat(plan.tokenStorage).isEqualTo(TokenStorage.DETACHED)
    }

    @Test
    fun `token metadata stays attached to retained active pairs`() {
        val plan = IndexLayout.forCoverage(
            coverage(tokens = true, activePair = true),
        )

        assertThat(plan.activePair).isTrue()
        assertThat(plan.tokenStorage).isEqualTo(TokenStorage.ATTACHED)
    }

    @Test
    fun `disabled token presentation omits its index`() {
        val plan = IndexLayout.forCoverage(coverage(activePair = true))

        assertThat(plan.tokenStorage).isEqualTo(TokenStorage.NONE)
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
