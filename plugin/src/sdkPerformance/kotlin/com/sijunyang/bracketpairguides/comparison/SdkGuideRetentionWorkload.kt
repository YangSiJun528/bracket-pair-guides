package com.sijunyang.bracketpairguides.comparison

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.RangeHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** The same actual document edits on both revisions; presence is observed, never assumed. */
suspend fun ComparisonFixture.runGuideRetentionWorkload(paddingLines: Int = 0) {
    val originalText = "class Retention {\n    void run() {\n            work();\n        }\n}\n"
    val text = originalText.replace("        }", "            work();\n".repeat(paddingLines) + "        }")
    val body = text.indexOf("            work")
    val close = text.indexOf("        }")
    val cases = listOf(
        RetentionEdit("closing-spaces", 4, close, "        ", "          ", 10, 3 + paddingLines),
        RetentionEdit("closing-tab-2", 2, close, "        ", "\t", 2, 3),
        RetentionEdit("closing-tab-4", 4, close, "        ", "\t", 4, 3),
        RetentionEdit("closing-tab-8", 8, close, "        ", "\t\t", 12, 2),
        RetentionEdit("multiline-paste", 4, body, "            work();", "  pasted();\n            work();", 2, 2),
        RetentionEdit("body-replacement", 4, body, "            ", "  ", 2, 2),
    )
    for (case in if (paddingLines == 0) cases else cases.take(1)) {
        val editor = editor("Retention.java", text)
        val underlying = host.newReads()
        val trace = SdkAllocationSegments(allocation)
        val reads = object : ReadRecorder by underlying {
            override val context = underlying.context + trace
        }
        var handle: EditHandle? = null
        try {
            withContext(Dispatchers.EDT) {
                editor.settings.setTabSize(case.tabSize)
                editor.caretModel.moveToOffset(text.indexOf("work"))
                handle = host.editSession(editor, reads)
            }
            val active = checkNotNull(handle)
            val initial = retentionSettled(active, retentionShape(text, 8, 3 + paddingLines))
            trace.awaitClosed()
            emit(
                "kind" to "retention-setup",
                "case" to case,
                "initialGuide" to initial,
                "scope" to
                    "actual main-editor document replacement and production listeners; headless ACTIVE; no screen paint",
                "latencyDefinition" to "write command EDT duration; return-to-correct guide is a polling upper bound",
            )
            repeat(warmups + repeats) { iteration ->
                for (forward in listOf(true, false)) {
                    val replacement = if (forward) case.after else case.before
                    val removed = if (forward) case.before else case.after
                    val changed = text.replaceRange(case.offset, case.offset + case.before.length, case.after)
                    val targetText = if (forward) changed else text
                    val targetGuide = if (forward) retentionShape(changed, case.column, case.anchor) else initial
                    trace.awaitClosed()
                    val asyncBefore = trace.snapshot()["coroutineAllocatedBytes"] as? Long
                    reads.reset()
                    var elapsed = 0L
                    var bytes: Long? = null
                    var returned = 0L
                    var immediate: GuideShape? = null
                    var retained = false
                    withContext(Dispatchers.EDT) {
                        val mark = active.markup().filterIsInstance<RangeHighlighter>().single {
                            it.isValid && it.customRenderer?.javaClass?.simpleName == "BracketGuideDrawing"
                        }
                        val allocated = allocation?.getThreadAllocatedBytes(Thread.currentThread().id)
                        val started = System.nanoTime()
                        WriteCommandAction.runWriteCommandAction(editor.project) {
                            editor.document.replaceString(case.offset, case.offset + removed.length, replacement)
                        }
                        returned = System.nanoTime()
                        elapsed = returned - started
                        val after = allocation?.getThreadAllocatedBytes(Thread.currentThread().id)
                        bytes = if (allocated != null && after != null) after - allocated else null
                        immediate = active.visibleGuide()
                        retained = mark.isValid
                        check(editor.document.text == targetText)
                    }
                    val settled = retentionSettled(active, targetGuide)
                    val observed = System.nanoTime()
                    trace.awaitClosed()
                    val asyncAfter = trace.snapshot()["coroutineAllocatedBytes"] as? Long
                    if (iteration >= warmups) {
                        emit(
                            "kind" to "retention",
                            "corpus" to if (paddingLines ==
                                0
                            ) {
                                case.name
                            } else {
                                "${case.name}-$paddingLines-lines"
                            },
                            "iteration" to iteration - warmups,
                            "direction" to if (forward) "forward" else "restore", "tabSize" to case.tabSize,
                            "editEdtNs" to elapsed, "editEdtAllocatedBytes" to bytes,
                            "guidePresentAfterEdit" to (immediate != null), "previousMarkRetained" to retained,
                            "immediateGuide" to immediate, "expectedGuide" to targetGuide, "settledGuide" to settled,
                            "returnToObservedCorrectNs" to observed - returned,
                            "asyncAllocatedBytes" to
                                if (asyncBefore != null && asyncAfter != null) asyncAfter - asyncBefore else null,
                            "readRequests" to reads.requests().size, "readAttempts" to reads.snapshot().size,
                            "nativePaintVerified" to false,
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { handle?.close() }
            withContext(NonCancellable) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (handle?.workerActive() == true && System.nanoTime() < deadline) delay(1.milliseconds)
                check(handle?.workerActive() != true)
                trace.awaitClosed()
            }
            withContext(NonCancellable + Dispatchers.EDT) { host.assertNoUnownedAttachments() }
            release(editor)
        }
    }
}

private data class RetentionEdit(
    val name: String,
    val tabSize: Int,
    val offset: Int,
    val before: String,
    val after: String,
    val column: Int,
    val anchor: Int,
)

private fun retentionShape(text: String, column: Int, anchor: Int): GuideShape {
    val open = text.indexOf('{', text.indexOf("void run"))
    val close = text.indexOf('}', open)
    return GuideShape(PairShape(open, 1, 1, close, 1, text.substring(0, close).count { it == '\n' }, 1), column, anchor)
}

private suspend fun retentionSettled(handle: EditHandle, expected: GuideShape): GuideShape {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (System.nanoTime() < deadline) {
        val guide = withContext(Dispatchers.EDT) { handle.visibleGuide() }
        if (guide == expected && !handle.workerActive()) return guide
        delay(1.milliseconds)
    }
    error("Guide did not settle to $expected; actual=${withContext(Dispatchers.EDT) { handle.visibleGuide() }}")
}
