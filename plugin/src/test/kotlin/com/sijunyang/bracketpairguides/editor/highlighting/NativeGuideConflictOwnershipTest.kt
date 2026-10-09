package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeGuideConflictOwnershipTest : BasePlatformTestCase() {
    fun testOwnedCaretListenerCancelsAwayAndBackWithoutEditorEventFacade() = verifyCaretOwnership()

    fun testDisposingOneNativeServiceLeavesItsParentAndSiblingUsable() = verifyScopeOwnership()

    fun testParentCancellationStopsOwnedNativeWork() = verifyParentCancellation()

    private fun verifyCaretOwnership() {
        myFixture.configureByText("CaretOwner.java", "class CaretOwner { void run() {} }")
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = Gate()
        val publications = AtomicInteger()
        val notification = notification(parent, gate.probe(), publications, listenToCarets = true)
        try {
            notification.consider(myFixture.editor, guide())
            gate.awaitStarted()
            val offset = myFixture.editor.caretModel.offset
            myFixture.editor.caretModel.moveToOffset(offset + 1)
            myFixture.editor.caretModel.moveToOffset(offset)
            gate.awaitFinished()
            assertThat(publications.get()).isZero()
            assertThat(gate.release.count).isEqualTo(1)
            assertThat(parent.coroutineContext[Job]!!.isActive).isTrue()
        } finally {
            gate.release.countDown()
            Disposer.dispose(notification)
            parent.cancel()
            awaitParentStopped(parent)
        }
    }

    private fun verifyScopeOwnership() {
        myFixture.configureByText("ScopeOwner.java", "class ScopeOwner { void run() {} }")
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = Gate()
        val publications = AtomicInteger()
        val first = notification(parent, gate.probe(), publications, listenToCarets = true)
        val sibling = notification(
            parent,
            NativeConflictProbe { checkCanceled ->
                checkCanceled()
                true
            },
            publications,
        )
        try {
            first.consider(myFixture.editor, guide())
            gate.awaitStarted()
            Disposer.dispose(first)
            gate.awaitFinished()
            assertThat(parent.coroutineContext[Job]!!.isActive).isTrue()
            assertThat(gate.release.count).isEqualTo(1)
            sibling.consider(myFixture.editor, guide())
            PlatformTestUtil.waitWithEventsDispatching("Sibling native service did not publish", {
                publications.get() ==
                    1
            }, 10)
            assertThat(parent.coroutineContext[Job]!!.isActive).isTrue()
            assertThat(publications.get()).isEqualTo(1)
        } finally {
            gate.release.countDown()
            if (!Disposer.isDisposed(first)) Disposer.dispose(first)
            Disposer.dispose(sibling)
            parent.cancel()
            awaitParentStopped(parent)
        }
    }

    private fun verifyParentCancellation() {
        myFixture.configureByText("ParentOwner.java", "class ParentOwner { void run() {} }")
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = Gate()
        val publications = AtomicInteger()
        val notification = notification(parent, gate.probe(), publications)
        try {
            notification.consider(myFixture.editor, guide())
            gate.awaitStarted()
            parent.cancel()
            gate.awaitFinished()
            awaitParentStopped(parent)
            assertThat(publications.get()).isZero()
            assertThat(gate.release.count).isEqualTo(1)
        } finally {
            gate.release.countDown()
            Disposer.dispose(notification)
            parent.cancel()
            awaitParentStopped(parent)
        }
    }

    private fun notification(
        parent: CoroutineScope,
        probe: NativeConflictProbe,
        publications: AtomicInteger,
        listenToCarets: Boolean = false,
    ): NativeGuideConflictNotification = NativeGuideConflictNotification(
        scope = parent,
        preferences = { BracketGuidePreferences() },
        captureConflict = { _, _, _ ->
            assertThat(ApplicationManager.getApplication().isDispatchThread).isTrue()
            probe
        },
        createNotification = { _, _ ->
            assertThat(ApplicationManager.getApplication().isDispatchThread).isTrue()
            publications.incrementAndGet()
            Notification("Bracket Pair Guides Test", "Ownership", "Test", NotificationType.INFORMATION)
        },
        schedule = { task -> ApplicationManager.getApplication().invokeLater { task() } },
        subscribeToCarets = { listener, disposable ->
            if (listenToCarets) EditorFactory.getInstance().eventMulticaster.addCaretListener(listener, disposable)
        },
    )

    private fun awaitParentStopped(parent: CoroutineScope) {
        PlatformTestUtil.waitWithEventsDispatching("Native child work did not stop", {
            parent.coroutineContext[Job]!!.children.none()
        }, 10)
    }

    private fun guide(): BracketGuide = BracketGuide(
        pair = BracketPair(
            openOffset = 0,
            openTokenLength = 1,
            closeOffset = 10,
            closeTokenLength = 1,
            depth = 0,
            openLine = 0,
            closeLine = 1,
        ),
        guideColumn = 0,
        anchorLine = 1,
    )

    private class Gate {
        val release = CountDownLatch(1)
        private val started = CountDownLatch(1)
        private val finished = CountDownLatch(1)

        fun probe(): NativeConflictProbe = NativeConflictProbe { checkCanceled ->
            check(!ApplicationManager.getApplication().isDispatchThread)
            started.countDown()
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) checkCanceled()
                checkCanceled()
                true
            } finally {
                finished.countDown()
            }
        }

        fun awaitStarted() {
            PlatformTestUtil.waitWithEventsDispatching("Native inspection did not start", { started.count == 0L }, 10)
        }

        fun awaitFinished() {
            PlatformTestUtil.waitWithEventsDispatching("Native inspection did not cancel", { finished.count == 0L }, 10)
        }
    }
}
