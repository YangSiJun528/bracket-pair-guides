package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
import com.sijunyang.bracketpairguides.presentation.ActiveGuidePresentation
import com.sijunyang.bracketpairguides.presentation.VisibleTokenDecorations
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange

/** EDT-owned state and presentation for one editor. */
internal class EditorGuideSession(
    private val editor: Editor,
    private var visibleRange: (Editor) -> TextRange,
    private var options: BracketGuidePreferences,
) {
    private var disposed = false
    private var analysisHighlighter = editor.highlighter
    private val analysisState = EditorAnalysisState(editor)

    private val activePresentation = ActiveGuidePresentation(editor)
    private val tokenDecorations = VisibleTokenDecorations(editor)

    val hasCappedTokenDecorations: Boolean
        get() = tokenDecorations.isCapped

    fun updateDependenciesIfCurrent(
        visibleRange: (Editor) -> TextRange,
        passStamp: AnalysisStamp,
    ): Boolean {
        assertEdt()
        val requiredCoverage = options.analysisCoverage()
        if (disposed || editor.isDisposed ||
            !passStamp.matchesCurrent(
                editor,
                editorFileType(editor),
                requiredCoverage,
                options.disabledLanguageIds,
            )
        ) {
            return false
        }
        analysisHighlighter = editor.highlighter
        this.visibleRange = visibleRange
        return true
    }

    /** The only publication boundary for an authoritative analysis attempt. */
    fun accept(outcome: AnalysisOutcome): Unit {
        assertEdt()
        when (outcome) {
            is AnalysisOutcome.Complete -> acceptComplete(outcome.snapshot)
            is AnalysisOutcome.Limited -> acceptLimited(outcome)
            is AnalysisOutcome.Unavailable -> acceptUnavailable(outcome)
        }
    }

    private fun acceptComplete(nextAnalysis: BracketSnapshot): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val requiredCoverage = options.analysisCoverage()
        val currentFileType = editorFileType(editor)
        if (!nextAnalysis.stamp.matchesCurrent(
                editor,
                currentFileType,
                requiredCoverage,
                options.disabledLanguageIds,
            )
        ) {
            return
        }
        if (!requiredCoverage.pairs) {
            clearPresentation()
            analysisState.publishComplete(currentStamp())
            return
        }
        if (shouldReleasePairGraph(requiredCoverage, nextAnalysis.stamp.coverage)) {
            val compactAnalysis = analysisState.snapshot?.takeIf { current ->
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
                    options,
                )
                editor.contentComponent.repaint()
                return
            }
        }

        val pair = nextAnalysis.activePairAt(caretOffset())
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let(nextAnalysis::guideFor),
            allowGuideFallback = false,
            preferences = options,
        )
        tokenDecorations.replace(
            nextAnalysis,
            visibleRange(editor),
            options,
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
        editor.contentComponent.repaint()
    }

    /** Publishes exact lower facets after the requested guide index crosses its cap. */
    private fun acceptLimited(outcome: AnalysisOutcome.Limited): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val nextAnalysis = outcome.snapshot
        val attemptedStamp = outcome.stamp
        val requiredCoverage = options.analysisCoverage()
        val completedCoverage = requiredCoverage.copy(guidePosition = false)
        val currentFileType = editorFileType(editor)
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

        val pair = nextAnalysis.activePairAt(caretOffset())
        activePresentation.replace(
            pair = pair,
            indexedGuide = null,
            allowGuideFallback = false,
            preferences = options,
        )
        tokenDecorations.replace(
            nextAnalysis,
            visibleRange(editor),
            options,
        )
        analysisState.publishLimited(
            snapshot = nextAnalysis,
            attemptedStamp = attemptedStamp,
        )
        editor.contentComponent.repaint()
    }

    /** Accepts a bounded analysis refusal without publishing a partial snapshot. */
    private fun acceptUnavailable(outcome: AnalysisOutcome.Unavailable): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val stamp = outcome.stamp
        val limit = outcome.limit
        val requiredCoverage = options.analysisCoverage()
        if (stamp.coverage != requiredCoverage || !stamp.matchesCurrent(
                editor,
                editorFileType(editor),
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
        editor.contentComponent.repaint()
    }

    fun caretMoved(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (!options.analysisCoverage().activePair) return
        val currentAnalysis = analysisState.snapshot
        if (currentAnalysis == null || !hasCurrentActivePair(currentAnalysis)) {
            updateProvisional()
            return
        }

        val pair = currentAnalysis.activePairAt(caretOffset())
        if (pair == activePresentation.currentPair) return
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let(currentAnalysis::guideFor),
            allowGuideFallback = allowsProvisionalGuide(currentAnalysis),
            preferences = options,
        )
        editor.contentComponent.repaint()
    }

    fun documentChanged(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        discardStaleAnalysis()
        updateProvisional()
    }

    fun visibleAreaChanged(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (discardPresentationFromReplacedHighlighter()) return
        val currentAnalysis = analysisState.snapshot ?: return
        if (!hasCurrentTokenAnalysis(currentAnalysis)) return
        val presentationChanged = tokenDecorations.replaceIfOutsideWindow(
            currentAnalysis,
            visibleRange(editor),
            options,
        )
        if (!presentationChanged) return
        editor.contentComponent.repaint()
    }

    fun updateOptions(
        nextOptions: BracketGuidePreferences,
        refreshColors: Boolean,
    ): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val previousOptions = options
        val languagesChanged =
            previousOptions.disabledLanguageIds != nextOptions.disabledLanguageIds
        options = nextOptions
        if (discardPresentationFromReplacedHighlighter()) return
        if (!nextOptions.analysisCoverage().pairs) {
            clearPresentation()
            analysisState.publishComplete(currentStamp())
            editor.contentComponent.repaint()
            return
        }
        if (languagesChanged) {
            clear()
            updateProvisional()
            return
        }
        val requiredCoverage = options.analysisCoverage()
        val currentFileType = editorFileType(editor)
        val currentAnalysis = analysisState.snapshot?.takeIf { candidate ->
            candidate.stamp.matchesCurrent(
                editor,
                currentFileType,
                candidate.stamp.coverage,
                options.disabledLanguageIds,
            )
        }
        val releasePairGraph = currentAnalysis?.let { candidate ->
            shouldReleasePairGraph(requiredCoverage, candidate.stamp.coverage)
        } == true
        updateTokenPresentation(
            previousOptions,
            currentAnalysis,
            refreshColors,
        )

        val currentPairAnalysis = currentAnalysis
            ?.takeIf { requiredCoverage.activePair && it.stamp.coverage.activePair }
        val pair = currentPairAnalysis
            ?.activePairAt(caretOffset())
            ?: activePresentation.adjustedPair
        activePresentation.replace(
            pair = pair,
            indexedGuide = pair?.let { currentPairAnalysis?.guideFor(it) },
            allowGuideFallback = currentPairAnalysis == null ||
                allowsProvisionalGuide(currentPairAnalysis),
            preferences = options,
        )
        if (pair == null &&
            currentAnalysis == null &&
            options.analysisCoverage().activePair
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
        editor.contentComponent.repaint()
    }

    private fun updateTokenPresentation(
        previousOptions: BracketGuidePreferences,
        currentAnalysis: BracketSnapshot?,
        refreshColors: Boolean,
    ): Unit {
        val wasVisible = previousOptions.enabled && previousOptions.colorBracketTokens
        val isVisible = options.enabled && options.colorBracketTokens
        when {
            wasVisible && !isVisible -> tokenDecorations.updateAttributes(options)
            !wasVisible && isVisible && currentAnalysis != null ->
                tokenDecorations.replace(
                    currentAnalysis,
                    visibleRange(editor),
                    options,
                )
            isVisible &&
                (refreshColors || previousOptions.levelBaseColors != options.levelBaseColors) ->
                tokenDecorations.updateAttributes(options)
        }
    }

    fun dispose(): Unit {
        assertEdt()
        if (disposed) return
        disposed = true
        clear()
    }

    /** Thread-safe acceptance query used by background highlighting passes. */
    fun canSkipAnalysis(required: AnalysisStamp): Boolean = analysisState.canSkip(required)

    /** Avoids touching editor markup when the application is already shutting down. */
    fun forgetAcceptedAnalysis(): Unit {
        analysisState.forgetAcceptance()
    }

    private fun clear() {
        assertEdt()
        analysisState.clear()
        clearPresentation()
    }

    private fun clearPresentation() {
        assertEdt()
        activePresentation.clear(preserveGuide = false)
        tokenDecorations.dispose()
    }

    private fun updateProvisional() {
        if (!options.analysisCoverage().activePair) {
            val hadActivePresentation = activePresentation.isVisible
            activePresentation.clear(preserveGuide = false)
            if (hadActivePresentation) editor.contentComponent.repaint()
            return
        }
        if (discardPresentationFromReplacedHighlighter()) return
        activePresentation.refreshProvisional(caretOffset(), options)
        editor.contentComponent.repaint()
    }

    private fun discardPresentationFromReplacedHighlighter(): Boolean {
        if (analysisHighlighter === editor.highlighter) {
            return false
        }
        clear()
        editor.contentComponent.repaint()
        return true
    }

    /** Releases proportional-size indexes while their RangeMarkers stay visible. */
    private fun discardStaleAnalysis() {
        analysisState.discardStale(
            editorFileType(editor),
            options.analysisCoverage(),
            options.disabledLanguageIds,
        )
    }

    private fun currentStamp(): AnalysisStamp = analysisState.currentStamp(
        editorFileType(editor),
        options.analysisCoverage(),
        options.disabledLanguageIds,
    )

    private fun hasCurrentActivePair(candidate: BracketSnapshot): Boolean =
        analysisState.hasCurrentActivePair(
            candidate,
            editorFileType(editor),
            options.disabledLanguageIds,
        )

    private fun hasCurrentTokenAnalysis(candidate: BracketSnapshot): Boolean {
        if (!options.analysisCoverage().tokens) return false
        return analysisState.hasCurrentTokens(
            candidate,
            editorFileType(editor),
            options.disabledLanguageIds,
        )
    }

    private fun allowsProvisionalGuide(candidate: BracketSnapshot): Boolean {
        if (candidate.stamp.coverage.guidePosition) return false
        return !analysisState.hasRefused(
            currentStamp(),
            AnalysisLimit.GUIDE_CAPACITY,
        )
    }

    private fun shouldReleasePairGraph(
        required: AnalysisCoverage,
        provided: AnalysisCoverage,
    ): Boolean = analysisState.shouldReleasePairGraph(required, provided)

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    private companion object {
        private fun assertEdt() {
            ApplicationManager.getApplication().assertIsDispatchThread()
        }
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
