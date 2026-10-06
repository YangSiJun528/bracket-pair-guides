package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorActivitySource
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.EditorSurfaceClassifier
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.events.StickyLineSourceRanges
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.editor.policy.EditorPresentationPolicy
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds

/** One request owns capture, calculation, and publication independently of a daemon read action. */
@Service(Service.Level.APP)
internal class EditorAnalysisExecution internal constructor(
    parentScope: CoroutineScope,
    private val analyze: suspend (AnalysisInput) -> AnalysisOutcome?,
    private val capabilities: (Editor) -> EditorCapabilities = EditorSurfaceClassifier::capabilities,
    private val activity: (Editor) -> EditorActivity = EditorActivitySource::capture,
    private val visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
    private val stickySourceRanges: (Editor) -> List<TextRange> = StickyLineSourceRanges::calculate,
    private val fileType: (Editor) -> FileType = EditorSurfaceClassifier::fileType,
    private val sourceFile: (Editor) -> VirtualFile? = EditorSurfaceClassifier::sourceFile,
    private val repairExecution: () -> GuideRepairExecution = { service<GuideRepairExecution>() },
) : Disposable {
    // IntelliJ application-service injection supplies the lifetime CoroutineScope.
    @Suppress("unused")
    constructor(scope: CoroutineScope) : this(
        scope,
        { input -> service<BracketAnalysis>().analyzeInBackground(input) },
    )

    private val root = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + root + Dispatchers.Default)
    private val lock = Any()
    private val running = IdentityHashMap<Editor, Request>()

    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) = cancel(event.editor)
            },
            this,
        )
    }

    /** Called from an editor UI callback or the daemon's existing read action. */
    fun request(editor: Editor) {
        // Intention-preview state belongs to this originating thread.
        if (!EditorEffectGuard.allowsEffects() || expired(editor)) return
        ApplicationManager.getApplication().assertReadAccessAllowed()
        if (capabilities(editor) == EditorCapabilities.NONE) return
        val input = currentInput(editor)
        synchronized(lock) {
            if (expired(editor) || !root.isActive) return
            val existing = running[editor]
            if (existing != null && existing.job.isActive && existing.input.stamp.covers(input.stamp) &&
                (!existing.input.coverage.guidePosition || existing.input.stamp.tabSize == input.stamp.tabSize)
            ) {
                return
            }
            // Supersession happens now, before any secondary-editor debounce.
            existing?.job?.cancel()
            val request = Request(input)
            request.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    execute(editor, input)
                } catch (_: ProcessCanceledException) {
                    // A canceled platform read action has no authoritative result to publish.
                }
            }
            running[editor] = request
            request.job.invokeOnCompletion {
                synchronized(lock) {
                    if (running[editor] === request) running.remove(editor)
                }
            }
            request.job.start()
        }
    }

    fun cancel(editor: Editor) {
        synchronized(lock) { running.remove(editor)?.job?.cancel() }
    }

    private suspend fun execute(editor: Editor, input: AnalysisInput) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) delay(REFRESH_DELAY)
        val modality = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            if (expired(editor) ||
                editor.editorKind != EditorKind.MAIN_EDITOR &&
                !(EditorGuideSessions.get(editor)?.isVisible ?: activity(editor).visible)
            ) {
                null
            } else {
                ModalityState.stateForComponent(editor.contentComponent)
            }
        } ?: return
        val decision = readAction {
            when {
                expired(editor) -> Admission.STALE
                sourceIsTooLarge(editor) -> Admission.SIZE_REFUSAL
                EditorGuideSessions.canSkipAnalysis(editor, input.stamp) -> Admission.ACCEPTED
                else -> Admission.CALCULATE
            }
        }
        val result = when (decision) {
            Admission.STALE -> return
            Admission.SIZE_REFUSAL -> AnalysisOutcome.Unavailable(input.stamp, AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE)
            Admission.ACCEPTED -> null
            Admission.CALCULATE -> analyze(input) ?: return
        }
        withContext(Dispatchers.EDT + modality.asContextElement()) {
            ensureActive()
            publish(editor, input.stamp, result)
        }
    }

    private fun publish(editor: Editor, passStamp: AnalysisStamp, result: AnalysisOutcome?) {
        if (!EditorEffectGuard.allowsEffects() || expired(editor)) return
        if (sourceIsTooLarge(editor)) {
            val currentStamp = currentInput(editor, EditorSurfaceClassifier.fileType(editor)).stamp
            val session = installSession(editor) ?: return
            session.updateDependenciesIfCurrent(visibleRange, supportedStickySourceRanges(editor), currentStamp)
            session.accept(AnalysisOutcome.Unavailable(currentStamp, AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE))
            return
        }
        val collectedSizeRefusal = result is AnalysisOutcome.Unavailable &&
            result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE
        val effectiveResult = result.takeUnless { collectedSizeRefusal }
        val requiresExactCoverage = collectedSizeRefusal || effectiveResult is AnalysisOutcome.Limited ||
            effectiveResult is AnalysisOutcome.Unavailable
        if (!isCurrent(editor, passStamp, requiresExactCoverage)) return
        val session = installSession(editor) ?: return
        session.updateDependenciesIfCurrent(visibleRange, supportedStickySourceRanges(editor), passStamp)
        effectiveResult?.let(session::accept)
    }

    private fun currentInput(editor: Editor, currentFileType: FileType = fileType(editor)): AnalysisInput {
        val options = BracketGuideSettings.getInstance().options
        return AnalysisInput(editor, currentFileType, coverage(editor), options.disabledLanguageIds)
    }

    private fun coverage(editor: Editor) = EditorPresentationPolicy.resolve(
        capabilities(editor),
        BracketGuideSettings.getInstance().options,
        EditorActivity.INACTIVE,
    ).analysis

    private fun isCurrent(editor: Editor, stamp: AnalysisStamp, exact: Boolean): Boolean {
        val options = BracketGuideSettings.getInstance().options
        val required = coverage(editor)
        return (!exact || stamp.coverage == required) && stamp.matchesCurrent(
            editor,
            EditorSurfaceClassifier.fileType(editor),
            required,
            options.disabledLanguageIds,
        )
    }

    private fun sourceIsTooLarge(editor: Editor): Boolean = sourceFile(editor)?.let { file ->
        if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) {
            SingleRootFileViewProvider.isTooLargeForIntelligence(file, editor.document.textLength.toLong())
        } else {
            SingleRootFileViewProvider.isTooLargeForIntelligence(file)
        }
    } == true

    private fun supportedStickySourceRanges(editor: Editor): (Editor) -> List<TextRange> =
        if (capabilities(editor).activePair) stickySourceRanges else { _ -> emptyList() }

    private fun installSession(editor: Editor): EditorGuideSession? {
        if (expired(editor) || capabilities(editor) == EditorCapabilities.NONE) return null
        val editorCapabilities = capabilities(editor)
        EditorGuideEvents.ensureInitialized(editor, observeStickyLines = editorCapabilities.activePair)
        return EditorGuideSessions.install(
            editor = editor,
            visibleRange = visibleRange,
            stickySourceRanges = supportedStickySourceRanges(editor),
            preferences = BracketGuideSettings.getInstance().options,
            activity = activity(editor),
            capabilities = editorCapabilities,
            matcherAvailabilityChanged = UnsupportedBackendNotificationProvider::update,
            nativeGuideConflictCandidate = { candidateEditor, guide ->
                NativeGuideConflictNotification.getInstance().consider(candidateEditor, guide)
            },
            requestRepair = { request, isCurrent, publish ->
                if (EditorEffectGuard.allowsEffects()) {
                    repairExecution().request(editor, request, isCurrent, publish)
                } else {
                    null
                }
            },
        )
    }

    private fun expired(editor: Editor): Boolean = disposed || editor.isDisposed ||
        editor.project?.isDisposed == true || ApplicationManager.getApplication().isDisposed

    override fun dispose() {
        disposed = true
        scope.cancel()
        synchronized(lock) { running.clear() }
    }

    private class Request(val input: AnalysisInput) {
        lateinit var job: Job
    }

    private enum class Admission { STALE, SIZE_REFUSAL, ACCEPTED, CALCULATE }

    companion object {
        private val REFRESH_DELAY = 75.milliseconds

        fun request(editor: Editor) {
            if (EditorEffectGuard.allowsEffects() &&
                !editor.isDisposed
            ) {
                service<EditorAnalysisExecution>().request(editor)
            }
        }
    }
}
