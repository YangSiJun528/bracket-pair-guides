package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.analysis.bracketSnapshot
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.presentation.ActiveGuidePresentation
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.presentation.DocumentChange
import com.sijunyang.bracketpairguides.presentation.GuideRepairRequest
import org.assertj.core.api.Assertions.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class GuideRepairExecutionTest : BasePlatformTestCase() {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default)
    private val executions = mutableListOf<GuideRepairExecution>()

    override fun tearDown() {
        try {
            lifetime.cancel()
            executions.forEach(Disposer::dispose)
            awaitIdle()
        } finally {
            super.tearDown()
        }
    }

    fun testRealRepairCapturesLongPrefixesAndPublishesOnEdtWithoutFullAnalysis() {
        configure("{\n" + " ".repeat(5_000) + "body\n  }")
        val execution = execution()
        var guide: BracketGuide? = null
        execution.request(myFixture.editor, request(), { true }) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            guide = it
        }
        awaitIdle()
        assertThat(guide?.guideColumn).isEqualTo(2)
        assertThat(guide?.anchorLine).isEqualTo(2)
    }

    fun testEdtWriteCompletesWhileRealRepairCalculationIsPausedOutsideReadAccess() {
        configure("{\n    body\n  }")
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val execution = execution { editor, request ->
            val context = currentCoroutineContext()
            GuideRepairExecution.calculateGuide(editor, request) {
                if (!ApplicationManager.getApplication().isReadAccessAllowed && checks.incrementAndGet() == 2) {
                    assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
                    ready.countDown()
                    while (!release.await(10, TimeUnit.MILLISECONDS)) context.ensureActive()
                }
            }
        }
        var guide: BracketGuide? = null
        execution.request(myFixture.editor, request(), { true }) { guide = it }
        awaitGate(ready)
        try {
            var writeCompleted = false
            ApplicationManager.getApplication().runWriteAction { writeCompleted = true }
            assertThat(writeCompleted).isTrue()
            assertThat(release.count).isEqualTo(1L)
        } finally {
            release.countDown()
        }
        awaitIdle()
        assertThat(guide?.guideColumn).isEqualTo(2)
    }

    fun testEditedDocumentAndChangedTabsRejectCapturedRepair() {
        for (changeTabs in listOf(false, true)) {
            configure("{\n    body\n  }")
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val execution = execution { _, request ->
                assertWorker()
                ready.complete(Unit)
                release.await()
                BracketGuide(request.pair, 99)
            }
            var publications = 0
            execution.request(myFixture.editor, request(), { true }) { publications++ }
            awaitReady(ready)
            if (changeTabs) {
                myFixture.editor.settings.setTabSize(8)
            } else {
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(2, " ") }
            }
            release.complete(Unit)
            awaitIdle()
            assertThat(publications).isZero()
            Disposer.dispose(execution)
        }
    }

    fun testNewRequestOwnsPublicationEvenWhenCanceledCalculationFinishesLate() {
        configure("{\n    body\n  }")
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calculations = AtomicInteger()
        val execution = execution { _, request ->
            assertWorker()
            val index = calculations.incrementAndGet()
            if (index == 1) {
                ready.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            BracketGuide(request.pair, index)
        }
        val columns = mutableListOf<Int>()
        execution.request(myFixture.editor, request(), { true }) { columns += it.guideColumn }
        awaitReady(ready)
        execution.request(myFixture.editor, request(), { true }) { columns += it.guideColumn }
        PlatformTestUtil.waitWithEventsDispatching("latest repair published", { columns.isNotEmpty() }, 10)
        release.complete(Unit)
        awaitIdle()
        assertThat(columns).containsExactly(2)
    }

    fun testAuthoritativeSnapshotCancelsPendingSessionRepairAndKeepsItsGuide() {
        configure("{\n    body\n  }")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf("body"))
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val execution = execution { _, request ->
            assertWorker()
            ready.complete(Unit)
            withContext(NonCancellable) { release.await() }
            BracketGuide(request.pair, 999)
        }
        val session = EditorGuideSession(
            editor, { TextRange(0, it.document.textLength) }, options = BracketGuidePreferences(),
            capabilities = EditorCapabilities.MAIN, activity = EditorActivity.ACTIVE, requestRepair = { request, isCurrent, publish -> execution.request(editor, request, isCurrent, publish) },
        )
        try {
            accept(session)
            val closeStart = editor.document.getLineStartOffset(2)
            WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(closeStart, " ") }
            session.documentChanged(DocumentChange(closeStart, 0, 1))
            assertThat(displayedGuide(editor)).isNull()
            awaitReady(ready)
            accept(session)
            assertThat(displayedGuide(editor)?.guideColumn).isEqualTo(3)
            release.complete(Unit)
            awaitIdle()
            assertThat(displayedGuide(editor)?.guideColumn).isEqualTo(3)
        } finally {
            release.complete(Unit)
            session.dispose()
        }
    }

    fun testCaretAndSettingsChangesCancelPendingSessionRepair() {
        for (changeSettings in listOf(false, true)) {
            configure("{\n    body\n  }")
            val editor = myFixture.editor
            editor.caretModel.moveToOffset(editor.document.text.indexOf("body"))
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val execution = execution { _, request ->
                assertWorker()
                ready.complete(Unit)
                withContext(NonCancellable) { release.await() }
                BracketGuide(request.pair, 999)
            }
            val preferences = BracketGuidePreferences()
            val session = EditorGuideSession(
                editor, { TextRange(0, it.document.textLength) }, options = preferences,
                capabilities = EditorCapabilities.MAIN, activity = EditorActivity.ACTIVE, requestRepair = { request, isCurrent, publish -> execution.request(editor, request, isCurrent, publish) },
            )
            try {
                accept(session)
                val closeStart = editor.document.getLineStartOffset(2)
                WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(closeStart, " ") }
                session.documentChanged(DocumentChange(closeStart, 0, 1))
                assertThat(displayedGuide(editor)).isNull()
                awaitReady(ready)
                if (changeSettings) {
                    session.updateOptions(preferences.copy(showActiveGuide = false), refreshColors = false)
                } else {
                    editor.caretModel.moveToOffset(0)
                    session.caretMoved()
                }
                release.complete(Unit)
                awaitIdle()
                assertThat(displayedGuide(editor)).isNull()
            } finally {
                release.complete(Unit)
                session.dispose()
            }
        }
    }

    fun testTabTransitionRepairsThePreviousAnchorBeyondTheForwardLineBudget() {
        // The exact snapshot sees the sole minimum at line300. Approximate repair must
        // inspect the closer and that old anchor before its bounded forward scan.
        configure("{\n" + "    body\n".repeat(299) + "body\n" + "    body\n".repeat(99) + "    }")
        val editor = myFixture.editor
        editor.settings.setTabSize(4)
        editor.caretModel.moveToOffset(editor.document.text.indexOf("body"))
        val requests = java.util.Collections.synchronizedList(mutableListOf<GuideRepairRequest>())
        val execution = execution { candidate, request ->
            assertWorker()
            requests += request
            GuideRepairExecution.calculateGuide(candidate, request)
        }
        val preferences = BracketGuidePreferences()
        val session = EditorGuideSession(
            editor, { TextRange(0, it.document.textLength) }, options = preferences,
            capabilities = EditorCapabilities.MAIN, activity = EditorActivity.ACTIVE,
            requestRepair = { request, isCurrent, publish -> execution.request(editor, request, isCurrent, publish) },
        )
        try {
            accept(session)
            assertThat(displayedGuide(editor)?.guideColumn).isZero()
            assertThat(displayedGuide(editor)?.anchorLine).isEqualTo(300)
            editor.settings.setTabSize(8)
            session.updateOptions(preferences, refreshColors = false)
            assertThat(displayedGuide(editor)).isNull()
            awaitIdle()
            assertThat(requests).hasSize(1)
            assertThat(requests.single().exact).isFalse()
            assertThat(requests.single().currentAnchorLine).isEqualTo(300)
            assertThat(displayedGuide(editor)?.guideColumn).isZero()
            assertThat(displayedGuide(editor)?.anchorLine).isEqualTo(300)
        } finally {
            session.dispose()
        }
    }

    fun testRepeatedGuideHideAndReplacementPreserveThePendingAnchor() {
        configure("{\n    body\nbody\n    }")
        val editor = myFixture.editor
        val presentation = ActiveGuidePresentation(editor)
        val pair = pair(editor)
        val preferences = BracketGuidePreferences()
        try {
            presentation.replace(pair, BracketGuide(pair, 0, 2), allowGuideFallback = false, preferences = preferences)
            presentation.hideGuide()
            assertThat(presentation.guideAnchorLine).isEqualTo(2)
            presentation.hideGuide()
            assertThat(presentation.guideAnchorLine).isEqualTo(2)
            presentation.replace(pair, indexedGuide = null, allowGuideFallback = true, preferences = preferences)
            assertThat(presentation.needsGuideRepair).isTrue()
            assertThat(presentation.guideAnchorLine).isEqualTo(2)
        } finally {
            presentation.clear(preserveGuide = false)
        }
    }

    fun testColorOnlySettingsSupersessionKeepsPostEditRefusalExact() {
        val source = "{\n" + "    body\n".repeat(300) + "    }"
        configure(source)
        val editor = myFixture.editor
        val body = source.indexOf("body")
        editor.caretModel.moveToOffset(body)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val modes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val execution = execution { candidate, request ->
            modes += request.exact
            if (modes.size == 1) {
                ready.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            GuideRepairExecution.calculateGuide(candidate, request)
        }
        val preferences = BracketGuidePreferences()
        val session = EditorGuideSession(
            editor, { TextRange(0, it.document.textLength) }, options = preferences,
            capabilities = EditorCapabilities.MAIN, activity = EditorActivity.ACTIVE, requestRepair = { request, isCurrent, publish -> execution.request(editor, request, isCurrent, publish) },
        )
        try {
            accept(session)
            WriteCommandAction.runWriteCommandAction(project) { editor.document.replaceString(body, body + 1, "x") }
            session.documentChanged(DocumentChange(body, 1, 1))
            assertThat(displayedGuide(editor)).isNull()
            awaitReady(ready)
            session.updateOptions(preferences.copy(guideOpacityPercent = 37), refreshColors = true)
            PlatformTestUtil.waitWithEventsDispatching("superseding repair started", { modes.size == 2 }, 10)
            release.complete(Unit)
            awaitIdle()
            assertThat(modes).containsExactly(true, true)
            assertThat(displayedGuide(editor)).isNull()
        } finally {
            release.complete(Unit)
            session.dispose()
        }
    }

    fun testHighlighterReplacementRejectsLateRepairWithoutADocumentEdit() {
        configure("{\n    body\n  }")
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val execution = execution { _, request ->
            ready.complete(Unit)
            release.await()
            BracketGuide(request.pair, 99)
        }
        var publications = 0
        execution.request(myFixture.editor, request(), { true }) { publications++ }
        awaitReady(ready)
        val documentStamp = myFixture.editor.document.modificationStamp
        (myFixture.editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )
        assertThat(myFixture.editor.document.modificationStamp).isEqualTo(documentStamp)
        release.complete(Unit)
        awaitIdle()
        assertThat(publications).isZero()
    }

    fun testDefaultProjectEditorCanRepairWithoutAProjectScope() {
        configure("{\n    body\n  }")
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(myFixture.editor.document, ProjectManager.getInstance().defaultProject)
        try {
            val execution = execution()
            var guide: BracketGuide? = null
            execution.request(editor, request(editor), { true }) { guide = it }
            awaitIdle()
            assertThat(guide?.guideColumn).isEqualTo(2)
        } finally {
            factory.releaseEditor(editor)
        }
    }

    fun testEditorReleaseAndScopeCancellationPreventLatePublicationForNullProjectEditors() {
        configure("{\n    body\n  }")
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(myFixture.editor.document)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val execution = execution { _, request ->
            assertWorker()
            ready.complete(Unit)
            withContext(NonCancellable) { release.await() }
            BracketGuide(request.pair, 1)
        }
        var publications = 0
        try {
            execution.request(editor, request(editor), { true }) { publications++ }
            awaitReady(ready)
            factory.releaseEditor(editor)
            lifetime.cancel()
            release.complete(Unit)
            awaitIdle()
            assertThat(publications).isZero()
        } finally {
            release.complete(Unit)
            if (!editor.isDisposed) factory.releaseEditor(editor)
        }
    }

    private fun configure(source: String) = myFixture.configureByText("Repair.txt", source)

    private fun execution(calculate: (suspend (Editor, GuideRepairRequest) -> BracketGuide?)? = null) =
        (if (calculate == null) GuideRepairExecution(scope) else GuideRepairExecution(scope, calculate))
            .also(executions::add)

    private fun request(editor: Editor = myFixture.editor): GuideRepairRequest {
        val fileType = EditorSurfaceClassifier.fileType(editor)
        return GuideRepairRequest(
            pair(editor), AnalysisStamp(editor, fileType, GuideRepairRequest.COVERAGE, emptySet()),
            fileType, emptySet(), exact = true,
        )
    }

    private fun pair(editor: Editor) = BracketPair(
        0, 1, editor.document.textLength - 1, 1, 0, 0, editor.document.lineCount - 1,
    )

    private fun accept(session: EditorGuideSession) {
        val editor = myFixture.editor
        val snapshot = ReadAction.compute<com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot, RuntimeException> {
            AnalysisInput(editor, myFixture.file.fileType, GuideRepairRequest.COVERAGE.copy(tokens = true), emptySet())
                .bracketSnapshot(listOf(pair(editor)))
        }
        session.accept(AnalysisOutcome.Complete(snapshot))
    }

    private fun displayedGuide(editor: Editor): BracketGuide? = editor.markupModel.allHighlighters
        .mapNotNull { (it.customRenderer as? BracketGuideDrawing)?.guide }.singleOrNull()

    private fun assertWorker() {
        assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
        assertThat(ApplicationManager.getApplication().isReadAccessAllowed).isFalse()
    }

    private fun awaitReady(ready: CompletableDeferred<Unit>) =
        PlatformTestUtil.waitWithEventsDispatching("repair started independently", { ready.isCompleted }, 10)

    private fun awaitGate(ready: CountDownLatch) =
        PlatformTestUtil.waitWithEventsDispatching("unlocked repair calculation paused", { ready.count == 0L }, 10)

    private fun awaitIdle() = PlatformTestUtil.waitWithEventsDispatching(
        "repair publication completed", { lifetime.children.all { root -> root.children.none { !it.isCompleted } } }, 10,
    )
}
