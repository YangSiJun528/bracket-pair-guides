package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis

class BracketAnalysisRegistrationTest : BasePlatformTestCase() {
    fun testBracketAnalysisIsALightServiceSingleton() {
        val first = service<BracketAnalysis>()
        val second = service<BracketAnalysis>()

        assertSame(first, second)
    }
}
