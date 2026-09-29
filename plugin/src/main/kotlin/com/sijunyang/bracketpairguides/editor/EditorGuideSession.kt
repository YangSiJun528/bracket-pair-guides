package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import com.sijunyang.bracketpairguides.editor.policy.EditorCapabilities
import com.sijunyang.bracketpairguides.editor.policy.EditorPresentationPolicy
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.presentation.ActiveGuidePresentation
import com.sijunyang.bracketpairguides.presentation.DocumentChange
import com.sijunyang.bracketpairguides.presentation.VisibleTokenDecorations

/** EDT-owned state and presentation for one editor. */
internal class EditorGuideSession(
    private val editor: Editor,
    private var visibleRange: (Editor) -> TextRange,
    private var stickySourceRanges: (Editor) -> List<TextRange> = { emptyList() },
    private var options: BracketGuidePreferences,
    private var capabilities: EditorCapabilities,
    @Volatile private var activity: EditorActivity,
    private var matcherAvailabilityChanged: (Editor) -> Unit = {},
    private var nativeGuideConflictCandidate: (Editor, BracketGuide) -> Unit = { _, _ -> },
) {
    private var plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
    private var displayOptions = plan.presentation.applyTo(options)
    private var analysisRefreshRequested: () -> Unit = {}
    private var disposed = false
    private var analysisHighlighter = editor.highlighter
    private val analysisState = EditorAnalysisState(editor)

    private val activePresentation =
        ActiveGuidePresentation(editor) { candidateEditor, guide ->
            nativeGuideConflictCandidate(candidateEditor, guide)
        }
    private val tokenDecorations = VisibleTokenDecorations(editor)

    @Volatile
    var matcherAvailability: BraceMatcherAvailability =
        BraceMatcherAvailability.UNDETERMINED
        private set

    val hasCappedTokenDecorations: Boolean
        get() = tokenDecorations.isCapped

    fun updateMatcherAvailabilityListener(listener: (Editor) -> Unit) {
        assertEdt()
        matcherAvailabilityChanged = listener
    }

    fun updateNativeGuideConflictListener(listener: (Editor, BracketGuide) -> Unit) {
        assertEdt()
        nativeGuideConflictCandidate = listener
    }

    fun updateDependenciesIfCurrent(
        visibleRange: (Editor) -> TextRange,
        stickySourceRanges: (Editor) -> List<TextRange>,
        passStamp: AnalysisStamp,
    ): Boolean {
        assertEdt()
        val requiredCoverage = plan.analysis
        if (disposed || editor.isDisposed ||
            !passStamp.matchesCurrent(
                editor,
                EditorSurfaceClassifier.fileType(editor),
                requiredCoverage,
                options.disabledLanguageIds,
            )
        ) {
            return false
        }
        analysisHighlighter = editor.highlighter
        this.visibleRange = visibleRange
        this.stickySourceRanges = stickySourceRanges
        return true
    }

    /** The only publication boundary for an authoritative analysis attempt. */
    fun accept(outcome: AnalysisOutcome) {
        assertEdt()
        when (outcome) {
            is AnalysisOutcome.Complete -> acceptComplete(outcome.snapshot)
            is AnalysisOutcome.Limited -> acceptLimited(outcome)
            is AnalysisOutcome.Unavailable -> acceptUnavailable(outcome)
        }
    }

    private fun acceptComplete(nextAnalysis: BracketSnapshot) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val requiredCoverage = plan.analysis
        val currentFileType = EditorSurfaceClassifier.fileType(editor)
        if (!nextAnalysis.stamp.matchesCurrent(
                editor,
                currentFileType,
                requiredCoverage,
                options.disabledLanguageIds,
            )
        ) {
            return
        }
        publishMatcherAvailability(nextAnalysis.matcherAvailability)
        if (!requiredCoverage.pairs) {
            clearPresentation()
            analysisState.publishComplete(currentStamp())
            return
        }
        if (shouldReleasePairGraph(requiredCoverage, nextAnalysis.stamp.coverage)) {
            val compactAnalysis =
                analysisState.snapshot?.takeIf { current ->
                    current.stamp.matchesCurrent(
                        editor,
                        currentFileType,
                        requiredCoverage,
                        options.disabledLanguageIds,
                    ) &&
                        !shouldReleasePairGraph(
                            requiredCoverage,
                            current.stamp.coverage,
                        )
                }
            if (compactAnalysis != null) {
                tokenDecorations.replace(
                    compactAnalysis,
                    visibleRange(editor),
                    stickySourceRanges(editor),
                    displayOptions,
                )
                repaintVisibleContent()
                return
            }
        }

        val pair = activePair(nextAnalysis)
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let(nextAnalysis::guideFor),
            allowGuideFallback = false,
            preferences = displayOptions,
        )
        tokenDecorations.replace(
            nextAnalysis,
            visibleRange(editor),
            stickySourceRanges(editor),
            displayOptions,
        )
        if (shouldReleasePairGraph(
                requiredCoverage,
                nextAnalysis.stamp.coverage,
            )
        ) {
            analysisState.publishPending(nextAnalysis)
        } else {
            analysisState.publishComplete(nextAnalysis)
        }
        repaintVisibleContent()
    }

    /** Publishes exact lower facets after the requested guide index crosses its cap. */
    private fun acceptLimited(outcome: AnalysisOutcome.Limited) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val nextAnalysis = outcome.snapshot
        val attemptedStamp = outcome.stamp
        val requiredCoverage = plan.analysis
        val completedCoverage = requiredCoverage.copy(guidePosition = false)
        val currentFileType = EditorSurfaceClassifier.fileType(editor)
        if (attemptedStamp.coverage != requiredCoverage ||
            !attemptedStamp.matchesCurrent(
                editor,
                currentFileType,
                requiredCoverage,
                options.disabledLanguageIds,
            ) ||
            nextAnalysis.stamp.coverage != completedCoverage ||
            !nextAnalysis.stamp.matchesCurrent(
                editor,
                currentFileType,
                nextAnalysis.stamp.coverage,
                options.disabledLanguageIds,
            )
        ) {
            return
        }
        if (analysisState.hasCompleted(attemptedStamp)) return

        publishMatcherAvailability(nextAnalysis.matcherAvailability)

        val pair = activePair(nextAnalysis)
        activePresentation.replace(
            pair = pair,
            indexedGuide = null,
            allowGuideFallback = false,
            preferences = displayOptions,
        )
        tokenDecorations.replace(
            nextAnalysis,
            visibleRange(editor),
            stickySourceRanges(editor),
            displayOptions,
        )
        analysisState.publishLimited(
            snapshot = nextAnalysis,
            attemptedStamp = attemptedStamp,
        )
        repaintVisibleContent()
    }

    /** Accepts a bounded analysis refusal without publishing a partial snapshot. */
    private fun acceptUnavailable(outcome: AnalysisOutcome.Unavailable) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val stamp = outcome.stamp
        val limit = outcome.limit
        val requiredCoverage = plan.analysis
        if (stamp.coverage != requiredCoverage ||
            !stamp.matchesCurrent(
                editor,
                EditorSurfaceClassifier.fileType(editor),
                requiredCoverage,
                options.disabledLanguageIds,
            )
        ) {
            return
        }
        if (limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE) {
            if (analysisState.hasRefused(stamp, limit)) return
        } else if (analysisState.hasCompleted(stamp)) {
            return
        }
        analysisState.publishUnavailable(stamp, limit)
        clearPresentation()
        repaintVisibleContent()
    }

    fun caretMoved() {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (!showsActivePresentation()) return
        val currentAnalysis = analysisState.snapshot
        if (currentAnalysis == null || !hasCurrentActivePair(currentAnalysis)) {
            updateProvisional()
            return
        }

        val pair = activePair(currentAnalysis)
        if (pair == activePresentation.currentPair) return
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let(currentAnalysis::guideFor),
            allowGuideFallback = allowsProvisionalGuide(currentAnalysis),
            preferences = displayOptions,
        )
        repaintVisibleContent()
    }

    /**
     * Synchronously repairs or removes the currently visible pair geometry.
     * This method must finish before the originating DocumentListener callback;
     * never make the active-pair refresh dependent on a later analysis pass.
     */
    fun documentChanged(change: DocumentChange) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        discardStaleAnalysis()
        tokenDecorations.documentChanged()
        updateProvisional(change)
        requestAnalysis()
    }

    fun visibleAreaChanged() {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (discardPresentationFromReplacedHighlighter()) return
        val currentAnalysis = analysisState.snapshot ?: return
        if (!hasCurrentTokenAnalysis(currentAnalysis)) return
        val presentationChanged =
            tokenDecorations.replaceIfOutsideWindow(
                currentAnalysis,
                visibleRange(editor),
                stickySourceRanges(editor),
                displayOptions,
            )
        if (!presentationChanged) return
        repaintVisibleContent()
    }

    fun updateOptions(nextOptions: BracketGuidePreferences, refreshColors: Boolean) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val previousOptions = displayOptions
        val previousAnalysis = plan.analysis
        val languagesChanged =
            options.disabledLanguageIds != nextOptions.disabledLanguageIds
        options = nextOptions
        plan = EditorPresentationPolicy.resolve(capabilities, options, activity)
        displayOptions = plan.presentation.applyTo(options)
        if (previousAnalysis != plan.analysis || languagesChanged) requestAnalysis()
        if (discardPresentationFromReplacedHighlighter()) return
        if (!plan.analysis.pairs) {
            clearPresentation()
            analysisState.publishComplete(currentStamp())
            publishMatcherAvailability(BraceMatcherAvailability.UNDETERMINED)
            repaintVisibleContent()
            return
        }
        if (languagesChanged) {
            clear()
            updateProvisional()
            return
        }
        val requiredCoverage = plan.analysis
        val currentFileType = EditorSurfaceClassifier.fileType(editor)
        val currentAnalysis =
            analysisState.snapshot?.takeIf { candidate ->
                candidate.stamp.matchesCurrent(
                    editor,
                    currentFileType,
                    candidate.stamp.coverage,
                    options.disabledLanguageIds,
                )
            }
        val releasePairGraph =
            currentAnalysis?.let { candidate ->
                shouldReleasePairGraph(requiredCoverage, candidate.stamp.coverage)
            } == true
        updateTokenPresentation(
            previousOptions,
            currentAnalysis,
            refreshColors,
        )

        val currentPairAnalysis =
            currentAnalysis
                ?.takeIf { showsActivePresentation() && it.stamp.coverage.activePair }
        val pair =
            currentPairAnalysis
                ?.activePairAt(caretOffset())
                ?: activePresentation.adjustedPair.takeIf { showsActivePresentation() }
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let { currentPairAnalysis?.guideFor(it) },
            allowGuideFallback =
            currentPairAnalysis == null ||
                allowsProvisionalGuide(currentPairAnalysis),
            preferences = displayOptions,
        )
        if (pair == null &&
            currentAnalysis == null &&
            plan.analysis.activePair
        ) {
            updateProvisional()
            return
        }
        if (releasePairGraph) {
            analysisState.forgetCompletion()
        } else if (currentAnalysis?.stamp?.matchesCurrent(
                editor,
                currentFileType,
                requiredCoverage,
                options.disabledLanguageIds,
            ) == true
        ) {
            analysisState.publishComplete(currentAnalysis)
        }
        repaintVisibleContent()
    }

    private fun updateTokenPresentation(
        previousOptions: BracketGuidePreferences,
        currentAnalysis: BracketSnapshot?,
        refreshColors: Boolean,
    ) {
        val wasVisible = previousOptions.enabled && previousOptions.colorBracketTokens
        val isVisible = displayOptions.enabled && displayOptions.colorBracketTokens
        when {
            wasVisible && !isVisible -> {
                tokenDecorations.updateAttributes(displayOptions)
            }

            !wasVisible && isVisible && currentAnalysis != null -> {
                tokenDecorations.replace(
                    currentAnalysis,
                    visibleRange(editor),
                    stickySourceRanges(editor),
                    displayOptions,
                )
            }

            isVisible &&
                (refreshColors || previousOptions.levelBaseColors != options.levelBaseColors) -> {
                tokenDecorations.updateAttributes(displayOptions)
            }
        }
    }

    fun dispose() {
        assertEdt()
        if (disposed) return
        disposed = true
        analysisRefreshRequested = {}
        clear()
    }

    /** Thread-safe acceptance query used by background highlighting passes. */
    fun canSkipAnalysis(required: AnalysisStamp): Boolean = analysisState.canSkip(required)

    /** Avoids touching editor markup when the application is already shutting down. */
    fun forgetAcceptedAnalysis() {
        analysisState.forgetAcceptance()
    }

    private fun clear() {
        assertEdt()
        analysisState.clear()
        publishMatcherAvailability(BraceMatcherAvailability.UNDETERMINED)
        clearPresentation()
    }

    private fun publishMatcherAvailability(next: BraceMatcherAvailability) {
        if (matcherAvailability == next) return
        matcherAvailability = next
        matcherAvailabilityChanged(editor)
    }

    private fun clearPresentation() {
        assertEdt()
        activePresentation.clear(preserveGuide = false)
        tokenDecorations.dispose()
    }

    private fun updateProvisional(change: DocumentChange? = null) {
        if (!showsActivePresentation()) {
            val hadActivePresentation = activePresentation.isVisible
            activePresentation.clear(preserveGuide = false)
            if (hadActivePresentation) repaintVisibleContent()
            return
        }
        if (discardPresentationFromReplacedHighlighter()) return
        if (change == null) {
            activePresentation.refreshProvisional(caretOffset(), displayOptions)
        } else {
            activePresentation.refreshAfterDocumentChange(
                change = change,
                caretOffset = caretOffset(),
                preferences = displayOptions,
            )
        }
        repaintVisibleContent()
    }

    private fun discardPresentationFromReplacedHighlighter(): Boolean {
        if (analysisHighlighter === editor.highlighter) {
            return false
        }
        clear()
        requestAnalysis()
        repaintVisibleContent()
        return true
    }

    /** Releases proportional-size indexes while their RangeMarkers stay visible. */
    private fun discardStaleAnalysis() {
        analysisState.discardStale(
            EditorSurfaceClassifier.fileType(editor),
            plan.analysis,
            options.disabledLanguageIds,
        )
    }

    private fun currentStamp(): AnalysisStamp = analysisState.currentStamp(
        EditorSurfaceClassifier.fileType(editor),
        plan.analysis,
        options.disabledLanguageIds,
    )

    private fun hasCurrentActivePair(candidate: BracketSnapshot): Boolean = analysisState.hasCurrentActivePair(
        candidate,
        EditorSurfaceClassifier.fileType(editor),
        options.disabledLanguageIds,
    )

    private fun hasCurrentTokenAnalysis(candidate: BracketSnapshot): Boolean = plan.analysis.tokens &&
        analysisState.hasCurrentTokens(
            candidate,
            EditorSurfaceClassifier.fileType(editor),
            options.disabledLanguageIds,
        )

    private fun allowsProvisionalGuide(candidate: BracketSnapshot): Boolean = !candidate.stamp.coverage.guidePosition &&
        !analysisState.hasRefused(
            currentStamp(),
            AnalysisLimit.GUIDE_CAPACITY,
        )

    private fun shouldReleasePairGraph(required: AnalysisCoverage, provided: AnalysisCoverage): Boolean =
        analysisState.shouldReleasePairGraph(required, provided)

    private fun activePair(snapshot: BracketSnapshot) =
        if (showsActivePresentation()) snapshot.activePairAt(caretOffset()) else null

    private fun showsActivePresentation(): Boolean = displayOptions.enabled &&
        (displayOptions.showsActivePair || displayOptions.showsGuide)

    val drawsGuides: Boolean
        get() = displayOptions.enabled && displayOptions.showsGuide

    val isVisible: Boolean
        get() = activity.visible

    fun setAnalysisRefreshRequester(request: () -> Unit) {
        assertEdt()
        analysisRefreshRequested = request
    }

    fun requestAnalysis() {
        assertEdt()
        if (!disposed && activity.visible) analysisRefreshRequested()
    }

    fun updateSurface(nextCapabilities: EditorCapabilities, nextActivity: EditorActivity) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (capabilities == nextCapabilities && activity == nextActivity) return
        val becameVisible = !activity.visible && nextActivity.visible
        capabilities = nextCapabilities
        activity = nextActivity
        updateOptions(options, refreshColors = false)
        if (becameVisible) requestAnalysis()
    }

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    /** Repaints only pixels that can currently display editor-owned presentation state. */
    private fun repaintVisibleContent() {
        val visibleArea = editor.scrollingModel.visibleArea
        if (visibleArea.isEmpty) {
            // Headless and detached editors may not have a viewport yet.
            editor.contentComponent.repaint()
        } else {
            editor.contentComponent.repaint(visibleArea)
        }
    }

    private companion object {
        private fun assertEdt() {
            ApplicationManager.getApplication().assertIsDispatchThread()
        }
    }
}
