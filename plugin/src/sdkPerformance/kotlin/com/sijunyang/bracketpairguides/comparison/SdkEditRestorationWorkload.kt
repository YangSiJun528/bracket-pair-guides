package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Actual document routing and observed SDK geometry; no synthetic repair scheduling. */
suspend fun ComparisonFixture.runEditRestorationWorkload() {
    for (corpus in repairCorpora()) {
        emit("kind" to "corpus", "workload" to "edit-restoration", "corpus" to corpus.name,
            "sha256Utf8" to editRestorationFingerprint(corpus.text), "characters" to corpus.text.length,
            "editOffset" to corpus.editOffset, "oldLength" to 1, "newLength" to 1, "tabSize" to 4,
            "boundedRepairRefusal" to corpus.refuses, "expectedGuideColumn" to corpus.column,
            "scope" to "real accepted live session; actual command/document multicaster; synchronous hide and first observed correct SDK guide",
            "limitations" to "headless ACTIVE; no paint or daemon orchestration; restoration origin not observable; all factory-owned competing work retained",
            "ownershipDifference" to "baseline MAIN_EDITOR daemon full-refresh is outside this owned factory fixture; candidate full-refresh and repair are factory-owned",
            "latencyDefinition" to "SDK beforeRemoved callback to first correct-guide poll; upper bound including polling/dispatch delay, never exclusive repair duration")
        repeat(warmups + repeats) { index ->
            val tabbed = index % 2 == 0
            val startingTabbed = !tabbed
            val startingText = if (startingTabbed) corpus.text.replaceRange(corpus.editOffset, corpus.editOffset + 1, "\t") else corpus.text
            val editor = editor("EditRestoration.java", startingText)
            val readsUnderlying = host.newReads()
            val trace = SdkAllocationSegments(allocation)
            val reads = object : ReadRecorder by readsUnderlying {
                override val context = readsUnderlying.context + trace
            }
            var handle: EditHandle? = null
            val listenerLifetime = Disposer.newDisposable("issue97-edit-restoration-observation")
            val target = AtomicReference<RangeHighlighter?>()
            val removalStarted = AtomicLong()
            try {
                withContext(Dispatchers.EDT) {
                    editor.settings.setTabSize(4)
                    editor.caretModel.moveToOffset(corpus.editOffset + 1)
                    handle = host.editSession(editor, reads)
                }
                // Language 1.9 cannot smart-cast the nullable cleanup owner captured by changing closures.
                @Suppress("RedundantRequireNotNullCall")
                val active = checkNotNull(handle)
                // Genuine initial acceptance and request unwind are outside every edit sample.
                var initial: GuideShape? = null
                val initialDeadline = System.nanoTime() + 30_000_000_000L
                while (System.nanoTime() < initialDeadline) {
                    initial = withContext(Dispatchers.EDT) { active.visibleGuide() }
                    if (initial != null && !active.workerActive()) break
                    delay(1.milliseconds)
                }
                val previous = checkNotNull(initial) { "No genuine initial guide: ${corpus.name}" }
                check(!active.workerActive()) { "Initial accepted request did not unwind: ${corpus.name}" }
                trace.awaitClosed()
                check(previous.pair.open == 0 && previous.pair.close == corpus.text.lastIndex &&
                    previous.guideColumn == corpus.column && previous.anchorLine == expectedAnchor(corpus, startingTabbed)) {
                    "Unexpected accepted initial guide ${corpus.name}: $previous"
                }
                reads.reset()
                val allocationBefore = trace.snapshot()
                withContext(Dispatchers.EDT) {
                    val guideMarks = active.markup().filterIsInstance<RangeHighlighter>()
                        .filter { it.isValid && it.customRenderer?.javaClass?.simpleName == "BracketGuideDrawing" }
                    target.set(guideMarks.single())
                    (editor.markupModel as MarkupModelEx).addMarkupModelListener(listenerLifetime,
                        object : MarkupModelListener {
                            override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                                if (target.get() === highlighter) removalStarted.compareAndSet(0, System.nanoTime())
                            }
                        })
                }
                val expected = GuideShape(previous.pair, corpus.column, expectedAnchor(corpus, tabbed))
                var commandRequested = 0L
                var commandReturned = 0L
                var editStarted = 0L
                var mutationCompleted = 0L
                var mutationAllocatedBytes: Long? = null
                var immediate: GuideShape? = null
                var previousMarkValidAfterListeners = false
                withContext(Dispatchers.EDT) {
                    commandRequested = System.nanoTime()
                    WriteCommandAction.runWriteCommandAction(editor.project) {
                        val before = allocatedOnCurrentThread()
                        editStarted = System.nanoTime()
                        editor.document.replaceString(corpus.editOffset, corpus.editOffset + 1, if (tabbed) "\t" else " ")
                        PsiDocumentManager.getInstance(editor.project!!).commitDocument(editor.document)
                        mutationCompleted = System.nanoTime()
                        mutationAllocatedBytes = allocationDelta(before, allocatedOnCurrentThread())
                        // Inspect before relinquishing write access: async publication cannot mask a missing hide.
                        immediate = active.visibleGuide()
                        previousMarkValidAfterListeners = target.get()?.isValid == true
                    }
                    commandReturned = System.nanoTime()
                }
                val expectedHide = corpus.candidateLines != 0
                val immediateHideCorrect = if (expectedHide) immediate == null && !previousMarkValidAfterListeners &&
                    removalStarted.get() in editStarted..mutationCompleted else immediate == expected && removalStarted.get() == 0L
                if (!immediateHideCorrect) {
                    emit("kind" to "edit-restoration-immediate-failure", "corpus" to corpus.name,
                        "iteration" to index - warmups, "expectedHide" to expectedHide,
                        "immediateGuide" to immediate, "expectedGuide" to expected,
                        "previousMarkValid" to previousMarkValidAfterListeners,
                        "removalStartedNanos" to removalStarted.get())
                    error("Actual document callback hide/reuse contract failed: ${corpus.name}")
                }
                var restored = immediate
                var observed = if (restored == expected) commandReturned else 0L
                var polls = 0
                var maximumPollGap = 0L
                var lastPoll = commandReturned
                val restorationDeadline = commandReturned + 5_000_000_000L
                while (restored != expected && System.nanoTime() < restorationDeadline) {
                    restored = withContext(Dispatchers.EDT) { active.visibleGuide() }
                    val now = System.nanoTime()
                    maximumPollGap = maxOf(maximumPollGap, now - lastPoll)
                    lastPoll = now
                    polls++
                    if (restored == expected) { observed = now; break }
                    // A refused repair can quiesce without restoration when no daemon is orchestrated.
                    if (corpus.refuses && !active.workerActive()) break
                    delay(1.milliseconds)
                }
                val workDeadline = System.nanoTime() + 5_000_000_000L
                while (active.workerActive() && System.nanoTime() < workDeadline) delay(1.milliseconds)
                check(!active.workerActive()) { "Edit work did not unwind: ${corpus.name}" }
                trace.awaitClosed()
                // Observe any full-analysis winner after owned work settles, retaining the first observation if already seen.
                val settled = withContext(Dispatchers.EDT) { active.visibleGuide() }
                if (observed == 0L && settled == expected) observed = System.nanoTime()
                val outcome = when {
                    !expectedHide -> "synchronous-same-line-geometry"
                    observed > 0L -> "correct-guide-observed-origin-unknown"
                    corpus.refuses && settled == null -> "no-owned-restoration-after-refusal"
                    else -> "correct-guide-not-observed"
                }
                val allocationAfter = trace.snapshot()
                if (index >= warmups) emit("kind" to "edit-restoration", "corpus" to corpus.name,
                    "iteration" to index - warmups, "tabbed" to tabbed,
                    "startingTextSha256Utf8" to editRestorationFingerprint(startingText), "expectedHide" to expectedHide,
                    "immediateGuide" to immediate, "previousMarkValidAfterListeners" to previousMarkValidAfterListeners,
                    "expectedGuide" to expected, "settledGuide" to settled, "outcome" to outcome,
                    "commandRequestedNanos" to commandRequested, "commandReturnedNanos" to commandReturned,
                    "editStartedNanos" to editStarted, "mutationCompletedNanos" to mutationCompleted,
                    "guideRemovalCallbackNanos" to removalStarted.get(), "correctGuideObservedNanos" to observed,
                    "mutationAndCommitNs" to mutationCompleted - editStarted,
                    "mutationAndCommitEdtAllocatedBytes" to mutationAllocatedBytes,
                    "commandScope" to "actual WriteCommandAction; mutation metric includes document callbacks/commit, excludes command wrapper and post-mutation geometry inspection",
                    "hideToObservedCorrectGuideNs" to if (expectedHide && observed > 0) observed - removalStarted.get() else null,
                    "polls" to polls, "maximumObservedPollGapNs" to maximumPollGap,
                    "pollMetric" to "beforeRemoved is hide-start observation; correct SDK geometry observed after full EDT turns; elapsed is an upper bound",
                    "asyncAllocationBefore" to allocationBefore, "asyncAllocationAfter" to allocationAfter,
                    "asyncAllocatedBytes" to allocationDelta(allocationBefore["coroutineAllocatedBytes"] as? Long,
                        allocationAfter["coroutineAllocatedBytes"] as? Long),
                    "allocationScopeRule" to "synchronous mutation and inherited coroutine segments are separate non-additive scopes; no whole-session sum",
                    "readObservationCoverage" to if (host.implementation.startsWith("baseline"))
                        "baseline repair worker has no capture observer; empty arrays do not mean no SDK reads"
                        else "owned factory read observer; all competing owned jobs retained",
                    "requests" to reads.requests(), "reads" to reads.snapshot(),
                    "ownedWorkersQuiescent" to true, "restorationOrigin" to "unknown", "nativePaintVerified" to false)
                check(settled == expected || corpus.refuses && settled == null) {
                    "Unexpected settled SDK guide ${corpus.name}: expected=$expected, actual=$settled"
                }
            } finally {
                try {
                    try {
                        withContext(NonCancellable + Dispatchers.EDT) {
                            target.set(null)
                            try { Disposer.dispose(listenerLifetime) } finally { handle?.close() }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            val deadline = System.nanoTime() + 5_000_000_000L
                            while (handle?.workerActive() == true && System.nanoTime() < deadline) delay(1.milliseconds)
                            check(handle?.workerActive() != true) { "Edit session did not close" }
                            trace.awaitClosed()
                        }
                    }
                    withContext(NonCancellable + Dispatchers.EDT) {
                        host.assertNoUnownedAttachments()
                        val remaining = editor.markupModel.allHighlighters.filter {
                            it.isValid && (it.customRenderer?.javaClass?.simpleName == "BracketGuideDrawing" ||
                                it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true ||
                                it.layer == com.intellij.openapi.editor.markup.HighlighterLayer.ELEMENT_UNDER_CARET)
                        }
                        check(remaining.isEmpty()) { "Owned SDK markup survived edit session close: ${remaining.size}" }
                        emit("kind" to "edit-restoration-cleanup", "corpus" to corpus.name,
                            "iteration" to index - warmups, "ownedWorkersQuiescent" to true,
                            "allocationTraceClosed" to true, "remainingOwnedMarkup" to remaining.size,
                            "scope" to "actual registry/SDK markup and owned jobs before guaranteed editor release; not heap GC evidence")
                    }
                } finally {
                    release(editor)
                }
            }
        }
    }
}

private fun expectedAnchor(corpus: RepairCorpus, tabbed: Boolean): Int = when {
    corpus.candidateLines == 0 -> 0
    corpus.name.contains("character") -> 2
    tabbed -> 2
    else -> 1
}

private fun ComparisonFixture.allocatedOnCurrentThread(): Long? =
    allocation?.getThreadAllocatedBytes(Thread.currentThread().id)?.takeIf { it >= 0 }

private fun allocationDelta(before: Long?, after: Long?): Long? =
    if (before != null && after != null && after >= before) after - before else null

private fun editRestorationFingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
