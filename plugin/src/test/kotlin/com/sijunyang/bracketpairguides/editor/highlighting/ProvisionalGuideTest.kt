package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import org.assertj.core.api.Assertions.assertThat
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

        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isSameAs(guideHighlighter)
        assertThat(activeGuideState()?.guide?.pair?.closeOffset).isEqualTo(pair.closeOffset + "fast ".length)
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

        assertThat(activeGuideState()?.guide?.guideColumn).isEqualTo(2)
        val closeLineStart = source.indexOf("  }\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(closeLineStart, "  ")
        }

        val adjustedGuide = checkNotNull(
            activeGuideState()?.guide,
        )
        assertThat(adjustedGuide.guideColumn).isEqualTo(2)
        assertThat(adjustedGuide.pair.closeOffset).isEqualTo(editor.document.text.indexOf('}', closeLineStart))

        applyPass()

        val recognizedGuide = checkNotNull(activeGuideState()?.guide)
        assertThat(recognizedGuide.guideColumn).isEqualTo(4)
        assertThat(recognizedGuide.pair.closeOffset).isEqualTo(editor.document.text.indexOf('}', closeLineStart))
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
        assertThat(adjustedGuide.guideColumn).isEqualTo(0)
        assertThat(adjustedGuide.pair.openLine).isEqualTo(0)
        assertThat(adjustedGuide.pair.closeLine).isEqualTo(2)

        applyPass()

        val recognizedGuide = checkNotNull(activeGuideState()?.guide)
        assertThat(recognizedGuide.guideColumn).isEqualTo(2)
        assertThat(recognizedGuide.pair.openLine).isEqualTo(0)
        assertThat(recognizedGuide.pair.closeLine).isEqualTo(2)
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
        assertThat(adjustedPair.openOffset).isEqualTo(previousPair.openOffset)
        assertThat(adjustedPair.openOffset).isNotEqualTo(start)

        applyPass()

        val recognizedPair = checkNotNull(activeGuideState()?.guide?.pair)
        assertThat(recognizedPair.openOffset).isEqualTo(start)
        assertThat(recognizedPair.closeOffset).isEqualTo(end + 1)
        assertThat(recognizedPair.depth).isEqualTo(2)
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

        assertThat(activeGuide()).isSameAs(persistentGuide)
        assertThat(guideHighlighters()).hasSize(1)
        assertThat(elapsed)
            .describedAs("2k active-pair switches took ${elapsed}ms")
            .isLessThan(2_000)
    }
}
