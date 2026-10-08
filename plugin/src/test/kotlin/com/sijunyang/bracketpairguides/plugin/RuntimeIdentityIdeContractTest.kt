package com.sijunyang.bracketpairguides.plugin

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightPlatformTestCase

/** A configured target is not evidence that the selected IDE actually executed the contracts. */
class RuntimeIdentityIdeContractTest : LightPlatformTestCase() {
    fun testSelectedIdeActuallyRunsWithReadAccessAndComposition() {
        val baseline = checkNotNull(System.getProperty("contract.ide.baseline"))
        val info = ApplicationInfo.getInstance()
        assertEquals(baseline.toInt(), info.build.baselineVersion)
        assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
        assertNotNull(ApplicationManager.getApplication().getService(GuidePlugin::class.java))
        println("ACTUAL_IDE=${info.build};EXPECTED=${System.getProperty("contract.ide.version")}")
        println("ACTUAL_JAVA=${System.getProperty("java.home")};${System.getProperty("java.runtime.version")}")
    }
}
