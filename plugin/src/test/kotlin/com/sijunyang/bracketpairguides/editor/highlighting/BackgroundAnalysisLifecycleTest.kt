package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class BackgroundAnalysisLifecycleTest : BracketGuideHighlightingFixture() {
    fun testAppliesActiveGuideBeforeRequestingViewportDecorations() {
        val source = "x { content } y"
        myFixture.configureByText("ActiveFirst.txt", source)
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
        BracketGuideSettings.getInstance().replace(
            BracketGuideSettings.getInstance().options.copy(showActivePairBorder = true),
        )
        var activePairWhenViewportWasRequested: BracketPair? = null
        var activeHighlightsWhenViewportWasRequested = 0
        val pass =
            testPass(
                project = project,
                editor = editor,
                pairs = { listOf(pair) },
                visibleRange = {
                    activePairWhenViewportWasRequested = activeGuideState()?.guide?.pair
                    activeHighlightsWhenViewportWasRequested = activePairHighlighters().size
                    TextRange(0, source.length)
                },
            )

        applyPass(pass)

        assertThat(activePairWhenViewportWasRequested).isEqualTo(pair)
        assertThat(activeHighlightsWhenViewportWasRequested).isEqualTo(2)
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(pair)
    }

    fun testDaemonRequestReturnsBeforeUnlockedCalculationAndPublishesOnEdt() {
        val source = "x { content } y"
        myFixture.configureByText("IndependentRequest.txt", source)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(source.indexOf("content"))
        val pair = BracketPair(2, 1, 12, 1, 0, 0, 0)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calculationUnderRead = true
        var calculationOnEdt = true
        var publishedOnEdt = false
        val execution = createExecution(
            analyze = { input ->
                val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                calculationUnderRead = app.isReadAccessAllowed
                calculationOnEdt = app.isDispatchThread
                entered.complete(Unit)
                release.await()
                com.intellij.openapi.application.readAction {
                    AnalysisOutcome.Complete(input.bracketSnapshot(listOf(pair)))
                }
            },
            capabilities = { EditorCapabilities.MAIN },
            visibleRange = {
                publishedOnEdt = com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread
                TextRange(0, it.document.textLength)
            },
        )
        val pass = inBackgroundReadAction {
            BracketGuideHighlightingPass(project, editor, execution::request)
        }
        inBackgroundReadAction { pass.doCollectInformation(EmptyProgressIndicator()) }
        PlatformTestUtil.waitWithEventsDispatching("independent calculation entered", { entered.isCompleted }, 10)
        assertThat(calculationUnderRead).isFalse()
        assertThat(calculationOnEdt).isFalse()
        assertThat(EditorGuideSessions.get(editor)).isNull()
        release.complete(Unit)
        awaitAnalysis()
        assertThat(publishedOnEdt).isTrue()
        assertThat(activeGuideState()?.guide?.pair).isEqualTo(pair)
    }

    fun testRequestsForTheSameCapturedInputCoalesce() {
        myFixture.configureByText("Coalesce.java", "class Coalesce { }")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val execution = createExecution(analyze = { input ->
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            com.intellij.openapi.application.readAction { AnalysisOutcome.Complete(input.bracketSnapshot(emptyList())) }
        })
        execution.request(myFixture.editor)
        PlatformTestUtil.waitWithEventsDispatching("coalesced calculation entered", { entered.isCompleted }, 10)
        repeat(20) { execution.request(myFixture.editor) }
        release.complete(Unit)
        awaitAnalysis()
        assertThat(calls.get()).isEqualTo(1)
    }

    fun testStaleResultDoesNotInstallItsDependenciesIntoANewSession() {
        myFixture.configureByText("StaleRequest.java", "class StaleRequest { }")
        val pass = testPass(project, myFixture.editor, { emptyList() })
        EditorGuideSessions.dispose(myFixture.editor)
        collectPass(pass)
        assertThat(EditorGuideSessions.get(myFixture.editor)).isNull()
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        publishPass(pass)
        assertThat(EditorGuideSessions.get(myFixture.editor)).isNull()
    }

    fun testCapturedLanguageSelectionSurvivesAnAbaSettingsChange() {
        myFixture.configureByText("LanguageAba.java", "class LanguageAba { }")
        val initial = BracketGuideSettings.getInstance().options
        val captured = AtomicReference<Set<String>>()
        val pass = createPass(
            project,
            myFixture.editor,
            myFixture.file.fileType,
            myFixture.file.virtualFile,
            capabilities = { EditorCapabilities.MAIN },
            analyze = { input, _ ->
                captured.set(input.disabledLanguageIds)
                AnalysisOutcome.Complete(input.bracketSnapshot(emptyList()))
            },
        )
        collectPass(pass)
        BracketGuideSettings.getInstance().replace(initial.copy(disabledLanguageIds = setOf("temporary")))
        BracketGuideSettings.getInstance().replace(initial)
        publishPass(pass)
        assertThat(captured.get()).isEqualTo(initial.disabledLanguageIds)
        assertThat(EditorGuideSessions.canSkipAnalysis(myFixture.editor, stampFor(myFixture.editor, initial))).isTrue()
    }

    fun testRejectedStaleResultCannotReplaceCurrentSessionDependencies() {
        myFixture.configureByText("DependencyOrder.java", "class DependencyOrder { }")
        var staleCalls = 0
        var currentCalls = 0
        val stale = testPass(project, myFixture.editor, { emptyList() }, visibleRange = {
            staleCalls++
            TextRange(0, it.document.textLength)
        })
        collectPass(stale)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
        val current = testPass(project, myFixture.editor, { emptyList() }, visibleRange = {
            currentCalls++
            TextRange(0, it.document.textLength)
        })
        // The older request is held while the current request publishes.
        applyPass(current)
        publishPass(stale)
        staleCalls = 0
        currentCalls = 0
        session().visibleAreaChanged()
        assertThat(staleCalls).isZero()
        assertThat(currentCalls).isEqualTo(1)
    }
}
