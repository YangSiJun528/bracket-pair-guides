package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

class ActiveGuidePositionResolverTest : BasePlatformTestCase() {
    fun testChangedCloserBoundaryRecomputesGuideInsteadOfReusingOverlappingPair() {
        val source = """
            (
                (
                    value
                    )
              )
        """.trimIndent()
        myFixture.configureByText("RematchedCloser.txt", source)
        val editor = myFixture.editor
        val innerOpen = source.indexOf('(', startIndex = 1)
        val innerClose = source.indexOf(')', startIndex = innerOpen)
        val oldPair = BracketPair(
            openOffset = innerOpen,
            openTokenLength = 1,
            closeOffset = innerClose,
            closeTokenLength = 1,
            depth = 1,
            openLine = 1,
            closeLine = 3,
        )

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(innerClose, innerClose + 1)
        }
        val newPair = oldPair.copy(
            closeOffset = editor.document.text.lastIndexOf(')'),
            closeLine = 4,
        )

        val guide = ActiveGuidePositionResolver.resolve(
            editor = editor,
            pair = newPair,
            previous = BracketGuide(oldPair, guideColumn = 8, anchorLine = 2),
            currentAnchorLine = 2,
            change = DocumentChange(
                offset = innerClose,
                mayAffectGuidePosition = false,
            ),
        )

        assertEquals(2, guide.guideColumn)
        assertEquals(4, guide.anchorLine)
    }

    fun testSharedCharacterBudgetUsesClosingIndentAsDeterministicFallback() {
        val longIndent = " ".repeat(40_000)
        val source = "{\n${longIndent}value\nunindented()\n    }"
        myFixture.configureByText("LongIndent.txt", source)

        val guide = ActiveGuidePositionResolver.resolve(
            editor = myFixture.editor,
            pair = pairFor(source, closeLine = 3),
            previous = null,
            currentAnchorLine = null,
            change = null,
        )

        assertEquals(4, guide.guideColumn)
        assertEquals(3, guide.anchorLine)
    }

    fun testSharedLineBudgetUsesClosingIndentAsDeterministicFallback() {
        val body = List(300) { index ->
            if (index == 260) "value" else "        value"
        }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("ManyLines.txt", source)

        val guide = ActiveGuidePositionResolver.resolve(
            editor = myFixture.editor,
            pair = pairFor(source, closeLine = 301),
            previous = null,
            currentAnchorLine = null,
            change = null,
        )

        assertEquals(4, guide.guideColumn)
        assertEquals(301, guide.anchorLine)
    }

    fun testSameLineFallbackClampsAnOverflowedProviderLine() {
        val source = "{\n    value\n}"
        myFixture.configureByText("OverflowedLine.txt", source)
        val malformed = pairFor(source, closeLine = Int.MAX_VALUE).copy(
            openLine = Int.MAX_VALUE,
        )

        val guide = ActiveGuidePositionResolver.resolve(
            editor = myFixture.editor,
            pair = malformed,
            previous = null,
            currentAnchorLine = null,
            change = null,
        )

        assertEquals(0, guide.guideColumn)
        assertEquals(2, guide.anchorLine)
    }

    fun testIndentationColumnSaturatesBeforeOverflowOnImmediatePath() {
        val source = "{\n\t\tvalue}"
        myFixture.configureByText("OverflowedIndent.txt", source)
        myFixture.editor.settings.setTabSize(Int.MAX_VALUE)

        val guide = ActiveGuidePositionResolver.resolve(
            editor = myFixture.editor,
            pair = pairFor(source, closeLine = 1),
            previous = null,
            currentAnchorLine = null,
            change = null,
        )

        assertEquals(GuideIndentation.MAXIMUM_COLUMN, guide.guideColumn)
        assertEquals(1, guide.anchorLine)
    }

    private fun pairFor(source: String, closeLine: Int): BracketPair = BracketPair(
        openOffset = source.indexOf('{'),
        openTokenLength = 1,
        closeOffset = source.lastIndexOf('}'),
        closeTokenLength = 1,
        depth = 0,
        openLine = 0,
        closeLine = closeLine,
    )
}
