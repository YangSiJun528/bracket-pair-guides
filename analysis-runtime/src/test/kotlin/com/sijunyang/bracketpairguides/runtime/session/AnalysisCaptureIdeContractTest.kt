package com.sijunyang.bracketpairguides.runtime.session

import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.LanguageBraceMatching
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.BracketPair
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.runtime.bootstrap.RuntimeGuideWorkFactory
import com.sijunyang.bracketpairguides.ui.work.AnalysisUpdate
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWork
import com.sijunyang.bracketpairguides.ui.work.NativeConflictEvidence
import com.sijunyang.bracketpairguides.ui.work.NativeInterest
import com.sijunyang.bracketpairguides.ui.work.RepairIntent
import com.sijunyang.bracketpairguides.ui.work.RepairUpdate
import com.sijunyang.bracketpairguides.ui.work.ViewApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** A real language-plugin callback makes capture races reproducible without a production test setter. */
class AnalysisCaptureIdeContractTest : BasePlatformTestCase() {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val once = AtomicBoolean()
    private val violation = AtomicReference<String?>()
    private lateinit var language: Language
    private lateinit var matcher: PairedBraceMatcher
    private lateinit var factory: RuntimeGuideWorkFactory
    private lateinit var work: GuideWork
    private lateinit var parent: Job
    private var secondary: Editor? = null
    private var published = 0
    private var repaired = 0
    private var latest: AnalysisUpdate? = null
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

    private fun open(secondaryEditor: Boolean = false) {
        myFixture.configureByText("CaptureContract.java", "class C {\n  void f() { }\n}")
        language = myFixture.file.language
        val original = checkNotNull(LanguageBraceMatching.INSTANCE.forLanguage(language))
        matcher = object : PairedBraceMatcher {
            override fun getPairs(): Array<BracePair> {
                val app = ApplicationManager.getApplication()
                if (!app.isDispatchThread && once.compareAndSet(false, true)) {
                    if (!app.isReadAccessAllowed) violation.set("matcher callback was not under SDK read access")
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "test did not release language-plugin capture" }
                }
                return original.pairs
            }
            override fun isPairedBracesAllowedBeforeType(left: IElementType, next: IElementType?): Boolean =
                original.isPairedBracesAllowedBeforeType(left, next)
            override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int =
                original.getCodeConstructStart(file, openingBraceOffset)
        }
        LanguageBraceMatching.INSTANCE.addExplicitExtension(language, matcher)
        assertSame(matcher, LanguageBraceMatching.INSTANCE.forLanguage(language))
        parent = SupervisorJob()
        factory = RuntimeGuideWorkFactory(CoroutineScope(parent + Dispatchers.Default))
        val editor = if (secondaryEditor) {
            EditorFactory.getInstance().createEditor(
                myFixture.editor.document,
                project,
                myFixture.file.virtualFile,
                true,
                EditorKind.CONSOLE,
            ).also { secondary = it }
        } else {
            myFixture.editor
        }
        work = factory.attach(
            editor,
            object : GuideView {
                override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread)
                    published++
                    latest = update
                    return ViewApplication.APPLIED
                }
                override fun applyRepair(update: RepairUpdate): ViewApplication {
                    assertTrue(ApplicationManager.getApplication().isDispatchThread)
                    repaired++
                    return ViewApplication.APPLIED
                }
                override fun reportNativeConflict(evidence: NativeConflictEvidence) = Unit
            },
        )
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            LockSupport.parkNanos(1_000_000)
        }
        assertTrue("actual SDK worker did not reach expected state", condition())
        assertNull(violation.get())
    }
    private fun drain() {
        work.close()
        if (!Disposer.isDisposed(factory)) Disposer.dispose(factory)
        await { parent.children.none() }
    }
    override fun tearDown() {
        try {
            release.countDown()
            if (::factory.isInitialized) drain()
            secondary?.let { EditorFactory.getInstance().releaseEditor(it) }
            if (::matcher.isInitialized) LanguageBraceMatching.INSTANCE.removeExplicitExtension(language, matcher)
        } finally {
            super.tearDown()
        }
    }
    fun testCloseDuringRealMatcherReadRejectsPublicationAfterCaptureUnwinds() {
        open()
        work.reconcile(initial)
        await { entered.count == 0L }
        work.close()
        release.countDown()
        drain()
        assertEquals(0, published)
    }
    fun testSourceEditAfterInFlightReadRejectsOldResultAndPublishesNewCoordinates() {
        open()
        work.reconcile(initial)
        await { entered.count == 0L }
        assertEquals(0, published)
        release.countDown()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "/*new*/") }
        work.reconcile(initial.copy(revision = 1, guideRevision = 1, change = GuideChange.CONTENT))
        await { published == 1 }
        val lookup = (checkNotNull(latest).result as AnalysisResult.Available).view
        assertEquals(15, checkNotNull(lookup.activePairAt(17)).openOffset)
        drain()
        assertEquals(1, published)
    }
    fun testSecondaryRepairPublishesWhileDelayedFullCaptureIsStillBlocked() {
        open(secondaryEditor = true)
        val pair = BracketPair(8, 1, myFixture.editor.document.textLength - 1, 1, 0, 0, 2)
        work.reconcile(initial.copy(repair = RepairIntent(pair, true, null)))
        await { entered.count == 0L }
        await { repaired == 1 }
        assertEquals(0, published)
        release.countDown()
        await { published == 1 }
        drain()
        assertEquals(1, repaired)
    }
}
