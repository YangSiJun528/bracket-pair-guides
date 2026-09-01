package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.requireSnapshot
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat

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
        val expectedPairCount =
            inReadAction {
                val analysis =
                    service<BracketAnalysis>()
                        .analyze(
                            AnalysisInput(
                                editor = editor,
                                fileType = myFixture.file.fileType,
                                coverage = BracketGuideSettings.getInstance().options.analysisCoverage(),
                                disabledLanguageIds = emptySet(),
                            ),
                            EmptyProgressIndicator(),
                        ).requireSnapshot()
                analysis
                    .visibleTokens(
                        range = TextRange(0, editor.document.textLength),
                        focusOffset = editor.caretModel.primaryCaret.offset,
                        limit = 10_000,
                    ).size / 2
            }

        applyPass()
        val first = ownedHighlighters()
        assertThat(first).hasSize(expectedPairCount * 2 + 1)
        assertThat(first.count { it.customRenderer is BracketGuideDrawing }).isEqualTo(1)
        assertThat(activePairHighlighters()).isEmpty()
        assertThat(bracketColorHighlighters()).hasSize(expectedPairCount * 2)

        applyPass()
        val second = ownedHighlighters()
        assertThat(second.toSet()).isEqualTo(first.toSet())

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample {}")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        editor.caretModel.moveToOffset(editor.document.text.indexOf('{') + 1)
        applyPass()
        assertThat(ownedHighlighters()).hasSize(3)
        assertThat(ownedHighlighters().count { it.customRenderer is BracketGuideDrawing }).isEqualTo(1)
        assertThat(first).anyMatch { !it.isValid }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("class Sample")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        applyPass()
        assertThat(ownedHighlighters()).isEmpty()
    }

    fun testHighlightingAcceptsMockedPairsWithoutALanguageLexer() {
        val source = "opening content closing"
        myFixture.configureByText("Sample.txt", source)
        val pair =
            BracketPair(
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

        assertThat(ownedHighlighters()).hasSize(3)
        assertThat(ownedHighlighters().count { it.customRenderer is BracketGuideDrawing }).isEqualTo(1)
    }

    fun testGuidePositionIndexRetainsOnlyTheMultilinePairEnvelope() {
        val pairSource = "class Sample {\n    int value;\n  }\n"
        val source = pairSource + "// outside\n".repeat(5_000)
        myFixture.configureByText("BoundedGuidePositionIndex.java", source)
        val editor = myFixture.editor
        val analysis =
            inReadAction {
                service<BracketAnalysis>()
                    .analyze(
                        AnalysisInput(
                            editor = editor,
                            fileType = myFixture.file.fileType,
                            coverage =
                            AnalysisCoverage(
                                tokens = true,
                                activePair = true,
                                guidePosition = true,
                            ),
                            disabledLanguageIds = emptySet(),
                        ),
                        EmptyProgressIndicator(),
                    ).requireSnapshot()
            }
        val pair =
            checkNotNull(
                analysis.activePairAt(source.indexOf("value")),
            )

        assertThat(analysis.guideFor(pair)).isEqualTo(BracketGuide(pair, guideColumn = 2, anchorLine = 2))
        assertThat(
            analysis.guideFor(
                pair.copy(openLine = 4_000, closeLine = 4_001),
            ),
        ).isNull()
    }

    fun testInvalidPairTokenBoundsDoNotCreateActivePresentation() {
        val source = "opening content closing"
        myFixture.configureByText("InvalidProviderPair.txt", source)
        val pair =
            BracketPair(
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

        assertThat(activeGuide()).isNull()
        assertThat(activePairHighlighters()).isEmpty()
    }

    fun testCaretMovementWaitsForTheFirstFullSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("InitialSnapshot.txt", source)
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
        testPass(
            project = project,
            editor = myFixture.editor,
            pairs = {
                collections++
                listOf(pair)
            },
        )

        myFixture.editor.caretModel.moveToOffset(source.indexOf("content"))

        assertThat(collections).isEqualTo(0)
        assertThat(activeGuide()).isNull()
        assertThat(activePairHighlighters()).isEmpty()
    }

    fun testStaleCaretMovementKeepsAdjustedPairUntilTheNextSnapshot() {
        val source = "x { outer (inner) tail } y"
        myFixture.configureByText("StaleCaret.txt", source)
        val outer =
            BracketPair(
                source.indexOf('{'),
                1,
                source.indexOf('}'),
                1,
                0,
                0,
                0,
            )
        val inner =
            BracketPair(
                source.indexOf('('),
                1,
                source.indexOf(')'),
                1,
                1,
                0,
                0,
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
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "z")
        }
        editor.caretModel.moveToOffset(innerOffset)

        assertThat(activeGuideState()?.guide?.pair).isEqualTo(outer)

        applyPass(testPass(project, editor, pairs = { listOf(outer, inner) }))
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(inner)
    }

    fun testDocumentEditDoesNotInventAReplacementBeforeTheNextSnapshot() {
        val source = "x { content } y"
        myFixture.configureByText("ContextChange.txt", source)
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
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        applyPass(
            testPass(
                project = project,
                editor = editor,
                pairs = { listOf(pair) },
            ),
        )
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(pair)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("content") + 1, "x")
        }

        assertThat(activeGuideState()?.guide?.pair?.closeOffset).isEqualTo(pair.closeOffset + 1)
    }

    fun testDocumentChangeKeepsTheAdjustedPairWhileSnapshotIsStale() {
        val source = "x { content } y"
        myFixture.configureByText("StaleSnapshot.txt", source)
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

        assertThat(activeGuideState()?.guide?.pair?.closeOffset).isEqualTo(pair.closeOffset + 1)
    }

    fun testDocumentChangesReleaseStaleAnalysisButKeepTokenPresentation() {
        val source = "class Sample { int value; }"
        myFixture.configureByText("ReleaseStaleSnapshot.java", source)
        val editor = myFixture.editor
        val options =
            BracketGuideSettings.getInstance().options.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
                colorBracketTokens = true,
            )
        BracketGuideSettings.getInstance().replace(options)
        applyPass()
        val acceptedStamp = stampFor(editor, options)
        val decorations = bracketColorHighlighters().toSet()
        assertThat(decorations).isNotEmpty()
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp)).isTrue()

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(source.indexOf("value"), "x")
        }

        assertThat(EditorGuideSessions.canSkipAnalysis(editor, acceptedStamp)).isFalse()
        assertThat(bracketColorHighlighters().toSet()).isEqualTo(decorations)
        assertThat(decorations).allMatch { it.isValid }
    }
}
