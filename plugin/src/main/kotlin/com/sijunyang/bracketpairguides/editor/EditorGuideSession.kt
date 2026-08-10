package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.CaretContext
import com.sijunyang.bracketpairguides.analysis.ActivePairKnowledge
import com.sijunyang.bracketpairguides.analysis.AnalysisCoverage
import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.BracketGuide
import com.sijunyang.bracketpairguides.analysis.BracketPair
import com.sijunyang.bracketpairguides.presentation.ActivePairMarkup
import com.sijunyang.bracketpairguides.presentation.VisibleTokenDecorations
import com.sijunyang.bracketpairguides.settings.BracketGuidePreferences
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange

/** EDT-owned state and presentation for one editor. */
internal class EditorGuideSession(
    private val editor: Editor,
    private var resolveActivePair: (CaretContext) -> ActivePairKnowledge,
    private var visibleRange: (Editor) -> TextRange,
    private var options: BracketGuidePreferences,
) {
    private var disposed = false
    private var analysisHighlighter = editor.highlighter
    private val analysisState = EditorAnalysisState(editor)

    /** EDT-owned recognition and presentation state. */
    private val trackedPair = TrackedBracketPair(editor)

    private var tokenDecorationState: VisibleTokenDecorations = VisibleTokenDecorations.EMPTY
    private val activeMarkup = ActivePairMarkup(editor)

    val hasCappedTokenDecorations: Boolean
        get() = tokenDecorationState.isCapped

    fun updateDependenciesIfCurrent(
        resolveActivePair: (CaretContext) -> ActivePairKnowledge,
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
        this.resolveActivePair = resolveActivePair
        analysisHighlighter = editor.highlighter
        this.visibleRange = visibleRange
        return true
    }

    fun accept(nextAnalysis: BracketSnapshot): Unit {
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
            clear()
            analysisState.acceptedStamp = currentStamp()
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
                tokenDecorationState = tokenDecorationState.replace(
                    editor,
                    compactAnalysis,
                    visibleRange(editor),
                    options,
                )
                editor.contentComponent.repaint()
                return
            }
        }

        analysisState.snapshot = nextAnalysis
        val pair = nextAnalysis.activePairAt(caretOffset())
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let(nextAnalysis::guideFor),
            change = null,
        )
        tokenDecorationState = tokenDecorationState.replace(
            editor,
            nextAnalysis,
            visibleRange(editor),
            options,
        )
        if (shouldReleasePairGraph(
                requiredCoverage,
                nextAnalysis.stamp.coverage,
            )
        ) {
            analysisState.acceptedStamp = null
        } else {
            analysisState.acceptedStamp = nextAnalysis.stamp
        }
        editor.contentComponent.repaint()
    }

    fun caretMoved(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (!options.analysisCoverage().activePair) return
        val currentAnalysis = analysisState.snapshot
        if (currentAnalysis == null || !isCurrent(currentAnalysis)) {
            updateProvisional(null)
            return
        }

        val pair = currentAnalysis.activePairAt(caretOffset())
        if (pair == trackedPair.current) return
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let(currentAnalysis::guideFor),
            change = null,
        )
        editor.contentComponent.repaint()
    }

    fun documentChanged(
        change: DocumentChange,
        resolveImmediately: Boolean,
    ): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        discardStaleAnalysis()
        updateProvisional(change, resolveImmediately)
    }

    fun visibleAreaChanged(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (discardPresentationFromReplacedHighlighter()) return
        val currentAnalysis = analysisState.snapshot ?: return
        if (!hasCurrentTokenAnalysis(currentAnalysis)) return
        val nextDecorations = tokenDecorationState.replaceIfOutsideWindow(
            editor,
            currentAnalysis,
            visibleRange(editor),
            options,
        ) ?: return
        tokenDecorationState = nextDecorations
        editor.contentComponent.repaint()
    }

    fun updateOptions(
        nextOptions: BracketGuidePreferences,
        resolveImmediately: Boolean,
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
            clear()
            analysisState.acceptedStamp = currentStamp()
            editor.contentComponent.repaint()
            return
        }
        if (languagesChanged) {
            clear()
            updateProvisional(null, resolveImmediately)
            return
        }
        val requiredCoverage = options.analysisCoverage()
        val currentFileType = editorFileType(editor)
        val currentAnalysis = analysisState.snapshot?.takeIf { candidate ->
            candidate.stamp.matchesCurrent(
                editor,
                currentFileType,
                requiredCoverage,
                options.disabledLanguageIds,
            )
        }
        val releasePairGraph = currentAnalysis?.let { candidate ->
            shouldReleasePairGraph(requiredCoverage, candidate.stamp.coverage)
        } == true
        tokenDecorationState = updateTokenPresentation(
            previousOptions,
            currentAnalysis,
            refreshColors,
        )

        val pair = currentAnalysis
            ?.takeIf { requiredCoverage.activePair }
            ?.activePairAt(caretOffset())
            ?: trackedPair.adjusted
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let { currentAnalysis?.guideFor(it) },
            change = null,
        )
        if (pair == null &&
            currentAnalysis == null &&
            options.analysisCoverage().activePair
        ) {
            updateProvisional(null, resolveImmediately)
            return
        }
        if (releasePairGraph) {
            analysisState.acceptedStamp = null
        } else if (currentAnalysis != null) {
            analysisState.acceptedStamp = currentAnalysis.stamp
        }
        editor.contentComponent.repaint()
    }

    private fun updateTokenPresentation(
        previousOptions: BracketGuidePreferences,
        currentAnalysis: BracketSnapshot?,
        refreshColors: Boolean,
    ): VisibleTokenDecorations {
        val wasVisible = previousOptions.enabled && previousOptions.colorBracketTokens
        val isVisible = options.enabled && options.colorBracketTokens
        return when {
            wasVisible && !isVisible -> tokenDecorationState.updateAttributes(
                editor,
                options,
            )
            !wasVisible && isVisible && currentAnalysis != null ->
                tokenDecorationState.replace(
                    editor,
                    currentAnalysis,
                    visibleRange(editor),
                    options,
                )
            isVisible &&
                (refreshColors || previousOptions.levelBaseColors != options.levelBaseColors) ->
                tokenDecorationState.updateAttributes(
                    editor,
                    options,
                )
            else -> tokenDecorationState
        }
    }

    fun dispose(): Unit {
        assertEdt()
        if (disposed) return
        disposed = true
        clear()
    }

    /** Thread-safe acceptance query used by background highlighting passes. */
    fun hasAcceptedAnalysis(required: AnalysisStamp): Boolean = analysisState.covers(required)

    /** Avoids touching editor markup when the application is already shutting down. */
    fun forgetAcceptedAnalysis(): Unit {
        analysisState.acceptedStamp = null
    }

    private fun clear() {
        assertEdt()
        analysisState.clear()
        clearActive(preserveGuide = false)
        tokenDecorationState.dispose()
        tokenDecorationState = VisibleTokenDecorations.EMPTY
    }

    private fun updateProvisional(
        change: DocumentChange?,
        resolveImmediately: Boolean = true,
    ) {
        if (!options.analysisCoverage().activePair) {
            val hadActivePresentation = activeMarkup.isVisible
            clearActive(preserveGuide = false)
            if (hadActivePresentation) editor.contentComponent.repaint()
            return
        }
        if (discardPresentationFromReplacedHighlighter()) return
        val adjustedPair = trackedPair.adjusted
        val caretOffset = caretOffset()
        val resolution = if (resolveImmediately) {
            resolveActivePair(
                CaretContext(
                    editor = editor,
                    fileType = editorFileType(editor),
                    caretOffset = caretOffset,
                    disabledLanguageIds = options.disabledLanguageIds,
                ),
            )
        } else {
            ActivePairKnowledge.Unknown
        }
        val pair = when (resolution) {
            is ActivePairKnowledge.Known -> resolution.pair?.let { pair ->
                trackedPair.withDepthHint(pair, adjustedPair)
            }
            ActivePairKnowledge.Unknown -> adjustedPair
        }
        if (pair?.contains(caretOffset) != true) {
            clearActive(preserveGuide = false)
            editor.contentComponent.repaint()
            return
        }

        if (trackedPair.hasDifferentRange(pair, adjustedPair)) {
            replaceActive(
                pair = pair,
                indexedGuide = null,
                change = change,
            )
        } else if (resolveImmediately) {
            refreshProvisionalPair(pair, change)
        } else {
            refreshAdjustedPair(pair)
        }
        editor.contentComponent.repaint()
    }

    /** Keeps inactive split editors coherent without multiplying the immediate-search budget. */
    private fun refreshAdjustedPair(pair: BracketPair) {
        val previousGuide = currentGuide()
        val guide = when {
            !options.enabled || !options.showsGuide -> null
            pair.openLine == pair.closeLine -> BracketGuide(pair, guideColumn = 0)
            previousGuide == null -> null
            else -> previousGuide.copy(
                pair = pair,
                anchorLine = (trackedPair.anchorLine ?: previousGuide.anchorLine)
                    .coerceIn(pair.openLine, pair.closeLine),
            )
        }
        updateGuide(guide)
        trackedPair.refresh(pair, guide)
    }

    private fun refreshProvisionalPair(pair: BracketPair, change: DocumentChange?) {
        val previousGuide = currentGuide()
        val currentAnchorLine = trackedPair.anchorLine
        val guide = createGuide(pair, null, previousGuide, currentAnchorLine, change)
        updateGuide(guide)
        trackedPair.refresh(pair, guide)
    }

    private fun replaceActive(
        pair: BracketPair?,
        indexedGuide: BracketGuide?,
        change: DocumentChange?,
    ) {
        val previousGuide = currentGuide()
        val currentAnchorLine = trackedPair.anchorLine
        clearActive(preserveGuide = true)
        if (pair == null || !options.enabled ||
            (!options.showsGuide && !options.showsActivePair) ||
            !pair.hasWellFormedTokenRange(editor.document.textLength)
        ) {
            activeMarkup.clearGuide()
            return
        }

        val guide = createGuide(
            pair,
            indexedGuide,
            previousGuide,
            currentAnchorLine,
            change,
        )
        trackedPair.track(pair, guide)
        updateGuide(guide)
        activeMarkup.showPair(pair, options)
    }

    private fun updateGuide(guide: BracketGuide?) {
        activeMarkup.showGuide(guide, options)
    }

    private fun createGuide(
        pair: BracketPair,
        indexedGuide: BracketGuide?,
        previousGuide: BracketGuide?,
        currentAnchorLine: Int?,
        change: DocumentChange?,
    ): BracketGuide? {
        if (!options.enabled || !options.showsGuide) return null
        if (pair.openLine == pair.closeLine) return BracketGuide(pair, 0)
        // A full snapshot can intentionally omit the proportional-size index
        // for an oversized document; keep that path bounded on the EDT.
        return indexedGuide ?: GuidePositionFallback.guideFor(
            editor,
            pair,
            previousGuide,
            currentAnchorLine,
            change,
        )
    }

    private fun clearActive(preserveGuide: Boolean) {
        trackedPair.clear()
        activeMarkup.clear(preserveGuide)
    }

    private fun currentGuide(): BracketGuide? = activeMarkup.guide

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

    private fun isCurrent(candidate: BracketSnapshot): Boolean =
        analysisState.isCurrent(
            candidate,
            editorFileType(editor),
            options.analysisCoverage(),
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

    private fun shouldReleasePairGraph(
        required: AnalysisCoverage,
        provided: AnalysisCoverage,
    ): Boolean = analysisState.shouldReleasePairGraph(required, provided)

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    private fun BracketPair.contains(offset: Int): Boolean =
        offset > openOffset && offset.toLong() < closeOffset.toLong() + closeTokenLength

    private companion object {
        private fun assertEdt() {
            ApplicationManager.getApplication().assertIsDispatchThread()
        }
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
