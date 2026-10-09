package com.sijunyang.bracketpairguides.presentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.guide.GuideRepairCalculation
import org.assertj.core.api.Assertions.assertThat
import kotlin.random.Random

class GuidePositionFallbackUnitTest : BasePlatformTestCase() {
    fun testChangedCloserBoundaryRecomputesGuideInsteadOfReusingOverlappingPair() {
        val source =
            """
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
        val oldPair =
            BracketPair(
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
        val newPair =
            oldPair.copy(
                closeOffset = editor.document.text.lastIndexOf(')'),
                closeLine = 4,
            )

        val guide =
            calculateGuide(
                editor = editor,
                pair = newPair,
                previous = BracketGuide(oldPair, guideColumn = 8, anchorLine = 2),
                currentAnchorLine = 2,
            )

        assertThat(guide.guideColumn).isEqualTo(2)
        assertThat(guide.anchorLine).isEqualTo(4)
    }

    fun testSharedCharacterBudgetUsesClosingIndentAsDeterministicFallback() {
        val longIndent = " ".repeat(40_000)
        val source = "{\n${longIndent}value\nunindented()\n    }"
        myFixture.configureByText("LongIndent.txt", source)

        val guide =
            calculateGuide(
                editor = myFixture.editor,
                pair = pairFor(source, closeLine = 3),
                previous = null,
                currentAnchorLine = null,
            )

        assertThat(guide.guideColumn).isEqualTo(4)
        assertThat(guide.anchorLine).isEqualTo(3)
    }

    fun testSharedLineBudgetUsesClosingIndentAsDeterministicFallback() {
        val body =
            List(300) { index ->
                if (index == 260) "value" else "        value"
            }.joinToString("\n")
        val source = "{\n$body\n    }"
        myFixture.configureByText("ManyLines.txt", source)

        val guide =
            calculateGuide(
                editor = myFixture.editor,
                pair = pairFor(source, closeLine = 301),
                previous = null,
                currentAnchorLine = null,
            )

        assertThat(guide.guideColumn).isEqualTo(4)
        assertThat(guide.anchorLine).isEqualTo(301)
    }

    fun testSameLineFallbackClampsAnOverflowedProviderLine() {
        val source = "{\n    value\n}"
        myFixture.configureByText("OverflowedLine.txt", source)
        val malformed =
            pairFor(source, closeLine = Int.MAX_VALUE).copy(
                openLine = Int.MAX_VALUE,
            )

        val guide =
            calculateGuide(
                editor = myFixture.editor,
                pair = malformed,
                previous = null,
                currentAnchorLine = null,
            )

        assertThat(guide.guideColumn).isEqualTo(0)
        assertThat(guide.anchorLine).isEqualTo(2)
    }

    fun testIndentationColumnSaturatesBeforeOverflowOnBoundedFallback() {
        val source = "{\n\t\tvalue}"
        myFixture.configureByText("OverflowedIndent.txt", source)
        myFixture.editor.settings.setTabSize(Int.MAX_VALUE)

        val guide =
            calculateGuide(
                editor = myFixture.editor,
                pair = pairFor(source, closeLine = 1),
                previous = null,
                currentAnchorLine = null,
            )

        assertThat(guide.guideColumn).isEqualTo(Int.MAX_VALUE - 1)
        assertThat(guide.anchorLine).isEqualTo(1)
    }

    fun testBoundedFallbackMatchesTheExactIndexAcrossRandomIndentationRanges() {
        val random = Random(0x61D3_5EED)

        repeat(20) { sample ->
            val lineCount = random.nextInt(8, 65)
            val lines =
                List(lineCount) { line ->
                    val indentation =
                        buildString {
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
                val pair =
                    BracketPair(
                        openOffset = editor.document.getLineStartOffset(openLine),
                        openTokenLength = 1,
                        closeOffset = editor.document.getLineStartOffset(closeLine),
                        closeTokenLength = 1,
                        depth = 0,
                        openLine = openLine,
                        closeLine = closeLine,
                    )
                val exact = exactGuide(lines, pair, tabSize)
                val fallback =
                    calculateGuide(
                        editor = editor,
                        pair = pair,
                        previous = null,
                        currentAnchorLine = null,
                    )

                assertThat(fallback.guideColumn)
                    .describedAs("sample=$sample range=$range lines=$openLine..$closeLine column")
                    .isEqualTo(exact.guideColumn)
                assertThat(fallback.anchorLine)
                    .describedAs("sample=$sample range=$range lines=$openLine..$closeLine anchor")
                    .isEqualTo(exact.anchorLine)
            }
        }
    }

    fun testMissingMultilineGeometryIsHiddenWithoutReadingIndentation() {
        val source = "{\n" + " ".repeat(40_000) + "value\n    }"
        myFixture.configureByText("GeometryOnly.txt", source)
        val pair = pairFor(source, 2)
        assertThat(GuidePositionFallback.guideFor(myFixture.editor, pair, null, null)).isNull()
        assertThat(
            GuidePositionFallback.guideAfterChange(
                myFixture.editor,
                pair,
                pair,
                BracketGuide(pair, 4, 2),
                2,
                DocumentChange(2, 1, 1),
            ),
        ).isNull()
    }

    fun testEditOutsideTheOldPairSafelyReusesTrackedGuideGeometry() {
        val source = "x\n{\n    value\n  }"
        myFixture.configureByText("OutsideEdit.txt", source)
        val oldPair = pairFor(source, 3).copy(openLine = 1)
        val previous = BracketGuide(oldPair, 2, 3)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "prefix\n") }
        val adjusted = oldPair.copy(
            openOffset = oldPair.openOffset + 7,
            closeOffset = oldPair.closeOffset + 7,
            openLine = 2,
            closeLine = 4,
        )
        assertThat(
            GuidePositionFallback.guideAfterChange(
                myFixture.editor,
                adjusted,
                oldPair,
                previous,
                4,
                DocumentChange(0, 0, 7),
            ),
        ).isEqualTo(BracketGuide(adjusted, 2, 4))
    }

    private fun calculateGuide(
        editor: Editor,
        pair: BracketPair,
        previous: BracketGuide?,
        currentAnchorLine: Int?,
    ): BracketGuide {
        GuidePositionFallback.guideFor(editor, pair, previous, currentAnchorLine)?.let { return it }
        val lines = editor.document.text.lines()
        val first = (pair.openLine + 1).coerceIn(0, lines.lastIndex)
        val last = pair.closeLine.coerceIn(first, lines.lastIndex)
        val calculation = GuideRepairCalculation(
            pair,
            first..last,
            editor.settings.getTabSize(editor.project),
            false,
            currentAnchorLine,
            {},
        )
        while (true) {
            val line = calculation.nextLine() ?: break
            val text = lines[line]
            if (text.isEmpty()) {
                calculation.append("", true)
            } else {
                var after = 0
                do {
                    val next = minOf(after + 4_096, text.length)
                    val complete = calculation.append(text.substring(after, next), next == text.length)
                    after = next
                } while (!complete)
            }
        }
        return checkNotNull(calculation.result())
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

    private fun exactGuide(lines: List<String>, pair: BracketPair, tabSize: Int): BracketGuide {
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
