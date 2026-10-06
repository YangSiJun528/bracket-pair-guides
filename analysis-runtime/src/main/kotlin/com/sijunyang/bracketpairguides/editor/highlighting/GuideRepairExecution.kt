package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.guide.GuideRepairCalculation
import com.sijunyang.bracketpairguides.analysis.intellij.DocumentTextCapture
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.presentation.GuideRepairRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/** Immediate independent repair, separate from full-analysis scheduling and debounce. */
@Service(Service.Level.APP)
internal class GuideRepairExecution internal constructor(
    parentScope: CoroutineScope,
    private val calculate: suspend (Editor, GuideRepairRequest) -> BracketGuide?,
) : Disposable {
    constructor(scope: CoroutineScope) : this(scope, { editor, request -> calculateGuide(editor, request) })

    private val root = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + root + Dispatchers.Default)
    private val running = IdentityHashMap<Editor, Job>()
    private val lock = Any()

    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) = cancel(event.editor)
            },
            this,
        )
    }

    fun request(
        editor: Editor,
        request: GuideRepairRequest,
        isCurrent: () -> Boolean,
        publish: (BracketGuide) -> Unit,
    ): Job? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!EditorEffectGuard.allowsEffects() || disposed || !root.isActive || !request.matches(editor)) return null
        val modality = ModalityState.current().asContextElement()
        return synchronized(lock) {
            running.remove(editor)?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val guide = calculate(editor, request) ?: return@launch
                    withContext(Dispatchers.EDT + modality) {
                        ensureActive()
                        if (!disposed && request.matches(editor) && isCurrent()) publish(guide)
                    }
                } catch (_: ProcessCanceledException) {
                    // Cancellation refuses publication; the synchronously hidden guide stays hidden.
                }
            }
            running[editor] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (running[editor] === job) running.remove(editor)
                }
            }
            job.start()
            job
        }
    }

    fun cancel(editor: Editor) {
        synchronized(lock) { running.remove(editor)?.cancel() }
    }

    override fun dispose() {
        disposed = true
        root.cancel()
        synchronized(lock) { running.clear() }
    }

    companion object {
        internal suspend fun calculateGuide(
            editor: Editor,
            request: GuideRepairRequest,
            additionalCheckCanceled: () -> Unit = {},
        ): BracketGuide? {
            val application = ApplicationManager.getApplication()
            check(!application.isDispatchThread && !application.isReadAccessAllowed)
            val context = currentCoroutineContext()
            val textCapture = DocumentTextCapture(CONTINUED_PREFIX)
            val checkCanceled = {
                context.ensureActive()
                ProgressManager.checkCanceled()
                additionalCheckCanceled()
            }
            suspend fun <T> capture(action: () -> T): T = readAction {
                checkCanceled()
                if (!request.matches(editor)) throw StaleRepair()
                val captured = action()
                checkCanceled()
                if (!request.matches(editor)) throw StaleRepair()
                captured
            }
            try {
                val lines = capture {
                    val lastDocumentLine = editor.document.lineCount - 1
                    val first = (
                        if (request.pair.openLine <
                            request.pair.closeLine
                        ) {
                            request.pair.openLine + 1
                        } else {
                            request.pair.closeLine
                        }
                        )
                        .coerceIn(0, lastDocumentLine)
                    first..request.pair.closeLine.coerceIn(first, lastDocumentLine)
                }
                val calculation = GuideRepairCalculation(
                    request.pair,
                    lines,
                    request.stamp.tabSize,
                    request.exact,
                    request.currentAnchorLine,
                    checkCanceled,
                )
                while (true) {
                    val line = calculation.nextLine() ?: break
                    var prefix = capture {
                        val document = editor.document
                        val start = document.getLineStartOffset(line)
                        val end = document.getLineEndOffset(line)
                        val after = minOf(start.toLong() + INITIAL_PREFIX, end.toLong()).toInt()
                        Prefix(textCapture.copy(document, start, after), after, end)
                    }
                    while (!calculation.append(prefix.text, prefix.after == prefix.end)) {
                        val before = prefix.after
                        val after = minOf(before.toLong() + CONTINUED_PREFIX, prefix.end.toLong()).toInt()
                        prefix = capture {
                            Prefix(textCapture.copy(editor.document, before, after), after, prefix.end)
                        }
                    }
                }
                val guide = calculation.result()
                capture { }
                return guide
            } catch (_: StaleRepair) {
                return null
            }
        }

        class Prefix(val text: String, val after: Int, val end: Int)
        class StaleRepair : RuntimeException(null, null, false, false)
        const val INITIAL_PREFIX = 128
        const val CONTINUED_PREFIX = 4_096
    }
}
