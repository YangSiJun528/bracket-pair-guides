package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.model.BracketGuide
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
import com.sijunyang.bracketpairguides.ui.presentation.RenderFrames
import com.sijunyang.bracketpairguides.ui.presentation.RenderFrames.Frame
import com.sijunyang.bracketpairguides.ui.presentation.ObsoleteRendering

/** Owns geometry and markup only. Runtime alone owns calculation acceptance and jobs. */
internal class EditorGuide(
    private val editor: Editor,
    private var options: BracketGuidePreferences,
    private var capabilities: EditorCapabilities,
    private var activity: EditorActivity,
    private val advisory: NativeGuideAdvisory,
    factory: GuideWorkFactory,
) : GuideView, AutoCloseable {
    private val frames = RenderFrames()
    private var revision = 0L
    private var guideRevision = 0L
    private var view: BracketView? = null
    private var repairAllowed = true
    private var exactRepair = false
    private var highlighter = editor.highlighter
    private var tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    private var plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
    private var display = plan.presentation.applyTo(options)
    private val active = ActiveGuidePresentation(editor) { _, guide -> observeDisplayedGuide(guide) }
    private val tokens = VisibleTokenDecorations(editor)
    private val work = factory.attach(editor, this)
    private var checkpoint = demand(GuideChange.CONFIGURATION)

    @Volatile var matcherAvailability = BraceMatcherAvailability.UNDETERMINED
        private set
    val hasCappedTokenDecorations: Boolean get() = tokens.isCapped
    val drawsGuides: Boolean get() = display.enabled && display.showsGuide

    private fun observeDisplayedGuide(guide: BracketGuide) {
        if (!frames.isClosed && active.isDisplayed(guide) && advisory.interest(options).enabled)
            work.observeNativeGuide(DisplayedGuide(guideRevision, guide))
    }
    fun start() { reconcile(GuideChange.CONFIGURATION) }
    fun wakeUp() = work.refresh()
    fun requestAnalysis() = work.refresh()

    fun caretMoved() {
        assertEdt()
        if (frames.isClosed) return
        guideRevision++
        try { render { frame ->
            val changedLayout = synchronizeLayout(frame)
            renderActive(frame, allowUnchanged = !changedLayout)
        } }
        finally { reconcile(GuideChange.PRESENTATION) }
    }

    fun documentChanged(change: DocumentChange) {
        assertEdt()
        if (frames.isClosed) return
        guideRevision++
        view = null
        exactRepair = true
        try {
            render { frame ->
                synchronizeLayout(frame)
                tokens.documentChanged(frame)
                active.refreshAfterDocumentChange(change, caretOffset(), display, frame)
            }
        } finally {
            var next = checkpoint.copy(guideRevision = guideRevision, repair = null, change = GuideChange.CONTENT)
            try { next = demand(GuideChange.CONTENT) }
            finally { checkpoint = next; if (!frames.isClosed) work.reconcile(next) }
        }
        repaint()
    }

    fun visibleAreaChanged() {
        assertEdt()
        if (frames.isClosed) return
        render { frame ->
            if (synchronizeLayout(frame)) { reconcile(GuideChange.CONTENT); return@render }
            val current = view ?: return@render
            if (tokens.replaceIfOutsideWindow(current, editor.calculateVisibleRange(), stickyRanges(), display, frame)) repaint()
        }
    }

    fun updateOptions(next: BracketGuidePreferences, refreshColors: Boolean) {
        assertEdt()
        if (frames.isClosed) return
        val previous = options
        options = next
        plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
        display = plan.presentation.applyTo(options)
        guideRevision++
        revision++
        try {
            render { frame ->
                if (previous.disabledLanguageIds != next.disabledLanguageIds) { view = null; clearMarkup(frame) }
                synchronizeLayout(frame)
                renderCurrent(frame)
                if (refreshColors) tokens.updateAttributes(display, frame)
            }
        } finally { reconcile(GuideChange.CONFIGURATION) }
    }

    fun updateSurface(nextCapabilities: EditorCapabilities, nextActivity: EditorActivity) {
        assertEdt()
        if (frames.isClosed || capabilities == nextCapabilities && activity == nextActivity) return
        capabilities = nextCapabilities
        activity = nextActivity
        updateOptions(options, false)
    }

    override fun applyAnalysis(update: AnalysisUpdate): ViewApplication {
        assertEdt()
        if (frames.isClosed || !activity.visible || !plan.analysis.pairs || update.demandRevision != revision)
            return ViewApplication.OBSOLETE
        val frame = frames.begin()
        try {
            when (val result = update.result) {
                is AnalysisResult.Available -> {
                    view = result.view
                    repairAllowed = result.limit != AnalysisLimit.GUIDE_CAPACITY
                    exactRepair = false
                    matcherAvailability = result.matcherAvailability
                    renderCurrent(frame)
                }
                is AnalysisResult.Unavailable -> {
                    view = null
                    repairAllowed = false
                    matcherAvailability = result.matcherAvailability
                    clearMarkup(frame)
                }
            }
            frame.check()
            reconcile(GuideChange.PRESENTATION)
            frame.check()
            UnsupportedBackendNotificationProvider.update(editor)
            frame.check()
            repaint()
            frame.commit()
            return ViewApplication.APPLIED
        } catch (_: ObsoleteRendering) {
            frame.rollback()
            reconcile(GuideChange.PRESENTATION)
            return ViewApplication.OBSOLETE
        } catch (failure: Throwable) {
            failed(frame, failure)
            throw failure
        }
    }

    override fun applyRepair(update: RepairUpdate): ViewApplication {
        assertEdt()
        if (frames.isClosed || update.guideRevision != guideRevision || !drawsGuides ||
            update.guide.pair != active.currentPair || update.guide.pair != active.adjustedPair)
            return ViewApplication.OBSOLETE
        val frame = frames.begin()
        try {
            if (!active.publishRepair(update.guide, display, frame)) { frame.commit(); return ViewApplication.OBSOLETE }
            repaint()
            frame.commit()
            return ViewApplication.APPLIED
        } catch (_: ObsoleteRendering) {
            frame.rollback()
            reconcile(GuideChange.PRESENTATION)
            return ViewApplication.OBSOLETE
        } catch (failure: Throwable) {
            failed(frame, failure)
            throw failure
        }
    }

    override fun reportNativeConflict(evidence: NativeConflictEvidence) {
        assertEdt()
        if (!frames.isClosed && evidence.guideRevision == guideRevision && drawsGuides) advisory.accept(editor, evidence)
    }

    private inline fun render(action: (Frame) -> Unit) {
        val frame = frames.begin()
        try { frame.check(); action(frame); frame.commit() }
        catch (_: ObsoleteRendering) { frame.rollback() }
        catch (failure: Throwable) { failed(frame, failure); throw failure }
    }

    private fun failed(frame: Frame, failure: Throwable) {
        try { frame.rollback() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        if (frame.isCurrent) {
            view = null
            try { clearMarkup(frame) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
    }

    private fun renderCurrent(frame: Frame) {
        frame.check()
        if (!activity.visible || !plan.analysis.pairs) { view = null; clearMarkup(frame); return }
        renderActive(frame)
        frame.check()
        val current = view
        if (current != null && plan.analysis.tokens)
            tokens.replace(current, editor.calculateVisibleRange(), stickyRanges(), display, frame)
        else tokens.dispose(frame)
    }

    private fun renderActive(frame: Frame, allowUnchanged: Boolean = false) {
        frame.check()
        if (!display.enabled || !display.showsActivePair && !display.showsGuide) { active.clear(false, frame); return }
        val current = view
        if (current == null) active.refreshProvisional(caretOffset(), display, frame)
        else {
            val pair = current.activePairAt(caretOffset())
            frame.check()
            if (allowUnchanged && active.adoptUnchanged(pair, display, frame)) {
                // Native evidence is invalidated by this caret revision; retain the paint request
                // that lets the actual renderer observe the displayed guide again.
                repaint()
                return
            }
            val geometry = pair?.let(current::guideFor)
            frame.check()
            active.replace(pair, geometry, true, display, frame)
        }
        frame.check()
        repaint()
    }

    private fun synchronizeLayout(frame: Frame): Boolean {
        frame.check()
        val changedSource = highlighter !== editor.highlighter
        if (changedSource) { highlighter = editor.highlighter; view = null; guideRevision++; clearMarkup(frame) }
        val currentTabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val changedLayout = tabSize != currentTabSize
        if (changedLayout) { tabSize = currentTabSize; view = null; guideRevision++; active.hideGuide(frame) }
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
        if (frames.isClosed) return
        checkpoint = demand(change)
        work.reconcile(checkpoint)
    }
    private fun stickyRanges(): List<TextRange> = if (capabilities.activePair) StickyLineSourceRanges.calculate(editor) else emptyList()
    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset
    private fun clearMarkup(frame: Frame? = null) {
        active.clear(false, frame)
        frame?.check()
        tokens.dispose(frame)
    }
    private fun repaint() {
        val area = editor.scrollingModel.visibleArea
        if (area.isEmpty) editor.contentComponent.repaint() else editor.contentComponent.repaint(area)
    }
    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()

    override fun close() {
        if (!frames.close()) return
        work.close()
        view = null
        if (!ApplicationManager.getApplication().isDisposed || ApplicationManager.getApplication().isDispatchThread) {
            assertEdt(); clearMarkup()
        }
    }
}
