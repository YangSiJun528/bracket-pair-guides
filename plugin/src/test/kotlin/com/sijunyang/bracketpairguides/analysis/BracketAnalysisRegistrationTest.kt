package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BracketAnalysisRegistrationTest : BasePlatformTestCase() {
    fun testRegisteredApplicationServiceIsSingletonAndListsLanguages() {
        val first = service<BracketAnalysis>()
        val second = service<BracketAnalysis>()

        assertSame(first, second)
        assertEquals(
            first.installedLanguages().map { family -> family.id },
            second.installedLanguages().map { family -> family.id },
        )
    }
}
