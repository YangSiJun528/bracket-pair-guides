package com.sijunyang.bracketpairguides.analysis.pairing

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LanguageBraceMatchersTest : BasePlatformTestCase() {
    fun testTextMatcherIsPresentedAsCustomFileTypeCapability() {
        val family = LanguageBraceMatchers.supportedLanguages().single { language ->
            language.id == "TEXT"
        }

        assertEquals("Custom file types", family.displayName)
        assertTrue(family.familyDisplayNames.contains("Plain text"))
        assertTrue(family.constraintDescription.orEmpty().contains("raw plain text"))
    }

    fun testMatcherFamilyWithoutStandaloneFileTypeIsExposedToSettings() {
        assertNull(EMBEDDED_LANGUAGE.associatedFileType)
        assertNull(EMBEDDED_DIALECT.associatedFileType)
        LanguageBraceMatching.INSTANCE.addExplicitExtension(EMBEDDED_LANGUAGE, MATCHER)

        try {
            val supported = LanguageBraceMatchers.supportedLanguages()
            val family = supported.single { language ->
                language.id == EMBEDDED_LANGUAGE.id
            }

            assertEquals(EMBEDDED_LANGUAGE.displayName, family.displayName)
            assertEquals(
                setOf(EMBEDDED_LANGUAGE.displayName, EMBEDDED_DIALECT.displayName),
                family.familyDisplayNames.toSet(),
            )
            assertEquals(
                EMBEDDED_LANGUAGE.id,
                LanguageBraceMatchers.resolve(EMBEDDED_DIALECT)?.capabilityId,
            )
        } finally {
            LanguageBraceMatching.INSTANCE.removeExplicitExtension(
                EMBEDDED_LANGUAGE,
                MATCHER,
            )
        }
    }

    private companion object {
        val EMBEDDED_LANGUAGE = object : Language(
            "BRACKET_PAIR_GUIDES_EMBEDDED_SETTINGS_TEST",
        ) {}
        val EMBEDDED_DIALECT = object : Language(
            EMBEDDED_LANGUAGE,
            "BRACKET_PAIR_GUIDES_EMBEDDED_DIALECT_SETTINGS_TEST",
        ) {}
        val LEFT = IElementType("EMBEDDED_SETTINGS_LEFT", EMBEDDED_LANGUAGE)
        val RIGHT = IElementType("EMBEDDED_SETTINGS_RIGHT", EMBEDDED_LANGUAGE)
        val MATCHER = object : PairedBraceMatcher {
            override fun getPairs(): Array<BracePair> =
                arrayOf(BracePair(LEFT, RIGHT, false))

            override fun isPairedBracesAllowedBeforeType(
                lbraceType: IElementType,
                contextType: IElementType?,
            ): Boolean = true

            override fun getCodeConstructStart(
                file: PsiFile,
                openingBraceOffset: Int,
            ): Int = openingBraceOffset
        }
    }
}
