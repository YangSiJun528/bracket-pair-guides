package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.requireSnapshot
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class BracketGuideHighlightingPassTest : BracketGuideHighlightingFixture() {
    fun testCreatesReusesAndRemovesOwnedHighlighters() {
        val source =
            "class Sample { void run() { call(); } }"
        myFixture.configureByText(
            "Sample.java",
            source,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("()") + 1)
        val expectedPairCount = inReadAction {
            val analysis = service<BracketAnalysis>().analyze(
                AnalysisInput(
                    editor = editor,
                    fileType = myFixture.file.fileType,
                    coverage = BracketGuideSettings.getInstance().options.analysisCoverage(),
                    disabledLanguageIds = emptySet(),
                ),
                EmptyProgressIndicator(),
            ).requireSnapshot()
            analysis.visibleTokens(
                range = TextRange(0, editor.document.textLength),
                focusOffset = editor.caretModel.primaryCaret.offset,
                limit = 10_000,
            ).size / 2
        }

        applyPass()
        val first = ownedHighlighters()
        assertEquals(expectedPairCount * 2 + 1, first.size)
        assertEquals(1, first.count { it.customRenderer is BracketGuideDrawing })
        assertTrue(activePairHighlighters().isEmpty())
        assertEquals(
            expectedPairCount * 2,
            bracketColorHighlighters().size,
        )

        applyPass()
        val second = ownedHighlighters()
        assertEquals(first.toSet(), second.toSet())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample {}")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        editor.caretModel.moveToOffset(editor.document.text.indexOf('{') + 1)
        applyPass()
        assertEquals(3, ownedHighlighters().size)
        assertEquals(
            1,
            ownedHighlighters().count { it.customRenderer is BracketGuideDrawing },
        )
        assertTrue(first.any { !it.isValid })

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        applyPass()
        assertTrue(ownedHighlighters().isEmpty())
    }

    fun testHighlightingAcceptsMockedPairsWithoutALanguageLexer() {
        val source = "opening content closing"
        myFixture.configureByText("Sample.txt", source)
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = "opening".length,
            closeOffset = source.indexOf("closing"),
            closeTokenLength = "closing".length,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        applyPass({ listOf(pair) })

        assertEquals(3, ownedHighlighters().size)
        assertEquals(
            1,
            ownedHighlighters().count { it.customRenderer is BracketGuideDrawing },
        )
    }

    fun testGuidePositionIndexRetainsOnlyTheMultilinePairEnvelope() {
        val pairSource = "class Sample {\n    int value;\n  }\n"
        val source = pairSource + "// outside\n".repeat(5_000)
        myFixture.configureByText("BoundedGuidePositionIndex.java", source)
        val editor = myFixture.editor
        val analysis = inReadAction {
            service<BracketAnalysis>().analyze(
                AnalysisInput(
                    editor = editor,
                    fileType = myFixture.file.fileType,
                    coverage = AnalysisCoverage(
                        tokens = true,
                        activePair = true,
                        guidePosition = true,
                    ),
                    disabledLanguageIds = emptySet(),
                ),
                EmptyProgressIndicator(),
            ).requireSnapshot()
        }
        val pair = checkNotNull(
            analysis.activePairAt(source.indexOf("value")),
        )

        assertEquals(
            BracketGuide(pair, guideColumn = 2, anchorLine = 2),
            analysis.guideFor(pair),
        )
        assertEquals(
            null,
            analysis.guideFor(
                pair.copy(openLine = 4_000, closeLine = 4_001),
            ),
        )
    }

    fun testInvalidPairTokenBoundsDoNotCreateActivePresentation() {
        val source = "opening content closing"
        myFixture.configureByText("InvalidProviderPair.txt", source)
        val pair = BracketPair(
            openOffset = 0,
            openTokenLength = "opening".length,
            closeOffset = source.indexOf("closing"),
            closeTokenLength = source.length,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        BracketGuideSettings.getInstance().replace(
            BracketGuideSettings.getInstance().options.copy(showActivePairBorder = true),
        )

        applyPass({ listOf(pair) })

        assertNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())
    }

    fun testCaretMovementWaitsForTheFirstFullSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("InitialSnapshot.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        var collections = 0
        testPass(
            project = project,
            editor = myFixture.editor,
            pairs = {
                collections++
                listOf(pair)
            },
        )

        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        assertEquals(0, collections)
        assertNull(activeGuide())
        assertTrue(activePairHighlighters().isEmpty())
    }

    fun testStaleCaretMovementKeepsAdjustedPairUntilTheNextSnapshot() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("StaleCaret.txt", source)
        val outer = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val inner = BracketPair(
            source.indexOf('('), 1, source.indexOf(')'), 1, 1, 0, 0,
        )
        val editor = myFixture.editor
        val tailOffset = source.indexOf("tail")
        val innerOffset = source.indexOf("inner")
        editor.caretModel.moveToOffset(tailOffset)
        applyPass(
            testPass(
                project = project,
                editor = editor,
                pairs = { listOf(outer, inner) },
            ),
        )
        assertEquals(outer, activeGuideState()?.guide?.pair)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        editor.caretModel.moveToOffset(innerOffset)

        assertEquals(outer, activeGuideState()?.guide?.pair)

        applyPass(testPass(project, editor, pairs = { listOf(outer, inner) }))
        assertEquals(inner, activeGuideState()?.guide?.pair)
    }

    fun testDocumentEditDoesNotInventAReplacementBeforeTheNextSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("ContextChange.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(
            testPass(
                project = project,
                editor = editor,
                pairs = { listOf(pair) },
            ),
        )
        assertEquals(pair, activeGuideState()?.guide?.pair)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content") + 1, "x")
        }

        assertEquals(pair.closeOffset + 1, activeGuideState()?.guide?.pair?.closeOffset)
    }

    fun testDocumentChangeKeepsTheAdjustedPairWhileSnapshotIsStale() {
        val source = "x { content } y"
        myFixture.configureByText("StaleSnapshot.txt", source)
        val pair = BracketPair(
            source.indexOf('{'), 1, source.indexOf('}'), 1, 0, 0, 0,
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(
            testPass(
                project = project,
                editor = editor,
                pairs = { listOf(pair) },
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content") + 1, "x")
        }

        assertEquals(
            pair.closeOffset + 1,
            activeGuideState()?.guide?.pair?.closeOffset,
        )
    }

    fun testDocumentChangesReleaseStaleAnalysisButKeepTokenPresentation() {
        val source = "class Sample { int value; }"
        myFixture.configureByText("ReleaseStaleSnapshot.java", source)
        val editor = myFixture.editor
        val options = BracketGuideSettings.getInstance().options.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
            colorBracketTokens = true,
        )
        BracketGuideSettings.getInstance().replace(options)
        applyPass()
        val acceptedStamp = stampFor(editor, options)
        val decorations = bracketColorHighlighters().toSet()
        assertTrue(decorations.isNotEmpty())
        assertTrue(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("value"), "x")
        }

        assertFalse(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp))
        assertEquals(decorations, bracketColorHighlighters().toSet())
        assertTrue(decorations.all { it.isValid })
    }
}
