package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.events.BracketGuideSettingsController
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean

internal class SecondaryEditorAnalysisTest : BracketGuideHighlightingFixture() {
    fun testSecondaryCalculationRunsWithoutReadAccessAndAppliesOnEdt() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        val application = ApplicationManager.getApplication()
        val calculatedOnEdt = AtomicBoolean(true)
        val calculatedUnderReadAccess = AtomicBoolean(true)
        val presentationApplied = AtomicBoolean(false)
        withScheduler(
            analyze = { input ->
                calculatedOnEdt.set(application.isDispatchThread)
                calculatedUnderReadAccess.set(application.isReadAccessAllowed)
                service<BracketAnalysis>().analyzeInBackground(input)
            },
            onPresentation = {
                application.assertIsDispatchThread()
                presentationApplied.set(true)
            },
        ) { _, editor ->
            awaitTokens(editor, 2)
            assertThat(calculatedOnEdt.get()).isFalse()
            assertThat(calculatedUnderReadAccess.get()).isFalse()
            assertThat(presentationApplied.get()).isTrue()
        }
    }

    fun testCodeSnippetWithoutAVirtualFileUsesItsLanguageHighlighter() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        val document = EditorFactory.getInstance().createDocument(myFixture.editor.document.text)
        withScheduler(document = document, editorProject = null) { _, editor ->
            assertThat(editor.project).isNull()
            assertThat(EditorSurfaceClassifier.sourceFile(editor)).isNull()
            awaitTokens(editor, 2)
        }
    }

    fun testExistingPreviewIsAnalyzedWhenObservationStarts() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(myFixture.editor.document, project, EditorKind.PREVIEW)
        var scheduler: SecondaryEditorAnalysis? = null
        try {
            (editor as EditorEx).setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
            )
            val execution = createExecution(activity = { EditorActivity(true, false) })
            scheduler = SecondaryEditorAnalysis(
                { EditorActivity(true, false) },
                { TextRange(0, it.document.textLength) },
                execution::request,
                execution::cancel,
            )
            awaitTokens(editor, 2)
        } finally {
            factory.releaseEditor(editor)
            scheduler?.let(Disposer::dispose)
        }
    }

    fun testHighlighterReplacementClearsOldColorsAndRequestsFreshAnalysis() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        withScheduler { _, editor ->
            awaitTokens(editor, 2)
            (editor as EditorEx).setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE),
            )
            assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
            editor.setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
            )
            awaitTokens(editor, 2)
        }
    }

    fun testSecondaryEditorColorsAndReanalyzesWithoutAnyDaemonPass() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        withScheduler { _, editor ->
            awaitTokens(editor, 2)
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.setText("class Preview { void action() {} }")
            }
            awaitTokens(editor, 6)
            assertThat(editor.observedBracketMarkup().guideMarks).isEmpty()
            assertThat(editor.observedBracketMarkup().activePairMarks).isEmpty()
        }
    }

    fun testShowingAPreviouslyHiddenEditorRequestsItsFirstAnalysis() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        var activity = EditorActivity.INACTIVE
        withScheduler(activity = { activity }) { _, editor ->
            assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
            activity = EditorActivity(true, false)
            EditorGuideSessions.get(editor)!!.updateSurface(EditorSurfaceClassifier.capabilities(editor), activity)
            awaitTokens(editor, 2)
        }
    }

    fun testSettingsChangesRefreshSecondaryEditorsThroughTheirOwnScheduler() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        withScheduler { _, editor ->
            awaitTokens(editor, 2)
            val session = EditorGuideSessions.get(editor)!!
            val disabled = BracketGuideSettings.getInstance().options
                .copy(colorBracketTokens = false)
            BracketGuideSettingsController.getInstance()
                .applySettings(disabled)
            assertThat(editor.observedBracketMarkup().allMarks).isEmpty()
            BracketGuideSettingsController.getInstance()
                .applySettings(disabled.copy(colorBracketTokens = true))
            awaitTokens(editor, 2)
            assertThat(EditorGuideSessions.get(editor)).isSameAs(session)
        }
    }

    fun testReleaseRemovesSessionAndPendingSecondaryRefresh() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        withScheduler { factory, editor ->
            awaitTokens(editor, 2)
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(0, " ")
            }
            factory.releaseEditor(editor)
            assertThat(EditorGuideSessions.get(editor)).isNull()
        }
    }

    private fun withScheduler(
        activity: (Editor) -> EditorActivity = { EditorActivity(true, false) },
        document: Document = myFixture.editor.document,
        editorProject: Project? = project,
        analyze: suspend (com.sijunyang.bracketpairguides.analysis.AnalysisInput) ->
        com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome? = {
            service<BracketAnalysis>().analyzeInBackground(it)
        },
        onPresentation: () -> Unit = {},
        action: (EditorFactory, Editor) -> Unit,
    ) {
        val visibleRange: (Editor) -> TextRange = {
            onPresentation()
            TextRange(0, it.document.textLength)
        }
        val execution = createExecution(analyze = analyze, activity = activity, visibleRange = visibleRange)
        val scheduler = SecondaryEditorAnalysis(activity, visibleRange, execution::request, execution::cancel)
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(document, editorProject, EditorKind.PREVIEW)
        try {
            (editor as EditorEx).setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
            )
            action(factory, editor)
        } finally {
            if (!editor.isDisposed) factory.releaseEditor(editor)
            Disposer.dispose(scheduler)
        }
    }

    private fun awaitTokens(editor: Editor, expected: Int) {
        PlatformTestUtil.waitWithEventsDispatching(
            "secondary editor has $expected colored bracket tokens without daemon analysis",
            { editor.observedBracketMarkup().tokenMarks.size == expected },
            10,
        )
    }
}
