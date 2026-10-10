package com.sijunyang.bracketpairguides.runtime.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.SingleRootFileViewProvider
import com.sijunyang.bracketpairguides.core.input.CalculationControl
import com.sijunyang.bracketpairguides.core.input.RepairRequest
import com.sijunyang.bracketpairguides.model.AnalysisCoverage
import com.sijunyang.bracketpairguides.model.AnalysisLimit
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.runtime.capture.AnalysisReadEpoch
import com.sijunyang.bracketpairguides.runtime.capture.EditorSource
import com.sijunyang.bracketpairguides.runtime.capture.SourceChanged
import com.sijunyang.bracketpairguides.runtime.capture.SourceIdentity
import com.sijunyang.bracketpairguides.runtime.nativeproof.NativeGuideConflictDetector
import com.sijunyang.bracketpairguides.runtime.nativeproof.NativeEvidenceGate
import com.sijunyang.bracketpairguides.ui.work.AnalysisUpdate
import com.sijunyang.bracketpairguides.ui.work.DisplayedGuide
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWork
import com.sijunyang.bracketpairguides.ui.work.NativeConflictEvidence
import com.sijunyang.bracketpairguides.ui.work.RepairIntent
import com.sijunyang.bracketpairguides.ui.work.RepairUpdate
import com.sijunyang.bracketpairguides.ui.work.ViewApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/** One editor owns one accepted value and three independently revocable work lanes. */
internal class EditorAnalysisSession(
    editor: Editor,
    view: GuideView,
    parent: CoroutineScope,
    private val reads: AnalysisReadEpoch,
    calculation: DocumentCalculation,
) : GuideWork {
    private val root = SupervisorJob(parent.coroutineContext[Job])
    @Volatile private var attachment: Attachment? = Attachment(editor, view, calculation,
        CoroutineScope(parent.coroutineContext + root + Dispatchers.Default))
    private val closed = AtomicBoolean()
    private val lifecycle = Any()
    private val sourceEpoch = AtomicLong()
    private val fullTicket = AtomicLong()
    private val repairTicket = AtomicLong()
    private val native = NativeEvidenceGate()
    @Volatile private var demand: GuideDemand? = null
    @Volatile private var accepted: Accepted? = null
    @Volatile private var dormant: SoftReference<Accepted>? = null
    @Volatile private var fullJob: Job? = null
    @Volatile private var repairJob: Job? = null
    @Volatile private var nativeJob: Job? = null
    private var repairTarget: Pair<Long, RepairIntent?>? = null
    private var fullSource: SourceIdentity? = null
    private var fullCoverage: AnalysisCoverage? = null
    private var fullEnvironmentRevision: Long? = null

    init {
        try {
            calculation.acquire()
        } catch (failure: Throwable) {
            root.cancel()
            throw failure
        }
        root.invokeOnCompletion { close() }
    }

    override fun reconcile(demand: GuideDemand) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (expired()) return
        val previous = this.demand
        if (previous == demand) return
        val sourceChanged = previous == null ||
            demand.change == GuideChange.CONTENT &&
                (demand.guideRevision != previous.guideRevision || demand.revision != previous.revision) ||
            previous.disabledLanguageIds != demand.disabledLanguageIds
        synchronized(lifecycle) {
            if (closed.get() || attachment == null) return
            this.demand = demand
        }
        if (!demand.visible || !demand.coverage.pairs) {
            revokeWork(retainDormant = !demand.visible && demand.coverage.pairs && !sourceChanged)
            return
        }
        if (sourceChanged) revokeWork()
        if (previous?.guideRevision != demand.guideRevision || previous.nativeInterest != demand.nativeInterest) {
            native.invalidate(); nativeJob?.cancel()
        }
        if (previous?.visible == false) {
            if (resumeDormant(demand) || expired() || this.demand != demand) return
        }
        reconcileRepair(demand)
        if (sourceChanged || previous?.visible != true || previous.coverage != demand.coverage) scheduleFull()
    }

    private fun revokeWork(retainDormant: Boolean = false) {
        sourceEpoch.incrementAndGet()
        fullTicket.incrementAndGet(); repairTicket.incrementAndGet(); native.invalidate()
        fullJob?.cancel(); repairJob?.cancel(); nativeJob?.cancel()
        fullJob = null; repairJob = null; nativeJob = null
        synchronized(lifecycle) {
            if (!closed.get()) {
                if (retainDormant) accepted?.let { dormant = SoftReference(it) } else dormant = null
                accepted = null
            }
        }
        fullSource = null; fullCoverage = null; fullEnvironmentRevision = null; repairTarget = null
    }

    private fun resumeDormant(requested: GuideDemand): Boolean {
        val cached = dormant?.get() ?: run { dormant = null; return false }
        dormant = null
        val attached = attachment ?: return false
        val epoch = sourceEpoch.get()
        val ticket = fullTicket.get()
        val source = SourceIdentity.capture(attached.editor, requested)
        if (sourceIsTooLarge() || cached.environmentRevision != reads.environmentRevision ||
            !cached.covers(source, requested.coverage, attached.editor)) return false
        if (expired() || !root.isActive || attachment !== attached || demand !== requested ||
            sourceEpoch.get() != epoch || fullTicket.get() != ticket ||
            cached.environmentRevision != reads.environmentRevision) return false
        val applied = try {
            attached.view.applyAnalysis(AnalysisUpdate(requested.revision, cached.result))
        } catch (failure: Exception) {
            LOG.warn("Could not restore bracket guides", failure)
            return false
        }
        val sourceCurrent = valid(cached.source, requested, epoch) && !sourceIsTooLarge() &&
            cached.environmentRevision == reads.environmentRevision
        val latest = demand
        synchronized(lifecycle) {
            if (applied == ViewApplication.APPLIED && sourceCurrent && !closed.get() && root.isActive &&
                attachment === attached && fullTicket.get() == ticket && demand === latest) {
                accepted = cached
                return true
            }
        }
        return false
    }

    override fun refresh() {
        if (expired() || IntentionPreviewUtils.isIntentionPreviewActive()) return
        ApplicationManager.getApplication().invokeLater({ if (!expired()) scheduleFull() }, ModalityState.any())
    }

    private fun scheduleFull() {
        val requested = demand ?: return
        val attached = attachment ?: return
        val editor = attached.editor
        val view = attached.view
        val scope = attached.scope
        val calculation = attached.calculation
        if (expired() || !requested.visible || !requested.coverage.pairs) return
        val source = SourceIdentity.capture(editor, requested)
        val environmentRevision = reads.environmentRevision
        if (!sourceIsTooLarge() && accepted?.let {
                it.environmentRevision == environmentRevision && it.covers(source, requested.coverage, editor)
            } == true) return
        if (fullJob?.isActive == true && fullEnvironmentRevision == environmentRevision && fullSource?.matches(editor, requested.coverage.guidePosition) == true &&
            fullCoverage?.includes(requested.coverage) == true) return
        fullJob?.cancel()
        synchronized(lifecycle) {
            if (closed.get() || attachment !== attached) return
            fullSource = source
            fullCoverage = requested.coverage
            fullEnvironmentRevision = environmentRevision
        }
        val ticket = fullTicket.incrementAndGet()
        val epoch = sourceEpoch.get()
        val modality = ModalityState.stateForComponent(editor.contentComponent).asContextElement()
        fullJob = scope.launch {
            try {
                if (editor.editorKind != EditorKind.MAIN_EDITOR) delay(75.milliseconds)
                val control = control(fullTicket, ticket, epoch)
                val refused = readAction { sourceIsTooLarge() }
                val result = if (refused) AnalysisResult.Unavailable(requested.coverage, AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE)
                else calculation.calculator.analyze(EditorSource(editor, source, reads, control, calculation), requested.coverage, control)
                withContext(Dispatchers.EDT + modality) {
                    control.checkCanceled()
                    val current = demand ?: return@withContext
                    if (!valid(source, requested, epoch) || reads.environmentRevision != environmentRevision || sourceIsTooLarge() &&
                        !(result is AnalysisResult.Unavailable && result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE)) return@withContext
                    repairTicket.incrementAndGet(); repairJob?.cancel(); repairTarget = null
                    val applied = view.applyAnalysis(AnalysisUpdate(current.revision, result))
                    // Source/SDK checks happen outside the lifecycle lock, after all rendering effects.
                    val sourceCurrent = valid(source, requested, epoch) && reads.environmentRevision == environmentRevision &&
                        (!sourceIsTooLarge() || result is AnalysisResult.Unavailable && result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE)
                    val latest = demand
                    synchronized(lifecycle) {
                        // EDT cannot interleave another supported document write in this commit turn.
                        if (applied == ViewApplication.APPLIED && sourceCurrent && !closed.get() && root.isActive &&
                            fullTicket.get() == ticket && sourceEpoch.get() == epoch && demand === latest)
                            accepted = Accepted(source, requested.coverage, result, environmentRevision)
                    }
                }
            } catch (_: SourceChanged) { /* newer editor state owns the next request */ }
            catch (_: ProcessCanceledException) { }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { LOG.warn("Could not calculate or display bracket guides", failure) }
        }
    }

    private fun reconcileRepair(requested: GuideDemand) {
        val target = requested.guideRevision to requested.repair
        if (repairTarget == target) return
        repairTarget = target
        val ticket = repairTicket.incrementAndGet()
        repairJob?.cancel()
        val intent = requested.repair ?: return
        val attached = attachment ?: return
        val editor = attached.editor
        val view = attached.view
        val scope = attached.scope
        val calculation = attached.calculation
        if (!requested.visible) return
        val source = SourceIdentity.capture(editor, requested)
        val epoch = sourceEpoch.get()
        val modality = ModalityState.current().asContextElement()
        repairJob = scope.launch {
            try {
                val control = control(repairTicket, ticket, epoch)
                val guide = calculation.calculator.repair(EditorSource(editor, source, reads, control, calculation),
                    RepairRequest(intent.pair, intent.exact, intent.previousAnchor), control) ?: return@launch
                withContext(Dispatchers.EDT + modality) {
                    control.checkCanceled()
                    if (valid(source, requested, epoch) && demand?.guideRevision == requested.guideRevision)
                        view.applyRepair(RepairUpdate(requested.guideRevision, guide))
                }
            } catch (_: SourceChanged) { }
            catch (_: ProcessCanceledException) { }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { LOG.warn("Could not repair a bracket guide", failure) }
        }
    }

    override fun observeNativeGuide(candidate: DisplayedGuide) {
        val attached = attachment ?: return
        val editor = attached.editor
        val view = attached.view
        val scope = attached.scope
        val requested = demand ?: return
        if (expired() || !requested.visible || !requested.nativeInterest.enabled || candidate.revision != requested.guideRevision) return
        val caret = editor.caretModel.primaryCaret.offset
        val epoch = sourceEpoch.get()
        val proof = native.admit(candidate, caret, epoch, requested) ?: return
        nativeJob?.cancel()
        ApplicationManager.getApplication().invokeLater({
            if (expired() || !native.isCurrent(proof)) return@invokeLater
            val source = SourceIdentity.capture(editor, requested)
            val probe = NativeGuideConflictDetector.captureConflict(editor, candidate.guide, reads) ?: return@invokeLater
            nativeJob = scope.launch {
                try {
                    val control = nativeControl(proof, epoch)
                    val conflict = probe.inspect(control::checkCanceled)
                    withContext(Dispatchers.EDT) {
                        control.checkCanceled()
                        val current = demand
                        if (conflict && valid(source, requested, epoch) && current != null && native.isCurrent(proof, current, editor.caretModel.primaryCaret.offset, sourceEpoch.get()) &&
                            probe.isCurrent())
                            view.reportNativeConflict(NativeConflictEvidence(candidate.revision, requested.nativeInterest.episode, candidate.guide))
                    }
                } catch (_: SourceChanged) { }
                catch (_: ProcessCanceledException) { }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { LOG.warn("Could not inspect native guide emphasis", failure) }
            }
        }, ModalityState.any())
    }

    private suspend fun nativeControl(proof: NativeEvidenceGate.Proof, epoch: Long): CalculationControl {
        val context = currentCoroutineContext()
        return object : CalculationControl {
            override fun checkCanceled() {
                context.ensureActive(); ProgressManager.checkCanceled()
                if (expired() || !native.isCurrent(proof) || sourceEpoch.get() != epoch)
                    throw CancellationException("Obsolete native evidence")
            }
            override suspend fun yieldWork() = yield()
        }
    }

    private suspend fun control(lane: AtomicLong, ticket: Long, epoch: Long): CalculationControl {
        val context = currentCoroutineContext()
        return object : CalculationControl {
            override fun checkCanceled() {
                context.ensureActive(); ProgressManager.checkCanceled()
                if (expired() || lane.get() != ticket || sourceEpoch.get() != epoch) throw CancellationException("Obsolete guide work")
            }
            override suspend fun yieldWork() = yield()
        }
    }

    private fun valid(source: SourceIdentity, requested: GuideDemand, epoch: Long): Boolean {
        val current = demand ?: return false
        val editor = attachment?.editor ?: return false
        return current.visible && current.coverage.pairs && !expired() && sourceEpoch.get() == epoch && source.matches(editor, requested.coverage.guidePosition) &&
            requested.coverage.includes(current.coverage) && current.disabledLanguageIds == requested.disabledLanguageIds
    }

    private fun sourceIsTooLarge(): Boolean {
        val editor = attachment?.editor ?: return true
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: (editor as? EditorEx)?.virtualFile ?: return false
        return if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))
            SingleRootFileViewProvider.isTooLargeForIntelligence(file, editor.document.textLength.toLong())
        else SingleRootFileViewProvider.isTooLargeForIntelligence(file)
    }

    private fun expired(): Boolean {
        val editor = attachment?.editor ?: return true
        return closed.get() || !root.isActive || editor.isDisposed || editor.project?.isDisposed == true ||
            ApplicationManager.getApplication().isDisposed
    }

    override fun close() {
        val released: Attachment?
        synchronized(lifecycle) {
            if (!closed.compareAndSet(false, true)) return
            sourceEpoch.incrementAndGet(); fullTicket.incrementAndGet(); repairTicket.incrementAndGet(); native.invalidate()
            released = attachment
            attachment = null
            fullSource = null; fullCoverage = null; fullEnvironmentRevision = null; accepted = null; dormant = null; demand = null; repairTarget = null
        }
        root.cancel()
        released?.calculation?.release()
        fullJob = null; repairJob = null; nativeJob = null
    }

    private class Attachment(val editor: Editor, val view: GuideView, val calculation: DocumentCalculation, val scope: CoroutineScope)

    private class Accepted(val source: SourceIdentity, val requested: AnalysisCoverage, val result: AnalysisResult,
        val environmentRevision: Long) {
        fun covers(next: SourceIdentity, coverage: AnalysisCoverage, editor: Editor): Boolean =
            source.matches(editor, coverage.guidePosition) && source.disabledLanguageIds == next.disabledLanguageIds &&
                requested.includes(coverage) && !(result is AnalysisResult.Unavailable && result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE)
    }

    private companion object { val LOG = Logger.getInstance(EditorAnalysisSession::class.java) }
}
