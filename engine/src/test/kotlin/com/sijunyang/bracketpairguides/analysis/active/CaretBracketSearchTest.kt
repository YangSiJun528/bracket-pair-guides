package com.sijunyang.bracketpairguides.analysis.active

import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.analysis.ActivePairKnowledge
import com.intellij.ide.highlighter.custom.CustomFileHighlighter
import com.intellij.ide.highlighter.custom.SyntaxTable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.AbstractFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CaretBracketSearchTest : BasePlatformTestCase() {
    fun testDefaultBudgetResolvesACommonNestedJavaPair() {
        val source = "class Sample { void run() { if (ready) { call(); } } }"
        myFixture.configureByText("CommonNested.java", source)
        val caretOffset = source.indexOf("call") + 2

        val resolution = resolve(
            search(myFixture.file.fileType),
            caretOffset,
        )

        val pair = (resolution as? ActivePairKnowledge.Known)?.pair
        assertNotNull(pair)
        val expectedOpen = source.indexOf('{', source.indexOf("if"))
        val expectedClose = source.indexOf('}', source.indexOf("call"))
        assertEquals(expectedOpen, pair?.openOffset)
        assertEquals(expectedClose, pair?.closeOffset)
    }

    fun testDefaultTokenCeilingReturnsIncompleteBeforeADeepOpeningBrace() {
        val source = buildString {
            append("class Budget { void run() { int value = 0;")
            repeat(600) { append("value++;") }
            append("int target = value; } }")
        }
        myFixture.configureByText("TokenCeiling.java", source)

        val resolution = resolve(
            search(myFixture.file.fileType),
            source.indexOf("target") + 2,
        )

        assertSame(ActivePairKnowledge.Unknown, resolution)
    }

    fun testLanguageFamilyGateStillReturnsAnAuthoritativeMiss() {
        val source = "class Disabled { void run() { call(); } }"
        myFixture.configureByText("DisabledFamily.java", source)
        val disabledCapabilityId = checkNotNull(
            BraceLanguageCatalog().definitionFor(myFixture.file.language)?.capabilityId,
        )

        val resolution = resolve(
            search(myFixture.file.fileType) { capabilityId ->
                capabilityId != disabledCapabilityId
            },
            source.indexOf("call") + 2,
        )

        assertEquals(ActivePairKnowledge.Known(null), resolution)
    }

    fun testCustomFileTypeImmediateLookupMatchesFullRecognitionAndTextGate() {
        val source = "{ [ ( value ) ] }"
        myFixture.configureByText("Custom.txt", source)
        val syntaxTable = SyntaxTable().apply {
            isHasBraces = true
            isHasBrackets = true
            isHasParens = true
        }
        val customFileType = AbstractFileType(syntaxTable)
        (myFixture.editor as EditorEx).setHighlighter(
            LexerEditorHighlighter(
                CustomFileHighlighter(syntaxTable),
                myFixture.editor.colorsScheme,
            ),
        )
        val caretOffset = source.indexOf("value") + 2

        val enabled = resolve(
            search(customFileType),
            caretOffset,
        )
        val pair = (enabled as? ActivePairKnowledge.Known)?.pair
        assertNotNull(pair)
        assertEquals(source.indexOf('('), pair?.openOffset)
        assertEquals(source.indexOf(')'), pair?.closeOffset)

        val disabled = resolve(
            search(customFileType) { capabilityId -> capabilityId != "TEXT" },
            caretOffset,
        )
        assertEquals(ActivePairKnowledge.Known(null), disabled)
    }

    private fun resolve(
        search: CaretBracketSearch,
        caretOffset: Int,
    ): ActivePairKnowledge = ReadAction.compute<
        ActivePairKnowledge,
        RuntimeException,
    > {
        search.findInnermost(myFixture.editor, caretOffset)
    }

    private fun search(
        fileType: FileType,
        isLanguageEnabled: (String) -> Boolean = { true },
    ): CaretBracketSearch = CaretBracketSearch(
        fileType = fileType,
        languages = BraceLanguageCatalog(),
        isLanguageEnabled = isLanguageEnabled,
    )
}
