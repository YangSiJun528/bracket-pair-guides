package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicInteger

internal class EditorAnalysisExecutionTest : BracketGuideHighlightingFixture() {
    fun testNarrowerCoverageWithANewTabSizeCancelsTheOldGuideCalculation() {
        myFixture.configureByText("TabSupersession.java", "class TabSupersession { }")
        val editor = myFixture.editor
        editor.settings.setTabSize(4)
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val execution = createExecution(
            capabilities = { EditorCapabilities.MAIN },
            analyze = { input ->
                if (calls.incrementAndGet() == 1) {
                    assertThat(input.coverage.guidePosition).isTrue()
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        canceled.complete(Unit)
                    }
                } else {
                    assertThat(input.coverage.guidePosition).isFalse()
                    readAction { AnalysisOutcome.Complete(input.bracketSnapshot(emptyList())) }
                }
            },
        )
        execution.request(editor)
        waitFor("guide calculation started") { entered.isCompleted }
        editor.settings.setTabSize(8)
        val tokenOnly = BracketGuideSettings.getInstance().options.copy(
            showActiveGuide = false,
            showActivePairBorder = false,
            showActivePairBackground = false,
        )
        BracketGuideSettings.getInstance().replace(tokenOnly)
        execution.request(editor)
        waitFor("old guide calculation canceled") { canceled.isCompleted }
        awaitAnalysis()
        assertThat(calls.get()).isEqualTo(2)
        assertThat(EditorGuideSessions.canSkipAnalysis(editor, stampFor(editor, tokenOnly))).isTrue()
    }

    fun testCompatibleNarrowerCoverageCanReuseARicherCalculation() {
        myFixture.configureByText("Richer.java", "class Richer { value }")
        val editor = myFixture.editor
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val pair = BracketPair(13, 1, 21, 1, 0, 0, 0)
        val execution = createExecution(
            capabilities = { EditorCapabilities.MAIN },
            analyze = { input ->
                calls.incrementAndGet()
                entered.complete(Unit)
                val result = readAction { AnalysisOutcome.Complete(input.bracketSnapshot(listOf(pair))) }
                release.await()
                result
            },
        )
        execution.request(editor)
        waitFor("richer calculation entered") { entered.isCompleted }
        BracketGuideSettings.getInstance().replace(
            BracketGuideSettings.getInstance().options.copy(
                showActiveGuide = false,
                showActivePairBorder = false,
                showActivePairBackground = false,
            ),
        )
        execution.request(editor)
        release.complete(Unit)
        awaitAnalysis()
        assertThat(calls.get()).isEqualTo(1)
        assertThat(editor.observedBracketMarkup().tokenMarks).hasSize(2)
    }

    fun testDocumentSupersessionCancelsBeforeTheNewRequestCompletes() {
        myFixture.configureByText("Supersession.java", "class Supersession { }")
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val execution = createExecution(analyze = { input ->
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    canceled.complete(Unit)
                }
            } else {
                readAction { AnalysisOutcome.Complete(input.bracketSnapshot(emptyList())) }
            }
        })
        execution.request(myFixture.editor)
        waitFor("initial calculation entered") { entered.isCompleted }
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        execution.request(myFixture.editor)
        waitFor("obsolete calculation canceled") { canceled.isCompleted }
        awaitAnalysis()
        assertThat(calls.get()).isEqualTo(2)
    }

    fun testDisposalCancelsRunningCalculationAndPreventsPublication() {
        myFixture.configureByText("Disposed.java", "class Disposed { }")
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val execution = createExecution(analyze = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                canceled.complete(Unit)
            }
        })
        execution.request(myFixture.editor)
        waitFor("disposed calculation entered") { entered.isCompleted }
        Disposer.dispose(execution)
        waitFor("disposed calculation canceled") { canceled.isCompleted }
        awaitAnalysis()
        assertThat(EditorGuideSessions.get(myFixture.editor)).isNull()
    }

    fun testEditorReleaseCancelsRunningCalculation() {
        myFixture.configureByText("Released.java", "class Released { }")
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(myFixture.editor.document, project, EditorKind.PREVIEW)
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        try {
            (editor as EditorEx).setHighlighter(
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, myFixture.file.fileType),
            )
            val execution = createExecution(
                activity = { EditorActivity(true, false) },
                analyze = {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        canceled.complete(Unit)
                    }
                },
            )
            execution.request(editor)
            waitFor("released editor calculation entered") { entered.isCompleted }
            factory.releaseEditor(editor)
            waitFor("released editor calculation canceled") { canceled.isCompleted }
            awaitAnalysis()
            assertThat(EditorGuideSessions.get(editor)).isNull()
        } finally {
            if (!editor.isDisposed) factory.releaseEditor(editor)
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(description, condition, 10)
    }
}
