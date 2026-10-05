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
    fun testPassConstructionAndCollectionUseBackgroundReadActionsAndApplyOnEdt() {
        myFixture.configureByText("Preview.java", "class Preview { int value; }")
        val application = ApplicationManager.getApplication()
        val constructedOnEdt = AtomicBoolean(true)
        val constructedUnderReadAccess = AtomicBoolean(false)
        val collectedOnEdt = AtomicBoolean(true)
        val collectedUnderReadAccess = AtomicBoolean(false)
        val presentationApplied = AtomicBoolean(false)
        withScheduler(createPass = { passProject, editor ->
            constructedOnEdt.set(application.isDispatchThread)
            constructedUnderReadAccess.set(application.isReadAccessAllowed)
            BracketGuideHighlightingPass(
                project = passProject,
                editor = editor,
                fileType = EditorSurfaceClassifier.fileType(editor),
                sourceFile = EditorSurfaceClassifier.sourceFile(editor),
                analyze = { input, progress ->
                    collectedOnEdt.set(application.isDispatchThread)
                    collectedUnderReadAccess.set(application.isReadAccessAllowed)
                    service<BracketAnalysis>().analyze(input, progress)
                },
                activity = {
                    application.assertIsDispatchThread()
                    EditorActivity(true, false)
                },
                visibleRange = {
                    application.assertIsDispatchThread()
                    presentationApplied.set(true)
                    TextRange(0, it.document.textLength)
                },
                stickySourceRanges = { emptyList() },
            )
        }) { _, editor ->
            awaitTokens(editor, 2)
            assertThat(constructedOnEdt.get()).isFalse()
            assertThat(constructedUnderReadAccess.get()).isTrue()
            assertThat(collectedOnEdt.get()).isFalse()
            assertThat(collectedUnderReadAccess.get()).isTrue()
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
            scheduler = SecondaryEditorAnalysis(
                { EditorActivity(true, false) },
                { TextRange(0, it.document.textLength) },
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
        createPass: ((Project, Editor) -> BracketGuideHighlightingPass)? = null,
        action: (EditorFactory, Editor) -> Unit,
    ) {
        val visibleRange: (Editor) -> TextRange = { TextRange(0, it.document.textLength) }
        val scheduler = if (createPass == null) {
            SecondaryEditorAnalysis(activity, visibleRange)
        } else {
            SecondaryEditorAnalysis(activity, visibleRange, createPass)
        }
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
