package com.sijunyang.bracketpairguides.analysis

import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.api.AnalysisRevision
import com.intellij.openapi.fileTypes.PlainTextFileType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisRevisionTest {
    @Test
    fun `tab size does not invalidate token-only analysis`() {
        val completed = revision(
            tabSize = 4,
            capabilities = AnalysisCapabilities(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
        )
        val required = revision(
            tabSize = 8,
            capabilities = completed.capabilities,
        )

        assertTrue(completed.satisfies(required))
    }

    @Test
    fun `tab size still invalidates guide-position analysis`() {
        val completed = revision(
            tabSize = 4,
            capabilities = AnalysisCapabilities(
                tokens = false,
                activePair = true,
                guidePosition = true,
            ),
        )
        val required = revision(
            tabSize = 8,
            capabilities = completed.capabilities,
        )

        assertFalse(completed.satisfies(required))
    }

    private fun revision(
        tabSize: Int,
        capabilities: AnalysisCapabilities,
    ): AnalysisRevision = AnalysisRevision(
        documentStamp = 7L,
        tabSize = tabSize,
        highlighterIdentity = 11,
        fileType = PlainTextFileType.INSTANCE,
        capabilities = capabilities,
        disabledLanguageIds = setOf("test.matcher"),
    )
}
