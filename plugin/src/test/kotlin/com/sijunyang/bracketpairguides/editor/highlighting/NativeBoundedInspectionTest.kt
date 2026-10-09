package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BracketPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeBoundedInspectionTest : BasePlatformTestCase() {
    fun testProductionChunksHaveSeparatePreparationAndAcceptExactPair() = verifyInspection(false, false)
    fun testProductionLazyPreparationUsesSeparateReadBody() = verifyInspection(false, false, true)
    fun testStructuralAndForwardScopeTraversalUseIndependentChunks() = verifyInspection(false, false, scopeMode = true)
    fun testLaterStructuralWriteDiscardsScopeProof() = verifyInspection(true, false, scopeMode = true)
    fun testWriteQueuedDuringLaterTraversalInvalidatesEntireProof() = verifyInspection(true, false)
    fun testCancellationQueuedDuringLaterTraversalStopsProof() = verifyInspection(false, true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyInspection(write: Boolean, cancel: Boolean, lazy: Boolean = false, scopeMode: Boolean = false) {
        myFixture.configureByText("Chunks.java", "class Chunks { void run() {\n" + "call(1);\n".repeat(12000) + "} }")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val editor = myFixture.editor
        val chars = editor.document.text
        val open = chars.indexOf("{\n")
        val close = chars.indexOf('}', open)
        editor.caretModel.moveToOffset(if (scopeMode) chars.lastIndexOf("call") + 1 else open)
        val pair = BracketPair(openOffset = open, openTokenLength = 1, closeOffset = close,
            closeTokenLength = 1, depth = 1, openLine = editor.document.getLineNumber(open), closeLine = editor.document.getLineNumber(close))
        val block = if (lazy) checkNotNull(PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(open), PsiCodeBlock::class.java, false)) else null
        val previousLanguage = block?.node?.getUserData(ILazyParseableElementType.LANGUAGE_KEY)
        if (block != null) block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, JavaLanguage.INSTANCE)
        val capture = NativeGuideConflictDetector.captureMarkerSources(editor, pair, true)
        val phases = CopyOnWriteArrayList<Int>()
        val traversal = AtomicInteger()
        val targetPhase = if (scopeMode) NativeCaptureObserver.STRUCTURAL_TRAVERSAL else NativeCaptureObserver.DIRECT_TRAVERSAL
        val requested = AtomicBoolean()
        val changed = AtomicBoolean()
        val releasedGate = CompletableDeferred<Unit>()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val observer = object : NativeCaptureObserver() {
            override fun requested(phase: Int) = phase.toLong()
            override fun entered(requestId: Long, phase: Int): Long {
                assertThat(ApplicationManager.getApplication().isDispatchThread).isFalse()
                assertThat(ApplicationManager.getApplication().isReadAccessAllowed).isTrue()
                phases += phase
                return phase.toLong()
            }
            override fun exited(attemptId: Long, completed: Boolean, failure: Throwable?) = Unit
            override suspend fun released(phase: Int) {
                if (phase == targetPhase && requested.get() && !changed.get()) {
                    assertThat(ApplicationManager.getApplication().isReadAccessAllowed).isFalse()
                    releasedGate.await()
                }
            }
            override fun progressed(phase: Int, ownedOperations: Int) {
                if (phase == targetPhase && traversal.incrementAndGet() == 8 && (write || cancel)) {
                    requested.set(true)
                    ApplicationManager.getApplication().invokeLater {
                        if (write) WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(editor.document.textLength, "\n") }
                        else scope.cancel()
                        changed.set(true)
                        releasedGate.complete(Unit)
                    }
                }
            }
        }
        try {
            val worker = scope.async(observer) {
                val context = currentCoroutineContext()
                capture {
                    context.ensureActive()
                    if (ApplicationManager.getApplication().isReadAccessAllowed) ProgressManager.checkCanceled()
                }
            }
            PlatformTestUtil.waitWithEventsDispatching("Bounded native inspection did not finish", { worker.isCompleted && (!(write || cancel) || changed.get()) }, 10)
            assertThat(phases).contains(NativeCaptureObserver.ADMISSION, NativeCaptureObserver.PREPARATION, NativeCaptureObserver.DIRECT_CLASSIFICATION, NativeCaptureObserver.DIRECT_TRAVERSAL)
            assertThat(traversal.get()).isGreaterThan(1)
            if (write || cancel) assertThat(requested.get()).isTrue()
            if (cancel) assertThat(worker.isCancelled).isTrue()
            else if (write) assertThat(worker.getCompleted()).isEqualTo(NativeGuideConflictDetector.NativeMarkerSources.NONE)
            else {
                if (scopeMode) {
                    assertThat(phases).contains(NativeCaptureObserver.STRUCTURAL_TRAVERSAL, NativeCaptureObserver.SCOPE_MATCH_CLASSIFICATION, NativeCaptureObserver.SCOPE_MATCH_TRAVERSAL)
                    assertThat(worker.getCompleted().currentScope).isTrue()
                    assertThat(worker.getCompleted().direct).isFalse()
                } else assertThat(worker.getCompleted().direct).isTrue()
            }
        } finally {
            releasedGate.complete(Unit)
            scope.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Native fixture shutdown", { owner.isCompleted }, 10)
            if (block != null) block.node.putUserData(ILazyParseableElementType.LANGUAGE_KEY, previousLanguage)
        }
    }
}
