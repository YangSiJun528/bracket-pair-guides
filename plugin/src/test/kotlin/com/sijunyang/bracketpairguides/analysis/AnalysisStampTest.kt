package com.sijunyang.bracketpairguides.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisStampTest {
    @Test
    fun `tab size does not invalidate token-only analysis`() {
        val completed = stamp(
            tabSize = 4,
            capabilities = AnalysisCapabilities(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
        )
        val required = completed.copy(tabSize = 8)

        assertTrue(completed.satisfies(required))
    }

    @Test
    fun `tab size still invalidates guide-position analysis`() {
        val completed = stamp(
            tabSize = 4,
            capabilities = AnalysisCapabilities(
                tokens = false,
                activePair = true,
                guidePosition = true,
            ),
        )
        val required = completed.copy(tabSize = 8)

        assertFalse(completed.satisfies(required))
    }

    private fun stamp(
        tabSize: Int,
        capabilities: AnalysisCapabilities,
    ): AnalysisStamp = AnalysisStamp(
        documentStamp = 7L,
        tabSize = tabSize,
        highlighterIdentity = 11,
        capabilities = capabilities,
        disabledLanguageIds = setOf("test.matcher"),
    )
}
