package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.BracketPair
import org.assertj.core.api.Assertions.assertThat
import kotlin.system.measureTimeMillis

/**
 * Regression contract: every document addition, replacement, or removal must
 * synchronously refresh the geometry of the pair that is already being shown.
 * Assertions after a write command intentionally run before another pass;
 * never weaken them by waiting for or applying background analysis.
 */
internal class ProvisionalGuideTest : BracketGuideHighlightingFixture() {
    fun testDocumentEditAdjustsTheActiveGuideBeforeFullRecognitionCompletes() {
        val source = "x { active content } y"
        myFixture.configureByText("Priority.txt", source)
        val pair =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
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

    fun testInsertionRecalculatesTrackedGuideImmediatelyWithoutBackgroundAnalysis() {
        val source =
            """
            class Sample {
              void run() {
                call();
              }
            }
            """.trimIndent()
        myFixture.configureByText("ImmediateInsertion.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)
        val pair =
            BracketPair(
                openingBrace,
                1,
                closingBrace,
                1,
                1,
                editor.document.getLineNumber(openingBrace),
                editor.document.getLineNumber(closingBrace),
            )
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val persistentGuide = checkNotNull(activeGuide())

        assertThat(activeGuideState()?.guide?.guideColumn).isEqualTo(2)
        val closeLineStart = source.indexOf("  }\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(closeLineStart, "  ")
        }

        val adjustedGuide =
            checkNotNull(
                activeGuideState()?.guide,
            )
        assertThat(adjustedGuide.guideColumn).isEqualTo(4)
        assertThat(adjustedGuide.pair.closeOffset).isEqualTo(editor.document.text.indexOf('}', closeLineStart))
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isSameAs(persistentGuide)
        assertThat(guideHighlighters()).containsExactly(persistentGuide)
    }

    fun testReplacementPublishesNewMultilineGeometryImmediatelyWithoutBackgroundAnalysis() {
        val source = "class Sample { void run() { call(); } }"
        myFixture.configureByText("ImmediateMultiline.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)
        val pair = BracketPair(openingBrace, 1, closingBrace, 1, 1, 0, 0)
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val persistentGuide = checkNotNull(activeGuide())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(
                openingBrace + 1,
                closingBrace,
                "\n    call();\n  ",
            )
        }

        val adjustedGuide =
            checkNotNull(
                activeGuideState()?.guide,
            )
        assertThat(adjustedGuide.guideColumn).isEqualTo(2)
        assertThat(adjustedGuide.pair.openLine).isEqualTo(0)
        assertThat(adjustedGuide.pair.closeLine).isEqualTo(2)
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isSameAs(persistentGuide)
        assertThat(guideHighlighters()).containsExactly(persistentGuide)
    }

    fun testSameLengthWhitespaceReplacementRecalculatesTrackedGuideImmediately() {
        val source = "class Sample {\n  void run() {\n \tcall();\n \t}\n}"
        myFixture.configureByText("ImmediateWhitespaceReplacement.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)
        val pair = BracketPair(openingBrace, 1, closingBrace, 1, 1, 1, 3)
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val persistentGuide = checkNotNull(activeGuide())

        assertThat(activeGuideState()?.guide?.guideColumn).isEqualTo(4)
        val bodyStart = source.indexOf(" \tcall")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(
                bodyStart,
                closingBrace,
                "\t call();\n\t ",
            )
        }

        val adjustedGuide = checkNotNull(activeGuideState()?.guide)
        assertThat(adjustedGuide.guideColumn).isEqualTo(5)
        assertThat(adjustedGuide.pair.openOffset).isEqualTo(openingBrace)
        assertThat(adjustedGuide.pair.closeOffset).isEqualTo(closingBrace)
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isSameAs(persistentGuide)
        assertThat(guideHighlighters()).containsExactly(persistentGuide)
    }

    fun testRemovalRecalculatesTrackedGuideImmediatelyWithoutBackgroundAnalysis() {
        val source =
            """
            class Sample {
                void run() {
                    call();
                }
            }
            """.trimIndent()
        myFixture.configureByText("ImmediateRemoval.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)
        val pair = BracketPair(openingBrace, 1, closingBrace, 1, 1, 1, 3)
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val persistentGuide = checkNotNull(activeGuide())

        assertThat(activeGuideState()?.guide?.guideColumn).isEqualTo(4)
        val closeLineStart = source.lastIndexOf("    }\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(closeLineStart, closeLineStart + 2)
        }

        val adjustedGuide = checkNotNull(activeGuideState()?.guide)
        assertThat(adjustedGuide.guideColumn).isEqualTo(2)
        assertThat(adjustedGuide.pair.closeOffset).isEqualTo(closingBrace - 2)
        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isSameAs(persistentGuide)
        assertThat(guideHighlighters()).containsExactly(persistentGuide)
    }

    fun testRemovingTrackedEndpointClearsGuideImmediatelyWithoutBackgroundAnalysis() {
        val source = "class Sample {\n  void run() {\n    call();\n  }\n}"
        myFixture.configureByText("ImmediateEndpointRemoval.java", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("call"))
        val openingBrace = source.indexOf('{', source.indexOf("run"))
        val closingBrace = source.indexOf('}', openingBrace)
        val pair = BracketPair(openingBrace, 1, closingBrace, 1, 1, 1, 3)
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val previousGuide = checkNotNull(activeGuide())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(closingBrace, closingBrace + 1)
        }

        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isNull()
        assertThat(guideHighlighters()).isEmpty()
        assertThat(previousGuide.isValid).isFalse()
    }

    fun testInsertionInsideTrackedMultiCharacterEndpointClearsGuideImmediately() {
        val source = "x << content >> y"
        myFixture.configureByText("ImmediateMultiCharacterEndpoint.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val pair =
            BracketPair(
                openOffset = source.indexOf("<<"),
                openTokenLength = 2,
                closeOffset = source.indexOf(">>"),
                closeTokenLength = 2,
                depth = 0,
                openLine = 0,
                closeLine = 0,
            )
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val staleGuide = checkNotNull(activeGuide())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(pair.openOffset + 1, "x")
        }

        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isNull()
        assertThat(guideHighlighters()).isEmpty()
        assertThat(staleGuide.isValid).isFalse()
    }

    fun testEditBeyondSynchronousLineBudgetClearsStaleGuideImmediately() {
        val source =
            buildString {
                append("{\n")
                repeat(300) { append("    value\n") }
                append("    }")
            }
        myFixture.configureByText("ImmediateBudget.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair =
            BracketPair(
                openOffset = source.indexOf('{'),
                openTokenLength = 1,
                closeOffset = source.lastIndexOf('}'),
                closeTokenLength = 1,
                depth = 0,
                openLine = 0,
                closeLine = 301,
            )
        var collections = 0
        applyPass(pairs = {
            collections++
            listOf(pair)
        })
        val staleGuide = checkNotNull(activeGuide())

        val bodyOffset = source.indexOf("value")
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(bodyOffset, bodyOffset + 1, "x")
        }

        assertThat(collections).isEqualTo(1)
        assertThat(activeGuide()).isNull()
        assertThat(guideHighlighters()).isEmpty()
        assertThat(staleGuide.isValid).isFalse()
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

        val adjustedPair =
            checkNotNull(
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
        val pairs =
            List(pairCount) { index ->
                val openOffset = index * 3
                BracketPair(openOffset, 1, openOffset + 2, 1, 0, 0, 0)
            }
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(1)
        applyPass({ pairs }) { TextRange(0, 256) }
        val persistentGuide = checkNotNull(activeGuide())

        val elapsed =
            measureTimeMillis {
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
