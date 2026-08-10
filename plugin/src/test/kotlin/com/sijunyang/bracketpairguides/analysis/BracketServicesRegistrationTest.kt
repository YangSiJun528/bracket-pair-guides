package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BracketServicesRegistrationTest : BasePlatformTestCase() {
    fun testRegisteredAnalysisAndLanguageInventoryAreSingletonServices() {
        val first = service<BracketAnalysis>()
        val second = service<BracketAnalysis>()
        val firstInventory = service<BraceLanguageInventory>()
        val secondInventory = service<BraceLanguageInventory>()

        assertSame(first, second)
        assertSame(firstInventory, secondInventory)
        assertEquals(
            firstInventory.families().map { family -> family.id },
            secondInventory.families().map { family -> family.id },
        )
    }
}
