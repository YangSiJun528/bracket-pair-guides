package com.sijunyang.bracketpairguides.analysis.api

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BracketEngineServiceTest : BasePlatformTestCase() {
    fun testRegisteredApplicationServiceIsSingletonAndListsLanguages() {
        val first = service<BracketEngine>()
        val second = service<BracketEngine>()

        assertSame(first, second)
        assertEquals(
            first.installedLanguages().map { family -> family.id },
            second.installedLanguages().map { family -> family.id },
        )
    }
}
