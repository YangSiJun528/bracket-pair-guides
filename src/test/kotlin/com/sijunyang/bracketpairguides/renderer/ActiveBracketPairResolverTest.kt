package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.intellij.openapi.application.ReadAction
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

        val pair = (resolution as? ActiveBracketPairResolution.Complete)?.pair
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

        assertSame(ActiveBracketPairResolution.Incomplete, resolution)
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

        assertSame(ActiveBracketPairResolution.Incomplete, resolution)
        assertTrue(clock.reads >= 2)
    }

    fun testLanguageFamilyGateStillReturnsAnAuthoritativeMiss() {
        val source = "class Disabled { void run() { call(); } }"
        myFixture.configureByText("DisabledFamily.java", source)
        val disabledCapabilityId = checkNotNull(
            LanguageBraceMatchers.capabilityOwner(myFixture.file.language),
        ).id

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

        assertEquals(ActiveBracketPairResolution.Complete(null), resolution)
    }

    private fun resolve(
        resolver: EditorHighlighterActiveBracketPairResolver,
        caretOffset: Int,
    ): ActiveBracketPairResolution = ReadAction.compute<
        ActiveBracketPairResolution,
        RuntimeException,
    > {
        resolver.findInnermost(myFixture.editor, caretOffset)
    }

    private class DeadlineClock(
        private val deadlineNanos: Long,
    ) : MonotonicClock {
        var reads: Int = 0
            private set

        override fun nowNanos(): Long {
            return if (reads++ == 0) 0L else deadlineNanos
        }
    }

    private companion object {
        const val DEADLINE_NANOS = 4_000_000L
        val FROZEN_CLOCK = MonotonicClock { 0L }
    }
}
