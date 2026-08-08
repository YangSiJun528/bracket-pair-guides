package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.api.BracketGuide
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import kotlin.random.Random

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

        assertEquals(Int.MAX_VALUE - 1, guide.guideColumn)
        assertEquals(1, guide.anchorLine)
    }

    fun testBoundedResolverMatchesTheExactIndexAcrossRandomIndentationRanges() {
        val random = Random(0x61D3_5EED)

        repeat(20) { sample ->
            val lineCount = random.nextInt(8, 65)
            val lines = List(lineCount) { line ->
                val indentation = buildString {
                    repeat(random.nextInt(0, 13)) {
                        append(if (random.nextBoolean()) ' ' else '\t')
                    }
                }
                if (line != 0 && random.nextInt(5) == 0) {
                    indentation.ifEmpty { " " }
                } else {
                    "${indentation}value-$line"
                }
            }
            val source = lines.joinToString("\n")
            myFixture.configureByText("RandomIndentation-$sample.txt", source)
            val editor = myFixture.editor
            val tabSize = listOf(1, 2, 4, 8)[random.nextInt(4)]
            editor.settings.setTabSize(tabSize)
            repeat(80) { range ->
                val openLine = random.nextInt(0, lineCount - 1)
                val closeLine = random.nextInt(openLine + 1, lineCount)
                val pair = BracketPair(
                    openOffset = editor.document.getLineStartOffset(openLine),
                    openTokenLength = 1,
                    closeOffset = editor.document.getLineStartOffset(closeLine),
                    closeTokenLength = 1,
                    depth = 0,
                    openLine = openLine,
                    closeLine = closeLine,
                )
                val exact = exactGuide(lines, pair, tabSize)
                val immediate = ActiveGuidePositionResolver.resolve(
                    editor = editor,
                    pair = pair,
                    previous = null,
                    currentAnchorLine = null,
                    change = null,
                )

                assertEquals(
                    "sample=$sample range=$range lines=$openLine..$closeLine column",
                    exact.guideColumn,
                    immediate.guideColumn,
                )
                assertEquals(
                    "sample=$sample range=$range lines=$openLine..$closeLine anchor",
                    exact.anchorLine,
                    immediate.anchorLine,
                )
            }
        }
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

    private fun exactGuide(
        lines: List<String>,
        pair: BracketPair,
        tabSize: Int,
    ): BracketGuide {
        var minimumColumn = Int.MAX_VALUE
        var anchorLine = pair.openLine + 1
        for (line in pair.openLine + 1..pair.closeLine) {
            val column = indentationColumn(lines[line], tabSize) ?: continue
            if (column < minimumColumn) {
                minimumColumn = column
                anchorLine = line
            }
        }
        return BracketGuide(
            pair = pair,
            guideColumn = minimumColumn.takeUnless { it == Int.MAX_VALUE } ?: 0,
            anchorLine = anchorLine,
        )
    }

    private fun indentationColumn(line: String, tabSize: Int): Int? {
        var column = 0
        for (character in line) {
            when (character) {
                ' ' -> column++
                '\t' -> column += tabSize - column % tabSize
                else -> return column
            }
        }
        return null
    }
}
