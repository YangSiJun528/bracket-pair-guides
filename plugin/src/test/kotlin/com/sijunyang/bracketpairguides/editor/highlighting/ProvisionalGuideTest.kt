package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.system.measureTimeMillis

internal class ProvisionalGuideTest : BracketGuideHighlightingFixture() {
    fun testDocumentEditAdjustsTheActiveGuideBeforeFullRecognitionCompletes() {
        val source = "x { active content } y"
        myFixture.configureByText("Priority.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        val pairs = {
            collections++
            listOf(pair)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(pairs)
        val guideHighlighter = checkNotNull(activeGuide())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content"), "fast ")
        }

        assertEquals(1, collections)
        assertSame(guideHighlighter, activeGuide())
        assertEquals(
            pair.closeOffset + "fast ".length,
            activeGuideState()?.guide?.pair?.closeOffset,
        )
    }

    fun testDocumentEditKeepsAdjustedGuideUntilBackgroundAnalysisRecalculatesColumn() {
        val source = """
            class Sample {
              void run() {
                call();
              }
            }
        """.trimIndent()
        myFixture.configureByText("DeferredColumn.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        applyPass()

        assertEquals(
            2,
            activeGuideState()?.guide?.guideColumn,
        )
        val closeLineStart = source.indexOf("  }\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(closeLineStart, "  ")
        }

        val adjustedGuide = checkNotNull(
            activeGuideState()?.guide,
        )
        assertEquals(2, adjustedGuide.guideColumn)
        assertEquals(
            editor.document.text.indexOf('}', closeLineStart),
            adjustedGuide.pair.closeOffset,
        )

        applyPass()

        val recognizedGuide = checkNotNull(activeGuideState()?.guide)
        assertEquals(4, recognizedGuide.guideColumn)
        assertEquals(
            editor.document.text.indexOf('}', closeLineStart),
            recognizedGuide.pair.closeOffset,
        )
    }

    fun testNewMultilineLayoutWaitsForBackgroundAnalysisBeforePublishingItsGuideColumn() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("DeferredMultiline.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        applyPass()
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(
                openingBrace + 1,
                closingBrace,
                "\n    call();\n  ",
            )
        }

        val adjustedGuide = checkNotNull(
            activeGuideState()?.guide,
        )
        assertEquals(0, adjustedGuide.guideColumn)
        assertEquals(0, adjustedGuide.pair.openLine)
        assertEquals(2, adjustedGuide.pair.closeLine)

        applyPass()

        val recognizedGuide = checkNotNull(activeGuideState()?.guide)
        assertEquals(2, recognizedGuide.guideColumn)
        assertEquals(0, recognizedGuide.pair.openLine)
        assertEquals(2, recognizedGuide.pair.closeLine)
    }

    fun testBracketEditWaitsForBackgroundAnalysisBeforePublishingANewInnermostPair() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("DeferredPair.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call") + 2)
        applyPass()
        val previousPair = checkNotNull(activeGuideState()?.guide?.pair)
        val start = source.indexOf("call")
        val end = source.indexOf(';', start) + 1

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(end, ")")
            editor.document.insertString(start, "(")
        }

        val adjustedPair = checkNotNull(
            activeGuideState()?.guide?.pair,
        )
        assertEquals(previousPair.openOffset, adjustedPair.openOffset)
        assertTrue(adjustedPair.openOffset != start)

        applyPass()

        val recognizedPair = checkNotNull(activeGuideState()?.guide?.pair)
        assertEquals(start, recognizedPair.openOffset)
        assertEquals(end + 1, recognizedPair.closeOffset)
        assertEquals(2, recognizedPair.depth)
    }

    fun testRapidPairSwitchesReuseOneGuideHighlighter() {
        val pairCount = 10_000
        val source = "(x)".repeat(pairCount)
        myFixture.configureByText("Switches.txt", source)
        val pairs = List(pairCount) { index ->
            val openOffset = index * 3
            BracketPair(openOffset, 1, openOffset + 2, 1, 0, 0, 0)
        }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        applyPass({ pairs }) { TextRange(0, 256) }
        val persistentGuide = checkNotNull(activeGuide())

        val elapsed = measureTimeMillis {
            var index = 1
            while (index <= 2_000) {
                editor.caretModel.moveToOffset(index * 3 + 1)
                index++
            }
        }

        assertSame(persistentGuide, activeGuide())
        assertEquals(1, guideHighlighters().size)
        assertTrue("2k active-pair switches took ${elapsed}ms", elapsed < 2_000)
    }
}
