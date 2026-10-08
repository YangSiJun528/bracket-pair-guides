package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.model.AnalysisLimit
import com.sijunyang.bracketpairguides.model.result.AnalysisResult
import com.sijunyang.bracketpairguides.model.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.model.result.BracketView
import com.sijunyang.bracketpairguides.ui.editor.events.StickyLineSourceRanges
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.editor.highlighting.UnsupportedBackendNotificationProvider
import com.sijunyang.bracketpairguides.ui.policy.EditorActivity
import com.sijunyang.bracketpairguides.ui.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.ui.policy.EditorPresentationPolicy
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.presentation.ActiveGuidePresentation
import com.sijunyang.bracketpairguides.ui.presentation.DocumentChange
import com.sijunyang.bracketpairguides.ui.presentation.VisibleTokenDecorations
import com.sijunyang.bracketpairguides.ui.work.AnalysisUpdate
import com.sijunyang.bracketpairguides.ui.work.DisplayedGuide
import com.sijunyang.bracketpairguides.ui.work.GuideChange
import com.sijunyang.bracketpairguides.ui.work.GuideDemand
import com.sijunyang.bracketpairguides.ui.work.GuideView
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory
import com.sijunyang.bracketpairguides.ui.work.NativeConflictEvidence
import com.sijunyang.bracketpairguides.ui.work.RepairIntent
import com.sijunyang.bracketpairguides.ui.work.RepairUpdate
import com.sijunyang.bracketpairguides.ui.work.ViewApplication
import java.util.concurrent.atomic.AtomicBoolean

/** Owns geometry and markup only. Runtime alone owns calculation acceptance and jobs. */
internal class EditorGuide(
    private val editor: Editor,
    private var options: BracketGuidePreferences,
    private var capabilities: EditorCapabilities,
    private var activity: EditorActivity,
    private val advisory: NativeGuideAdvisory,
    factory: GuideWorkFactory,
) : GuideView, AutoCloseable {
    private val closed = AtomicBoolean()
    private var revision = 0L
    private var guideRevision = 0L
    private var view: BracketView? = null
    private var repairAllowed = true
    private var exactRepair = false
    private var highlighter = editor.highlighter
    private var tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    private var plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
    private var display = plan.presentation.applyTo(options)
    private val active = ActiveGuidePresentation(editor) { _, guide ->
        if (!closed.get() && advisory.interest(options).enabled) work.observeNativeGuide(DisplayedGuide(guideRevision, guide))
    }
    private val tokens = VisibleTokenDecorations(editor)
    private val work = factory.attach(editor, this)
    private var checkpoint = demand(GuideChange.CONFIGURATION)

    @Volatile var matcherAvailability = BraceMatcherAvailability.UNDETERMINED
        private set
    val hasCappedTokenDecorations: Boolean get() = tokens.isCapped
    val drawsGuides: Boolean get() = display.enabled && display.showsGuide
    val isVisible: Boolean get() = activity.visible

    fun start() { reconcile(GuideChange.CONFIGURATION) }
    fun wakeUp() = work.refresh()
    fun requestAnalysis() = work.refresh()

    fun caretMoved() {
        assertEdt()
        if (closed.get()) return
        guideRevision++
        synchronizeLayout()
        renderActive()
        reconcile(GuideChange.PRESENTATION)
    }

    fun documentChanged(change: DocumentChange) {
        assertEdt()
        if (closed.get()) return
        guideRevision++
        view = null
        exactRepair = true
        try {
            synchronizeLayout()
            tokens.documentChanged()
            active.refreshAfterDocumentChange(change, caretOffset(), display)
        } finally {
            // Never let an SDK rendering failure retain publication authority for the old source.
            var next = checkpoint.copy(guideRevision = guideRevision, repair = null, change = GuideChange.CONTENT)
            try { next = demand(GuideChange.CONTENT) } finally { checkpoint = next; work.reconcile(next) }
        }
        repaint()
    }

    fun visibleAreaChanged() {
        assertEdt()
        if (closed.get()) return
        if (synchronizeLayout()) { reconcile(GuideChange.CONTENT); return }
        val current = view ?: return
        if (tokens.replaceIfOutsideWindow(current, editor.calculateVisibleRange(), stickyRanges(), display)) repaint()
    }

    fun updateOptions(next: BracketGuidePreferences, refreshColors: Boolean) {
        assertEdt()
        if (closed.get()) return
        val previous = options
        options = next
        plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
        display = plan.presentation.applyTo(options)
        guideRevision++
        if (previous.disabledLanguageIds != next.disabledLanguageIds) { view = null; active.clear(false); tokens.dispose() }
        revision++
        synchronizeLayout()
        renderCurrent()
        if (refreshColors) tokens.updateAttributes(display)
        reconcile(GuideChange.CONFIGURATION)
    }

    fun updateSurface(nextCapabilities: EditorCapabilities, nextActivity: EditorActivity) {
        assertEdt()
        if (closed.get() || capabilities == nextCapabilities && activity == nextActivity) return
        capabilities = nextCapabilities
        activity = nextActivity
        updateOptions(options, false)
    }

    override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
        assertEdt()
        if (closed.get() || !activity.visible || !plan.analysis.pairs || update.demandRevision != revision) return ViewApplication.OBSOLETE
        val expectedRevision = guideRevision
        try {
            when (val result = update.result) {
                is AnalysisResult.Available -> {
                    view = result.view
                    repairAllowed = result.limit != AnalysisLimit.GUIDE_CAPACITY
                    exactRepair = false
                    matcherAvailability = result.matcherAvailability
                    renderCurrent()
                }
                is AnalysisResult.Unavailable -> {
                    view = null
                    repairAllowed = false
                    matcherAvailability = result.matcherAvailability
                    clearMarkup()
                }
            }
            if (closed.get() || expectedRevision != guideRevision || update.demandRevision != revision) {
                clearMarkup()
                return ViewApplication.OBSOLETE
            }
            reconcile(GuideChange.PRESENTATION)
            UnsupportedBackendNotificationProvider.update(editor)
            repaint()
            return ViewApplication.APPLIED
        } catch (failure: Throwable) {
            view = null
            clearMarkup()
            throw failure
        }
    }

    override fun applyRepair(update: RepairUpdate): ViewApplication {
        assertEdt()
        if (closed.get() || update.guideRevision != guideRevision || !drawsGuides ||
            update.guide.pair != active.currentPair || update.guide.pair != active.adjustedPair) return ViewApplication.OBSOLETE
        if (!active.publishRepair(update.guide, display)) return ViewApplication.OBSOLETE
        repaint()
        return if (!closed.get() && update.guideRevision == guideRevision) ViewApplication.APPLIED else ViewApplication.OBSOLETE
    }

    override fun reportNativeConflict(evidence: NativeConflictEvidence) {
        assertEdt()
        if (!closed.get() && evidence.guideRevision == guideRevision && drawsGuides) advisory.accept(editor, evidence)
    }

    private fun renderCurrent() {
        if (!activity.visible || !plan.analysis.pairs) { view = null; clearMarkup(); return }
        val expected = guideRevision
        val current = view
        renderActive()
        if (closed.get() || expected != guideRevision || current !== view) return
        if (current != null && plan.analysis.tokens) tokens.replace(current, editor.calculateVisibleRange(), stickyRanges(), display)
        else tokens.dispose()
    }

    private fun renderActive() {
        if (!display.enabled || !display.showsActivePair && !display.showsGuide) { active.clear(false); return }
        val current = view
        if (current == null) active.refreshProvisional(caretOffset(), display)
        else {
            val expected = guideRevision
            val pair = current.activePairAt(caretOffset())
            val geometry = pair?.let(current::guideFor)
            if (closed.get() || expected != guideRevision || current !== view) return
            active.replace(pair, geometry, true, display)
        }
        repaint()
    }

    private fun synchronizeLayout(): Boolean {
        val changedSource = highlighter !== editor.highlighter
        if (changedSource) { highlighter = editor.highlighter; view = null; clearMarkup(); guideRevision++ }
        val currentTabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val changedLayout = tabSize != currentTabSize
        if (changedLayout) { tabSize = currentTabSize; view = null; active.hideGuide(); guideRevision++ }
        return changedSource || changedLayout
    }

    private fun demand(change: GuideChange): GuideDemand = GuideDemand(
        revision, plan.analysis, options.disabledLanguageIds, activity.visible, guideRevision,
        if (activity.visible && drawsGuides && repairAllowed && active.needsGuideRepair) active.currentPair?.let {
            RepairIntent(it, exactRepair, active.guideAnchorLine)
        } else null,
        change, advisory.interest(options),
    )

    private fun reconcile(change: GuideChange) {
        if (closed.get()) return
        checkpoint = demand(change)
        work.reconcile(checkpoint)
    }
    private fun stickyRanges(): List<TextRange> = if (capabilities.activePair) StickyLineSourceRanges.calculate(editor) else emptyList()
    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset
    private fun clearMarkup() { active.clear(false); tokens.dispose() }
    private fun repaint() {
        val area = editor.scrollingModel.visibleArea
        if (area.isEmpty) editor.contentComponent.repaint() else editor.contentComponent.repaint(area)
    }
    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        work.close()
        view = null
        if (!ApplicationManager.getApplication().isDisposed || ApplicationManager.getApplication().isDispatchThread) {
            assertEdt(); clearMarkup()
        }
    }
}
