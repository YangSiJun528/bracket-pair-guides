package com.sijunyang.bracketpairguides.analysis

import com.sijunyang.bracketpairguides.analysis.pairing.LanguageBraceMatchers
import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.intellij.ide.highlighter.custom.CustomFileHighlighter
import com.intellij.ide.highlighter.custom.SyntaxTable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.impl.AbstractFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActiveBracketPairResolverTest : BasePlatformTestCase() {
    fun testDefaultBudgetResolvesACommonNestedJavaPair() {
        val source = "class Sample { void run() { if (ready) { call(); } } }"
        myFixture.configureByText("CommonNested.java", source)
        val caretOffset = source.indexOf("call") + 2

        val resolution = resolve(
            EditorHighlighterActiveBracketPairResolver(
                fileType = myFixture.file.fileType,
                clock = FROZEN_CLOCK,
            ),
            caretOffset,
        )

        val pair = (resolution as? ActivePairResult.Complete)?.pair
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
            EditorHighlighterActiveBracketPairResolver(
                fileType = myFixture.file.fileType,
                clock = FROZEN_CLOCK,
            ),
            source.indexOf("target") + 2,
        )

        assertSame(ActivePairResult.Incomplete, resolution)
    }

    fun testElapsedCeilingUsesAnInjectableClockAndReturnsIncomplete() {
        val source = "class Deadline { void run() { call(); } }"
        myFixture.configureByText("ElapsedCeiling.java", source)
        val clock = DeadlineClock(DEADLINE_NANOS)

        val resolution = resolve(
            EditorHighlighterActiveBracketPairResolver(
                fileType = myFixture.file.fileType,
                elapsedBudgetNanos = DEADLINE_NANOS,
                clock = clock,
            ),
            source.indexOf("call") + 2,
        )

        assertSame(ActivePairResult.Incomplete, resolution)
        assertTrue(clock.reads >= 2)
    }

    fun testLanguageFamilyGateStillReturnsAnAuthoritativeMiss() {
        val source = "class Disabled { void run() { call(); } }"
        myFixture.configureByText("DisabledFamily.java", source)
        val disabledCapabilityId = checkNotNull(
            LanguageBraceMatchers.resolve(myFixture.file.language)?.capabilityId,
        )

        val resolution = resolve(
            EditorHighlighterActiveBracketPairResolver(
                fileType = myFixture.file.fileType,
                isLanguageEnabled = { capabilityId ->
                    capabilityId != disabledCapabilityId
                },
                clock = FROZEN_CLOCK,
            ),
            source.indexOf("call") + 2,
        )

        assertEquals(ActivePairResult.Complete(null), resolution)
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
            EditorHighlighterActiveBracketPairResolver(
                fileType = customFileType,
                clock = FROZEN_CLOCK,
            ),
            caretOffset,
        )
        val pair = (enabled as? ActivePairResult.Complete)?.pair
        assertNotNull(pair)
        assertEquals(source.indexOf('('), pair?.openOffset)
        assertEquals(source.indexOf(')'), pair?.closeOffset)

        val disabled = resolve(
            EditorHighlighterActiveBracketPairResolver(
                fileType = customFileType,
                isLanguageEnabled = { capabilityId -> capabilityId != "TEXT" },
                clock = FROZEN_CLOCK,
            ),
            caretOffset,
        )
        assertEquals(ActivePairResult.Complete(null), disabled)
    }

    private fun resolve(
        resolver: EditorHighlighterActiveBracketPairResolver,
        caretOffset: Int,
    ): ActivePairResult = ReadAction.compute<
        ActivePairResult,
        RuntimeException,
    > {
        resolver.findInnermost(myFixture.editor, caretOffset)
    }

    private class DeadlineClock(
        private val deadlineNanos: Long,
    ) : () -> Long {
        var reads: Int = 0
            private set

        override fun invoke(): Long {
            return if (reads++ == 0) 0L else deadlineNanos
        }
    }

    private companion object {
        const val DEADLINE_NANOS = 4_000_000L
        val FROZEN_CLOCK: () -> Long = { 0L }
    }
}
