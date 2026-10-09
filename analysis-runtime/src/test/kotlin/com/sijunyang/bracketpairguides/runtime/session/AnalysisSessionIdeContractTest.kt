package com.sijunyang.bracketpairguides.runtime.session

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ref.GCWatcher
import com.intellij.util.ui.UIUtil
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.runtime.bootstrap.RuntimeGuideWorkFactory
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisCaptureObserver
import com.sijunyang.bracketpairguides.ui.work.AnalysisUpdate
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWork
import com.sijunyang.bracketpairguides.ui.work.NativeConflictEvidence
import com.sijunyang.bracketpairguides.ui.work.NativeInterest
import com.sijunyang.bracketpairguides.ui.work.RepairUpdate
import com.sijunyang.bracketpairguides.ui.work.ViewApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Tests the production dispatcher and bounded SDK reads through complete desired-state requests. */
class AnalysisSessionIdeContractTest : BasePlatformTestCase() {
    private lateinit var factory: RuntimeGuideWorkFactory
    private lateinit var work: GuideWork
    private lateinit var parentJob: Job
    private var permanentJobs: Set<Job> = emptySet()
    private val entered = AtomicInteger()
    private val violation = AtomicReference<String?>()
    private var publications = 0
    private var onApply: ((AnalysisUpdate) -> Unit)? = null
    private val observer = object : AnalysisCaptureObserver() {
        override fun requested(phase: Int): Long {
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread ||
                app.isReadAccessAllowed
            ) {
                violation.set("capture requested under EDT/read access")
            }
            return 0
        }
        override fun entered(requestId: Long, phase: Int): Long {
            val app = ApplicationManager.getApplication()
            if (app.isDispatchThread ||
                !app.isReadAccessAllowed
            ) {
                violation.set("capture body outside background read access")
            }
            entered.incrementAndGet()
            return 0
        }
        override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) = Unit
    }
    private val initial = GuideDemand(
        0,
        AnalysisCoverage(true, true, true),
        emptySet(),
        true,
        0,
        null,
        GuideChange.CONFIGURATION,
        NativeInterest(0, false),
    )
    private fun open() {
        myFixture.configureByText("RuntimeContract.java", "class C {\n  void f() { }\n}")
        parentJob = SupervisorJob()
        factory = RuntimeGuideWorkFactory(CoroutineScope(parentJob + Dispatchers.Default + observer))
        work = factory.attach(
            myFixture.editor,
            object : GuideView {
                override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread)
                    publications++
                    onApply?.invoke(update)
                    return ViewApplication.APPLIED
                }
                override fun applyRepair(update: RepairUpdate): ViewApplication = ViewApplication.APPLIED
                override fun reportNativeConflict(evidence: NativeConflictEvidence) = Unit
            },
        )
        permanentJobs = descendants(parentJob)
    }
    private fun descendants(parent: Job): Set<Job> = parent.children.flatMap { listOf(it) + descendants(it) }.toSet()
    private fun settle() {
        UIUtil.dispatchAllInvocationEvents()
        await { descendants(parentJob).all { it in permanentJobs } }
    }
    override fun tearDown() {
        try {
            if (::work.isInitialized) work.close()
            if (::factory.isInitialized) Disposer.dispose(factory)
        } finally {
            super.tearDown()
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            LockSupport.parkNanos(1_000_000)
        }
        assertTrue("production worker did not settle", condition())
        assertNull(violation.get())
    }
    fun testReadsAreBackgroundAndPublicationIsEdtAndDuplicateDemandIsInert() {
        open()
        work.reconcile(initial)
        await { publications == 1 }
        val count = entered.get()
        work.reconcile(initial)
        work.reconcile(initial.copy(change = GuideChange.PRESENTATION))
        work.refresh()
        settle()
        assertEquals(count, entered.get())
        assertEquals(1, publications)
    }
    fun testReentrantEditCannotAcceptOldResult() {
        open()
        onApply = {
            onApply = null
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
            work.reconcile(initial.copy(guideRevision = 1, change = GuideChange.CONTENT))
        }
        work.reconcile(initial)
        await { publications == 2 }
        val count = entered.get()
        work.refresh()
        settle()
        assertEquals(count, entered.get())
    }
    fun testCloseDuringPublicationPreventsAnyLaterWork() {
        open()
        onApply = { work.close() }
        work.reconcile(initial)
        await { publications == 1 }
        val count = entered.get()
        work.close()
        work.refresh()
        work.reconcile(initial.copy(guideRevision = 1, change = GuideChange.CONTENT))
        settle()
        assertEquals(count, entered.get())
        assertEquals(1, publications)
    }
    fun testRicherAcceptedResultSurvivesCoverageReduction() {
        open()
        work.reconcile(initial)
        await { publications == 1 }
        settle()
        val count = entered.get()
        work.reconcile(initial.copy(revision = 1, coverage = AnalysisCoverage(true, false, false)))
        work.refresh()
        settle()
        assertEquals(count, entered.get())
        assertEquals(1, publications)
    }
    fun testSharedDocumentEditorsKeepIndependentPublicationAndCloseLifetimes() {
        open()
        work.close()
        val editors = EditorFactory.getInstance()
        val document = myFixture.editor.document
        val editorA = editors.createEditor(document, project, JavaFileType.INSTANCE, false)
        val editorB = editors.createEditor(document, project, JavaFileType.INSTANCE, false)
        var publishedA = 0
        var publishedB = 0
        var latestA: AnalysisUpdate? = null
        var latestB: AnalysisUpdate? = null
        fun view(receive: (AnalysisUpdate) -> Unit): GuideView = object : GuideView {
            override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                receive(update)
                return ViewApplication.APPLIED
            }
            override fun applyRepair(update: RepairUpdate) = ViewApplication.APPLIED
            override fun reportNativeConflict(evidence: NativeConflictEvidence) = Unit
        }
        val workA = factory.attach(
            editorA,
            view {
                publishedA++
                latestA = it
            },
        )
        val workB = factory.attach(
            editorB,
            view {
                publishedB++
                latestB = it
            },
        )
        try {
            permanentJobs = descendants(parentJob)
            workA.reconcile(initial)
            workB.reconcile(initial)
            await { publishedA == 1 && publishedB == 1 }
            settle()
            for (update in listOf(checkNotNull(latestA), checkNotNull(latestB))) {
                val lookup = (update.result as AnalysisResult.Available).view
                assertEquals(8, checkNotNull(lookup.activePairAt(10)).openOffset)
            }
            workA.close()
            editors.releaseEditor(editorA)
            assertTrue(editorA.isDisposed)
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "/*new*/") }
            workB.reconcile(initial.copy(revision = 1, guideRevision = 1, change = GuideChange.CONTENT))
            workA.refresh()
            await { publishedB == 2 }
            settle()
            assertEquals(1, publishedA)
            assertEquals(1L, checkNotNull(latestB).demandRevision)
            val lookup = (checkNotNull(latestB).result as AnalysisResult.Available).view
            assertEquals(15, checkNotNull(lookup.activePairAt(17)).openOffset)
        } finally {
            workA.close()
            workB.close()
            if (!editorA.isDisposed) editors.releaseEditor(editorA)
            editors.releaseEditor(editorB)
            Disposer.dispose(factory)
            await { parentJob.children.none() }
        }
        assertTrue(editorB.isDisposed)
        assertEquals(1, publishedA)
        assertEquals(2, publishedB)
    }
    fun testDocumentGenerationSurvivesStampResetAndDormantReacquire() {
        open()
        val document = myFixture.editor.document
        val calculation = DocumentCalculation(document)
        calculation.acquire()
        try {
            val stamp = document.modificationStamp
            val before = calculation.revisionFor(stamp)
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(10, 11, "\t")
                (document as com.intellij.openapi.editor.ex.DocumentEx).setModificationStamp(stamp)
            }
            assertTrue(calculation.revisionFor(stamp) > before)
            val after = calculation.revisionFor(stamp)
            calculation.release()
            calculation.acquire()
            assertTrue(calculation.revisionFor(stamp) > after)
        } finally {
            calculation.release()
        }
    }
    private fun captureAcceptedPayload(): GCWatcher {
        var watcher: GCWatcher? = null
        onApply = { update -> watcher = GCWatcher.tracking((update.result as AnalysisResult.Available).view) }
        work.reconcile(initial)
        await { publications == 1 }
        settle()
        onApply = null
        return checkNotNull(watcher)
    }
    fun testHiddenDemandReleasesAcceptedPayloadAndWakeUpDoesNotRecapture() {
        open()
        val watcher = captureAcceptedPayload()
        val count = entered.get()
        work.reconcile(initial.copy(visible = false))
        work.refresh()
        settle()
        watcher.ensureCollected()
        assertEquals(count, entered.get())
    }
    fun testNoFacetDemandReleasesAcceptedPayloadAndWakeUpDoesNotRecapture() {
        open()
        val watcher = captureAcceptedPayload()
        val count = entered.get()
        work.reconcile(initial.copy(coverage = AnalysisCoverage(false, false, false)))
        work.refresh()
        settle()
        watcher.ensureCollected()
        assertEquals(count, entered.get())
    }
    private class PayloadView(private val payload: ByteArray) : GuideView {
        override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
            payload[0] = 1
            return ViewApplication.APPLIED
        }
        override fun applyRepair(update: RepairUpdate) = ViewApplication.APPLIED
        override fun reportNativeConflict(evidence: NativeConflictEvidence) = Unit
    }
    private fun closeDetachedEditor(): GCWatcher {
        val editors = EditorFactory.getInstance()
        val editor = editors.createEditor(editors.createDocument("{}"), project)
        val payload = ByteArray(1024 * 1024)
        val view = PayloadView(payload)
        work = factory.attach(editor, view)
        val watcher = GCWatcher.tracking(editor, payload, view)
        work.close()
        editors.releaseEditor(editor)
        return watcher
    }
    fun testRetainedClosedWorkDoesNotRetainEditorViewOrPayload() {
        open()
        work.close()
        val watcher = closeDetachedEditor()
        settle()
        watcher.ensureCollected()
        work.close()
        work.refresh()
    }
    fun testImmediateCloseCancelsPendingPublication() {
        open()
        work.reconcile(initial)
        work.close()
        settle()
        assertEquals(0, publications)
    }
}
