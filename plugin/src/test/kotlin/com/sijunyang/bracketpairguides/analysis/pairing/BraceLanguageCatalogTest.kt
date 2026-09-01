package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import org.assertj.core.api.Assertions.assertThat

class BraceLanguageCatalogTest : BasePlatformTestCase() {
    fun testInstalledFamiliesAreStableUiReadyValues() {
        val families = BraceLanguageCatalog().installedFamilies()

        assertThat(families).isNotEmpty()
        assertThat(families.map { family -> family.id }).isSorted()
        assertThat(families).allSatisfy { family ->
            assertThat(family.id).isNotBlank()
            assertThat(family.displayName).isNotBlank()
            assertThat(family.memberDisplayNames).isNotEmpty()
        }
    }

    fun testTextMatcherIsPresentedAsCustomFileTypeCapability() {
        val family: BraceLanguageFamily =
            BraceLanguageCatalog().installedFamilies().single { language ->
                language.id == "TEXT"
            }

        assertThat(family.id).isEqualTo("TEXT")
        assertThat(family.memberDisplayNames).contains("Plain text")
    }

    fun testLegacyFileTypeMatcherLanguageIsExposedToSettings() {
        myFixture.configureByText("Legacy.xml", "<root/>")
        val language = (myFixture.file.fileType as LanguageFileType).language

        assertThat(LanguageBraceMatching.INSTANCE.forLanguage(language)).isNull()
        val family =
            BraceLanguageCatalog().installedFamilies().single { candidate ->
                candidate.id == language.id
            }

        assertThat(family.displayName).isEqualTo(language.displayName)
        assertThat(family.memberDisplayNames).contains(language.displayName)
    }

    fun testMatcherFamilyWithoutStandaloneFileTypeIsExposedToSettings() {
        assertThat(EMBEDDED_LANGUAGE.associatedFileType).isNull()
        assertThat(EMBEDDED_DIALECT.associatedFileType).isNull()
        LanguageBraceMatching.INSTANCE.addExplicitExtension(EMBEDDED_LANGUAGE, MATCHER)

        try {
            val supported = BraceLanguageCatalog().installedFamilies()
            val family: BraceLanguageFamily =
                supported.single { language ->
                    language.id == EMBEDDED_LANGUAGE.id
                }

            assertThat(family.displayName).isEqualTo(EMBEDDED_LANGUAGE.displayName)
            assertThat(family.memberDisplayNames).containsExactlyInAnyOrder(
                EMBEDDED_LANGUAGE.displayName,
                EMBEDDED_DIALECT.displayName,
            )
            assertThat(
                BraceLanguageCatalog().definitionFor(EMBEDDED_DIALECT)?.capabilityId,
            ).isEqualTo(EMBEDDED_LANGUAGE.id)
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(
                EMBEDDED_LANGUAGE,
                MATCHER,
            )
        }
    }

    private companion object {
        val EMBEDDED_LANGUAGE =
            object : Language(
                "BRACKET_PAIR_GUIDES_EMBEDDED_SETTINGS_TEST",
            ) {}
        val EMBEDDED_DIALECT =
            object : Language(
                EMBEDDED_LANGUAGE,
                "BRACKET_PAIR_GUIDES_EMBEDDED_DIALECT_SETTINGS_TEST",
            ) {}
        val LEFT = IElementType("EMBEDDED_SETTINGS_LEFT", EMBEDDED_LANGUAGE)
        val RIGHT = IElementType("EMBEDDED_SETTINGS_RIGHT", EMBEDDED_LANGUAGE)
        val MATCHER =
            object : PairedBraceMatcher {
                override fun getPairs(): Array<BracePair> = arrayOf(BracePair(LEFT, RIGHT, false))

                override fun isPairedBracesAllowedBeforeType(
                    lbraceType: IElementType,
                    contextType: IElementType?,
                ): Boolean = true

                override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
            }
    }
}
