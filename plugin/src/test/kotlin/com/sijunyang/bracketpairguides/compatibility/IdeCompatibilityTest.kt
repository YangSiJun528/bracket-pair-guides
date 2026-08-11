package com.sijunyang.bracketpairguides.compatibility

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class IdeCompatibilityTest : BasePlatformTestCase() {
    fun testSupportedWhenLanguageBraceMatchingIsPresent() {
        val checkedExtensionPoints = mutableListOf<String>()

        val compatibility = IdeCompatibility.from { extensionPoint ->
            checkedExtensionPoints += extensionPoint
            true
        }

        assertThat(compatibility).isSameAs(IdeCompatibility.Supported)
        assertThat(checkedExtensionPoints).containsExactly("com.intellij.lang.braceMatcher")
    }

    fun testUnsupportedWhenLanguageBraceMatchingIsAbsent() {
        val compatibility = IdeCompatibility.from { false }

        assertThat(compatibility).isEqualTo(IdeCompatibility.Unsupported("com.intellij.lang.braceMatcher"))
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

        assertThat(appearances).containsExactly(
            UnsupportedIdeWarning.WarningText(
                title = "Unsupported IDE",
                message =
                    "Bracket Pair Guides is not supported in this IDE because it " +
                        "does not provide the com.intellij.lang.braceMatcher " +
                        "extension point.",
            ),
        )
    }

}
