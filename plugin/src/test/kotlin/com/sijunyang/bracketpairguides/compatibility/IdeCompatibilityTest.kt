package com.sijunyang.bracketpairguides.compatibility

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IdeCompatibilityTest : BasePlatformTestCase() {
    fun testSupportedWhenLanguageBraceMatchingIsPresent() {
        val checkedExtensionPoints = mutableListOf<String>()

        val compatibility = IdeCompatibility.from { extensionPoint ->
            checkedExtensionPoints += extensionPoint
            true
        }

        assertSame(IdeCompatibility.Supported, compatibility)
        assertEquals(listOf("com.intellij.lang.braceMatcher"), checkedExtensionPoints)
    }

    fun testUnsupportedWhenLanguageBraceMatchingIsAbsent() {
        val compatibility = IdeCompatibility.from { false }

        assertEquals(
            IdeCompatibility.Unsupported("com.intellij.lang.braceMatcher"),
            compatibility,
        )
    }

    fun testNoticeAppearsOnceForAnUnsupportedIde() {
        val appearances = mutableListOf<UnsupportedIdeWarning.WarningText>()
        val warning = UnsupportedIdeWarning { _: Project, content ->
            appearances += content
        }
        val compatibility = IdeCompatibility.Unsupported(
            "com.intellij.lang.braceMatcher",
        )

        warning.present(project, compatibility)
        warning.present(project, compatibility)

        assertEquals(
            listOf(
                UnsupportedIdeWarning.WarningText(
                    title = "Unsupported IDE",
                    message =
                        "Bracket Pair Guides is not supported in this IDE because it " +
                            "does not provide the com.intellij.lang.braceMatcher " +
                            "extension point.",
                ),
            ),
            appearances,
        )
    }

}
