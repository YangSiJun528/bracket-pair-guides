package com.sijunyang.bracketpairguides.analysis.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BraceLanguageInventory

class IntellijBraceLanguageInventoryTest : BasePlatformTestCase() {
    fun testInstalledFamiliesAreStableUiReadyValues() {
        val inventory: BraceLanguageInventory = IntellijBraceLanguageInventory()

        val families = inventory.families()

        assertTrue(families.isNotEmpty())
        assertEquals(families.map { family -> family.id }.sorted(), families.map { it.id })
        assertTrue(families.all { family -> family.id.isNotBlank() })
        assertTrue(families.all { family -> family.displayName.isNotBlank() })
        assertTrue(families.all { family -> family.memberDisplayNames.isNotEmpty() })
        val textFamily = families.single { family -> family.id == "TEXT" }
        assertTrue("Plain text" in textFamily.memberDisplayNames)
    }
}
