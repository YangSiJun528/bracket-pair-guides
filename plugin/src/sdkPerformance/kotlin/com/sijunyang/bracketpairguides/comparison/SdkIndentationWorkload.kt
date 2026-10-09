package com.sijunyang.bracketpairguides.comparison

import com.intellij.application.options.CodeStyle
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Identical additive SDK action workload: observations never require retention on either side. */
suspend fun ComparisonFixture.runIndentationWorkload() {
    val text = "class Indentation {\n    void run() {\n            work();\n        }\n}\n"
    val tabbedText = text.replace("            work();", "                work();")
    val editor = editor("Indentation.java", text)
    val readsUnderlying = host.newReads()
    val trace = SdkAllocationSegments(allocation)
    val reads = object : ReadRecorder by readsUnderlying {
        override val context = readsUnderlying.context + trace
    }
    var handle: EditHandle? = null
    val project = checkNotNull(editor.project)
    var restoreIndentOptions: (() -> Unit)? = null
    try {
        withContext(Dispatchers.EDT) {
            val options = CodeStyle.getSettings(project).getIndentOptions(JavaFileType.INSTANCE)
            val tabSize = options.TAB_SIZE
            val indentSize = options.INDENT_SIZE
            val useTabs = options.USE_TAB_CHARACTER
            restoreIndentOptions = {
                options.TAB_SIZE = tabSize
                options.INDENT_SIZE = indentSize
                options.USE_TAB_CHARACTER = useTabs
            }
            options.TAB_SIZE = 4
            options.INDENT_SIZE = 4
            options.USE_TAB_CHARACTER = false
            editor.settings.setTabSize(4)
            editor.settings.setUseTabCharacter(false)
            editor.caretModel.moveToOffset(text.indexOf("work();"))
            handle = host.editSession(editor, reads)
        }
        val active = checkNotNull(handle)
        val expected = awaitIndentationSettled(active)
        check(expected.guideColumn == 8 && expected.anchorLine == 3) { "Wrong warmed guide: $expected" }
        trace.awaitClosed()
        emit(
            "kind" to "indentation-setup", "workload" to "indentation", "characters" to text.length,
            "expectedGuide" to expected, "bodyColumns" to listOf(12, 16, 12), "tabSize" to 4,
            "actions" to listOf(IdeActions.ACTION_EDITOR_TAB, IdeActions.ACTION_EDITOR_UNINDENT_SELECTION),
            "scope" to
                "one warmed actual MAIN_EDITOR; real action handlers and registry document listeners; headless ACTIVE, no screen paint",
            "comparisonRule" to "hidden/retained are observations, not side-specific acceptance conditions",
            "ownedWorkScope" to
                "both sides use identical CandidateComparisonHost manual registry composition and factory-owned repair/full jobs",
            "primaryAcceptance" to
                "per-JVM median action EDT ns/bytes; flag more than 20 percent regression, no automatic exemption",
        )
        repeat(warmups + repeats) { index ->
            reads.reset()
            val asyncBefore = trace.snapshot()
            val observations = mutableListOf<Map<String, Any?>>()
            withContext(Dispatchers.EDT) {
                check(editor.document.text == text && active.visibleGuide() == expected)
                editor.caretModel.moveToOffset(text.indexOf("work();"))
                val context = DataContext { key ->
                    when (key) {
                        CommonDataKeys.EDITOR.name -> editor
                        CommonDataKeys.PROJECT.name -> project
                        else -> null
                    }
                }
                WriteCommandAction.runWriteCommandAction(project) {
                    for ((action, expectedText) in listOf(
                        IdeActions.ACTION_EDITOR_TAB to tabbedText,
                        IdeActions.ACTION_EDITOR_UNINDENT_SELECTION to text,
                    )) {
                        val previousMark = active.markup().filterIsInstance<RangeHighlighter>().singleOrNull {
                            it.isValid && it.customRenderer?.javaClass?.simpleName == "BracketGuideDrawing"
                        }
                        val handler = EditorActionManager.getInstance().getActionHandler(action)
                        val allocatedBefore = indentationAllocated()
                        val started = System.nanoTime()
                        handler.execute(editor, editor.caretModel.currentCaret, context)
                        val ended = System.nanoTime()
                        val allocatedAfter = indentationAllocated()
                        // Commit and observations are outside action timing, still before releasing write access.
                        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                        check(editor.document.text == expectedText) {
                            "Action $action did not produce the declared indentation"
                        }
                        observations.add(
                            linkedMapOf(
                                "action" to action, "actionEdtNs" to ended - started,
                                "actionEdtAllocatedBytes" to indentationDelta(allocatedBefore, allocatedAfter),
                                "guidePresentAfterAction" to (active.visibleGuide() != null),
                                "guideAfterAction" to active.visibleGuide(),
                                "previousGuideMarkExisted" to (previousMark != null),
                                "previousGuideMarkValidAfterAction" to (previousMark?.isValid == true),
                                "bodyColumn" to
                                    editor.offsetToLogicalPosition(editor.document.text.indexOf("work();")).column,
                                "sourceStamp" to editor.document.modificationStamp,
                            ),
                        )
                    }
                    check(editor.document.text == text) { "Tab/Unindent did not restore the actual original source" }
                }
            }
            val settled = awaitIndentationSettled(active)
            trace.awaitClosed()
            check(settled == expected) { "Final exact guide changed: expected=$expected actual=$settled" }
            val asyncAfter = trace.snapshot()
            if (index >= warmups) {
                emit(
                    "kind" to "indentation", "workload" to "indentation",
                    "corpus" to "Indentation.java", "iteration" to index - warmups,
                    "actions" to observations, "settledGuide" to settled, "textRestored" to true,
                    "ownedWorkersQuiescent" to true, "asyncAllocationBefore" to asyncBefore,
                    "asyncAllocationAfter" to asyncAfter,
                    "asyncAllocatedBytes" to indentationDelta(
                        asyncBefore["coroutineAllocatedBytes"] as? Long,
                        asyncAfter["coroutineAllocatedBytes"] as? Long,
                    ),
                    "allocationSupported" to (allocation != null),
                    "allocationScopeRule" to
                        "action EDT and inherited coroutine allocations are separate non-additive scopes; no whole-session sum",
                    "readObservationCoverage" to
                        "both sides use the same factory-owned read observer; all competing repair/full jobs retained",
                    "requests" to reads.requests(), "reads" to reads.snapshot(), "nativePaintVerified" to false,
                )
            }
        }
    } finally {
        try {
            withContext(NonCancellable + Dispatchers.EDT) { handle?.close() }
            withContext(NonCancellable) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (handle?.workerActive() == true && System.nanoTime() < deadline) delay(1.milliseconds)
                check(handle?.workerActive() != true) { "Indentation session did not close" }
                trace.awaitClosed()
            }
            withContext(NonCancellable + Dispatchers.EDT) {
                check(
                    editor.markupModel.allHighlighters.none {
                        it.isValid && it.customRenderer?.javaClass?.simpleName == "BracketGuideDrawing"
                    },
                ) { "Guide survived actual session close" }
                host.assertNoUnownedAttachments()
                emit(
                    "kind" to "indentation-cleanup",
                    "ownedWorkersQuiescent" to true,
                    "allocationTraceClosed" to true,
                    "remainingGuideMarkup" to 0,
                )
            }
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.EDT) { restoreIndentOptions?.invoke() }
            } finally {
                release(editor)
            }
        }
    }
}

private suspend fun awaitIndentationSettled(handle: EditHandle): GuideShape {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (System.nanoTime() < deadline) {
        val guide = withContext(Dispatchers.EDT) { handle.visibleGuide() }
        if (guide != null && !handle.workerActive()) return guide
        delay(1.milliseconds)
    }
    error("Actual indentation guide/owned workers did not settle")
}

private fun ComparisonFixture.indentationAllocated(): Long? =
    allocation?.getThreadAllocatedBytes(Thread.currentThread().id)?.takeIf { it >= 0 }

private fun indentationDelta(before: Long?, after: Long?): Long? =
    if (before != null && after != null && after >= before) after - before else null
