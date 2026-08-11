package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.highlighting.BracketGuideHighlightingPass
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat

class EditorGuideSessionLifecycleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    fun testHighlighterReplacementClearsPresentationFromPreviousSemantics() {
        val source = "class Sample { content }"
        myFixture.configureByText("Sample.java", source)
        val editor = myFixture.editor
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.indexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 0,
        )
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                AnalysisOutcome.Complete(input.bracketSnapshot(listOf(pair)))
            },
            visibleRange = { current ->
                TextRange(0, current.document.textLength)
            },
        )
        ReadAction.compute<Unit, RuntimeException> {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
        val session = checkNotNull(EditorGuideSessions.get(editor))
        assertThat(editor.observedBracketMarkup().guideMarks).hasSize(1)
        assertThat(editor.observedBracketMarkup().tokenMarks).hasSize(2)

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        session.visibleAreaChanged()

        assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
        editor.caretModel.moveToOffset(source.indexOf("content") + 1)
        assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
    }

    fun testDocumentChangeWithoutSnapshotDoesNotCreateActivePresentation() {
        myFixture.configureByText("NoSnapshot.txt", "{ value }")
        val editor = myFixture.editor
        BracketGuideSettings.getInstance().replace(
            BracketGuidePreferences(showActivePairBorder = true),
        )
        EditorGuideEvents.ensureInitialized()
        EditorGuideSessions.dispose(editor)
        EditorGuideSessions.install(
            editor = editor,
            visibleRange = { TextRange(0, editor.document.textLength) },
            preferences = BracketGuideSettings.getInstance().options,
        )

        try {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(2, "x")
            }
            editor.caretModel.moveToOffset(4)

            assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
        } finally {
            EditorGuideSessions.dispose(editor)
        }
    }

    fun testSplitDocumentKeepsAdjustedPresentationInBothEditors() {
        val document = EditorFactory.getInstance().createDocument("{ value }")
        val firstEditor = EditorFactory.getInstance().createEditor(document, project)
        val secondEditor = EditorFactory.getInstance().createEditor(document, project)
        val options = BracketGuidePreferences(showActivePairBorder = true)
        BracketGuideSettings.getInstance().replace(options)
        EditorGuideEvents.ensureInitialized()
        val pair = BracketPair(0, 1, 8, 1, 0, 0, 0)
        try {
            val sessions = listOf(firstEditor, secondEditor).map { editor ->
                editor.caretModel.moveToOffset(3)
                val session = EditorGuideSessions.install(
                    editor = editor,
                    visibleRange = { TextRange(0, document.textLength) },
                    preferences = options,
                )
                val input = AnalysisInput(
                    editor = editor,
                    fileType = PlainTextFileType.INSTANCE,
                    coverage = options.analysisCoverage(),
                    disabledLanguageIds = emptySet(),
                )
                session.accept(
                    AnalysisOutcome.Complete(
                        input.bracketSnapshot(listOf(pair)),
                    ),
                )
                session
            }
            assertThat(
                listOf(firstEditor, secondEditor).map {
                    it.observedBracketMarkup().activePairMarks.size
                },
            ).containsExactly(2, 2)

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(0, "x")
            }

            assertThat(sessions).hasSize(2)
            for (editor in listOf(firstEditor, secondEditor)) {
                assertThat(
                    editor.observedBracketMarkup().activePairMarks
                        .map { it.startOffset }
                        .sorted(),
                ).containsExactly(1, 9)
            }
        } finally {
            EditorGuideSessions.dispose(firstEditor)
            EditorGuideSessions.dispose(secondEditor)
            EditorFactory.getInstance().releaseEditor(firstEditor)
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testSplitDocumentRecalculatesEachViewsGuideGeometryImmediately() {
        val source = "{\n \tvalue\n \t}"
        val document = EditorFactory.getInstance().createDocument(source)
        val firstEditor = EditorFactory.getInstance().createEditor(document, project)
        val secondEditor = EditorFactory.getInstance().createEditor(document, project)
        val options = BracketGuidePreferences()
        firstEditor.settings.setTabSize(2)
        secondEditor.settings.setTabSize(4)
        BracketGuideSettings.getInstance().replace(options)
        EditorGuideEvents.ensureInitialized()
        val pair = BracketPair(
            openOffset = source.indexOf('{'),
            openTokenLength = 1,
            closeOffset = source.lastIndexOf('}'),
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 2,
        )
        try {
            for (editor in listOf(firstEditor, secondEditor)) {
                editor.caretModel.moveToOffset(source.indexOf("value"))
                val session = EditorGuideSessions.install(
                    editor = editor,
                    visibleRange = { TextRange(0, document.textLength) },
                    preferences = options,
                )
                val input = AnalysisInput(
                    editor = editor,
                    fileType = PlainTextFileType.INSTANCE,
                    coverage = options.analysisCoverage(),
                    disabledLanguageIds = emptySet(),
                )
                session.accept(
                    AnalysisOutcome.Complete(
                        input.bracketSnapshot(listOf(pair)),
                    ),
                )
            }
            assertThat(firstEditor.guideColumn()).isEqualTo(2)
            assertThat(secondEditor.guideColumn()).isEqualTo(4)
            val firstGuide = firstEditor.observedBracketMarkup().guideMarks.single()
            val secondGuide = secondEditor.observedBracketMarkup().guideMarks.single()

            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(
                    source.indexOf(" \tvalue"),
                    source.lastIndexOf('}'),
                    "\t value\n\t ",
                )
            }

            assertThat(firstEditor.guideColumn()).isEqualTo(3)
            assertThat(secondEditor.guideColumn()).isEqualTo(5)
            assertThat(firstEditor.observedBracketMarkup().guideMarks)
                .containsExactly(firstGuide)
            assertThat(secondEditor.observedBracketMarkup().guideMarks)
                .containsExactly(secondGuide)
        } finally {
            EditorGuideSessions.dispose(firstEditor)
            EditorGuideSessions.dispose(secondEditor)
            EditorFactory.getInstance().releaseEditor(firstEditor)
            EditorFactory.getInstance().releaseEditor(secondEditor)
        }
    }

    fun testLanguageChangeClearsPresentationUntilANewSnapshotArrives() {
        val source = "{ value }"
        myFixture.configureByText("LanguageChange.txt", source)
        val editor = myFixture.editor
        val options = BracketGuidePreferences(showActivePairBorder = true)
        BracketGuideSettings.getInstance().replace(options)
        editor.caretModel.moveToOffset(source.indexOf("value"))
        val pair = BracketPair(0, 1, source.lastIndex, 1, 0, 0, 0)
        val pass = BracketGuideHighlightingPass(
            project = project,
            editor = editor,
            fileType = myFixture.file.fileType,
            sourceFile = myFixture.file.virtualFile,
            analyze = { input, _ ->
                AnalysisOutcome.Complete(input.bracketSnapshot(listOf(pair)))
            },
            visibleRange = { current ->
                TextRange(0, current.document.textLength)
            },
        )
        ReadAction.compute<Unit, RuntimeException> {
            pass.doCollectInformation(EmptyProgressIndicator())
        }
        pass.doApplyInformationToEditor()
        val session = checkNotNull(EditorGuideSessions.get(editor))
        assertThat(editor.observedBracketMarkup().allMarks).isNotEmpty()

        session.updateOptions(
            options.copy(disabledLanguageIds = setOf("changed.language")),
            refreshColors = false,
        )
        editor.caretModel.moveToOffset(source.indexOf("value") + 1)

        assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
    }

    private fun Editor.guideColumn(): Int =
        (observedBracketMarkup().guideMarks.single().customRenderer as BracketGuideDrawing)
            .guide.guideColumn
}
