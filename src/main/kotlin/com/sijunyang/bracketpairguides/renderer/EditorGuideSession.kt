package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange

/** EDT-owned state and presentation for one editor. */
internal class EditorGuideSession private constructor(
    private val editor: Editor,
    private var activePairResolver: ActiveBracketPairResolver,
    private var visibleRangeProvider: (Editor) -> TextRange,
    private var options: PluginOptions,
    private val retainAnalysisWhenInactive: Boolean,
) {
    private var disposed = false
    private var activePairResolverHighlighterIdentity =
        System.identityHashCode(editor.highlighter)
    /** The only session state read by background highlighting passes. */
    @Volatile
    private var acceptedStamp: AnalysisStamp? = null

    /** EDT-owned recognition and presentation state. */
    private var snapshot: AnalysisSnapshot? = null
    private var activePairIndex = ActiveBracketPairIndex.NO_PAIR
    private var activePair: BracketPair? = null
    private var activeRange: RangeMarker? = null
    private var activeAnchor: RangeMarker? = null

    internal var tokenDecorations: VisibleTokenDecorations = VisibleTokenDecorations.EMPTY
        private set
    internal var activeGuide: RangeHighlighter? = null
        private set
    internal var activePairHighlights: List<RangeHighlighter> = emptyList()
        private set

    fun updateDependenciesIfCurrent(
        resolver: ActiveBracketPairResolver,
        rangeProvider: (Editor) -> TextRange,
        passStamp: AnalysisStamp,
    ): Boolean {
        assertEdt()
        if (disposed || editor.isDisposed || !passStamp.satisfies(currentStamp())) return false
        activePairResolver = resolver
        activePairResolverHighlighterIdentity = passStamp.highlighterIdentity
        visibleRangeProvider = rangeProvider
        return true
    }

    fun accept(nextSnapshot: AnalysisSnapshot) {
        assertEdt()
        if (disposed || editor.isDisposed || !nextSnapshot.stamp.satisfies(currentStamp())) return

        snapshot = nextSnapshot
        val pairIndex = nextSnapshot.activeIndex.activePairIndex(caretOffset())
        replaceActive(
            pair = nextSnapshot.pairs.getOrNull(pairIndex),
            pairIndex = pairIndex,
            positionIndex = nextSnapshot.positionIndex,
            change = null,
        )
        tokenDecorations = VisibleTokenDecorationManager.replace(
            editor,
            tokenDecorations,
            nextSnapshot.tokenIndex,
            visibleRangeProvider(editor),
            options,
        )
        acceptedStamp = nextSnapshot.stamp
        editor.contentComponent.repaint()
    }

    fun caretMoved() {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (!AnalysisCapabilities.from(options).activePair) return
        val currentSnapshot = snapshot
        if (currentSnapshot == null || !isCurrent(currentSnapshot)) {
            updateProvisional(null)
            return
        }

        val pairIndex = currentSnapshot.activeIndex.activePairIndex(caretOffset())
        if (pairIndex == activePairIndex) return
        replaceActive(
            pair = currentSnapshot.pairs.getOrNull(pairIndex),
            pairIndex = pairIndex,
            positionIndex = currentSnapshot.positionIndex,
            change = null,
        )
        editor.contentComponent.repaint()
    }

    fun documentChanged(
        change: DocumentChange,
        resolveImmediately: Boolean = true,
    ) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        discardStaleAnalysis()
        updateProvisional(change, resolveImmediately)
    }

    fun visibleAreaChanged() {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (discardPresentationFromReplacedHighlighter()) return
        val currentSnapshot = snapshot ?: return
        if (!isCurrent(currentSnapshot)) return
        val nextDecorations = VisibleTokenDecorationManager.replaceIfOutsideWindow(
            editor,
            tokenDecorations,
            currentSnapshot.tokenIndex,
            visibleRangeProvider(editor),
            options,
        ) ?: return
        tokenDecorations = nextDecorations
        editor.contentComponent.repaint()
    }

    fun updateOptions(nextOptions: PluginOptions) {
        updateOptions(nextOptions, resolveImmediately = true)
    }

    fun updateOptions(
        nextOptions: PluginOptions,
        resolveImmediately: Boolean = true,
    ) {
        updateOptions(nextOptions, resolveImmediately, refreshColors = false)
    }

    fun updateOptions(
        nextOptions: PluginOptions,
        resolveImmediately: Boolean,
        refreshColors: Boolean,
    ) {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val previousOptions = options
        val languagesChanged =
            previousOptions.disabledLanguageIds != nextOptions.disabledLanguageIds
        options = nextOptions
        if (discardPresentationFromReplacedHighlighter()) return
        if (!retainAnalysisWhenInactive &&
            !AnalysisCapabilities.from(nextOptions).pairs
        ) {
            clear()
            editor.contentComponent.repaint()
            return
        }
        if (languagesChanged) {
            clear()
            updateProvisional(null, resolveImmediately)
            return
        }
        val currentSnapshot = snapshot
        val currentAnalysis = currentSnapshot?.takeIf(::isCurrent)
        tokenDecorations = updateTokenPresentation(
            previousOptions,
            currentAnalysis,
            refreshColors,
        )

        val pairIndex = if (currentAnalysis != null) {
            currentAnalysis.activeIndex.activePairIndex(caretOffset())
        } else {
            ActiveBracketPairIndex.NO_PAIR
        }
        val pair = if (pairIndex == ActiveBracketPairIndex.NO_PAIR) {
            adjustedActivePair()
        } else {
            currentAnalysis?.pairs?.getOrNull(pairIndex)
        }
        replaceActive(
            pair = pair,
            pairIndex = pairIndex,
            positionIndex = currentAnalysis?.positionIndex,
            change = null,
        )
        if (pair == null &&
            currentAnalysis == null &&
            AnalysisCapabilities.from(options).activePair
        ) {
            updateProvisional(null, resolveImmediately)
            return
        }
        editor.contentComponent.repaint()
    }

    fun requiresAnalysisRefresh(): Boolean {
        assertEdt()
        if (disposed || editor.isDisposed) return false
        val required = currentStamp()
        return required.capabilities.pairs &&
            snapshot?.stamp?.satisfies(required) != true
    }

    private fun updateTokenPresentation(
        previousOptions: PluginOptions,
        currentAnalysis: AnalysisSnapshot?,
        refreshColors: Boolean,
    ): VisibleTokenDecorations {
        val wasVisible = previousOptions.enabled && previousOptions.colorBracketTokens
        val isVisible = options.enabled && options.colorBracketTokens
        return when {
            wasVisible && !isVisible -> VisibleTokenDecorationManager.updateAttributes(
                editor,
                tokenDecorations,
                options,
            )
            !wasVisible && isVisible && currentAnalysis != null ->
                VisibleTokenDecorationManager.replace(
                    editor,
                    tokenDecorations,
                    currentAnalysis.tokenIndex,
                    visibleRangeProvider(editor),
                    options,
                )
            isVisible &&
                (refreshColors || previousOptions.levelBaseColors != options.levelBaseColors) ->
                VisibleTokenDecorationManager.updateAttributes(
                    editor,
                    tokenDecorations,
                    options,
                )
            else -> tokenDecorations
        }
    }

    fun dispose() {
        assertEdt()
        if (disposed) return
        disposed = true
        clear()
    }

    fun clear() {
        assertEdt()
        acceptedStamp = null
        snapshot = null
        clearActive(preserveGuide = false)
        VisibleTokenDecorationManager.dispose(tokenDecorations)
        tokenDecorations = VisibleTokenDecorations.EMPTY
    }

    private fun updateProvisional(
        change: DocumentChange?,
        resolveImmediately: Boolean = true,
    ) {
        if (!AnalysisCapabilities.from(options).activePair) {
            val hadActivePresentation = activeGuide != null || activePairHighlights.isNotEmpty()
            clearActive(preserveGuide = false)
            if (hadActivePresentation) editor.contentComponent.repaint()
            return
        }
        if (discardPresentationFromReplacedHighlighter()) return
        val adjustedPair = adjustedActivePair()
        val caretOffset = caretOffset()
        val resolution = if (resolveImmediately) {
            activePairResolver.findInnermost(editor, caretOffset)
        } else {
            ActiveBracketPairResolution.Incomplete
        }
        val pair = when (resolution) {
            is ActiveBracketPairResolution.Complete ->
                resolution.pair?.withDepthHint(adjustedPair)
            ActiveBracketPairResolution.Incomplete -> adjustedPair
        }
        if (pair?.contains(caretOffset) != true) {
            clearActive(preserveGuide = false)
            editor.contentComponent.repaint()
            return
        }

        if (pair.hasDifferentRangeFrom(adjustedPair)) {
            replaceActive(
                pair = pair,
                pairIndex = ActiveBracketPairIndex.NO_PAIR,
                positionIndex = null,
                change = change,
            )
        } else if (resolveImmediately) {
            refreshProvisionalPair(pair, change)
        } else {
            refreshAdjustedPair(pair)
        }
        editor.contentComponent.repaint()
    }

    /** Keeps inactive split editors coherent without multiplying the resolver budget. */
    private fun refreshAdjustedPair(pair: BracketPair) {
        val previousGuide = paintState()?.guide
        val guide = when {
            !options.enabled || !options.showsGuide -> null
            pair.openLine == pair.closeLine -> BracketGuide(pair, guideColumn = 0)
            previousGuide == null -> null
            else -> previousGuide.copy(
                pair = pair,
                anchorLine = (activeAnchorLine() ?: previousGuide.anchorLine)
                    .coerceIn(pair.openLine, pair.closeLine),
            )
        }
        updateGuide(guide)
        updateAnchor(guide)
        activePair = pair
        activePairIndex = ActiveBracketPairIndex.NO_PAIR
    }

    private fun refreshProvisionalPair(pair: BracketPair, change: DocumentChange?) {
        val previousGuide = paintState()?.guide
        val currentAnchorLine = activeAnchorLine()
        val guide = createGuide(pair, null, previousGuide, currentAnchorLine, change)
        updateGuide(guide)
        updateAnchor(guide)
        activePair = pair
        activePairIndex = ActiveBracketPairIndex.NO_PAIR
    }

    private fun replaceActive(
        pair: BracketPair?,
        pairIndex: Int,
        positionIndex: GuidePositionIndex?,
        change: DocumentChange?,
    ) {
        val previousGuide = paintState()?.guide
        val currentAnchorLine = activeAnchorLine()
        clearActive(preserveGuide = true)
        if (pair == null || !options.enabled ||
            (!options.showsGuide && !options.showsActivePair) ||
            !pair.hasWellFormedTokenRange(editor.document.textLength)
        ) {
            activeGuide?.dispose()
            activeGuide = null
            return
        }

        val guide = createGuide(
            pair,
            positionIndex,
            previousGuide,
            currentAnchorLine,
            change,
        )
        activePairIndex = pairIndex
        activePair = pair
        activeRange = editor.document.createRangeMarker(
            pair.openOffset,
            pair.closeOffset + pair.closeTokenLength,
        ).apply {
            isGreedyToLeft = false
            isGreedyToRight = false
        }
        updateGuide(guide)
        updateAnchor(guide)
        activePairHighlights = ActivePairDecoration.addPairHighlights(editor, pair, options)
    }

    private fun updateGuide(guide: BracketGuide?) {
        activeGuide = if (guide == null) {
            activeGuide?.dispose()
            null
        } else {
            ActivePairDecoration.addGuide(editor, guide, options, activeGuide)
        }
    }

    private fun createGuide(
        pair: BracketPair,
        positionIndex: GuidePositionIndex?,
        previousGuide: BracketGuide?,
        currentAnchorLine: Int?,
        change: DocumentChange?,
    ): BracketGuide? {
        if (!options.enabled || !options.showsGuide) return null
        if (pair.openLine == pair.closeLine) return BracketGuide(pair, 0)
        // A full snapshot can intentionally omit the proportional-size index
        // for an oversized document; keep that path bounded on the EDT.
        return positionIndex?.guideFor(pair) ?: ActiveGuidePositionResolver.resolve(
            editor,
            pair,
            previousGuide,
            currentAnchorLine,
            change,
        )
    }

    private fun updateAnchor(guide: BracketGuide?) {
        val line = guide?.anchorLine?.coerceIn(0, editor.document.lineCount - 1)
        val currentLine = activeAnchorLine()
        if (line == null) {
            activeAnchor?.dispose()
            activeAnchor = null
        } else if (currentLine != line) {
            activeAnchor?.dispose()
            val offset = editor.document.getLineStartOffset(line)
            activeAnchor = editor.document.createRangeMarker(offset, offset).apply {
                isGreedyToLeft = false
                isGreedyToRight = false
            }
        }
    }

    private fun clearActive(preserveGuide: Boolean) {
        activeRange?.dispose()
        activeRange = null
        activeAnchor?.dispose()
        activeAnchor = null
        for (highlighter in activePairHighlights) {
            if (highlighter.isValid) highlighter.dispose()
        }
        activePairHighlights = emptyList()
        if (!preserveGuide) {
            activeGuide?.dispose()
            activeGuide = null
        }
        activePairIndex = ActiveBracketPairIndex.NO_PAIR
        activePair = null
    }

    private fun adjustedActivePair(): BracketPair? {
        val original = activePair ?: return null
        val range = activeRange?.takeIf(RangeMarker::isValid) ?: return null
        val closeOffset = range.endOffset - original.closeTokenLength
        if (range.startOffset + original.openTokenLength > closeOffset) return null
        return original.copy(
            openOffset = range.startOffset,
            closeOffset = closeOffset,
            openLine = editor.document.getLineNumber(range.startOffset),
            closeLine = editor.document.getLineNumber(closeOffset),
        )
    }

    private fun activeAnchorLine(): Int? {
        val marker = activeAnchor?.takeIf(RangeMarker::isValid) ?: return null
        return editor.document.getLineNumber(
            marker.startOffset.coerceIn(0, editor.document.textLength),
        )
    }

    private fun paintState(): GuidePaintState? =
        activeGuide?.takeIf(RangeHighlighter::isValid)?.getUserData(GUIDE_PAINT_STATE_KEY)

    private fun discardPresentationFromReplacedHighlighter(): Boolean {
        if (activePairResolverHighlighterIdentity ==
            System.identityHashCode(editor.highlighter)
        ) {
            return false
        }
        clear()
        editor.contentComponent.repaint()
        return true
    }

    /** Releases proportional-size indexes while their RangeMarkers stay visible. */
    private fun discardStaleAnalysis() {
        val required = currentStamp()
        if (snapshot?.stamp?.satisfies(required) == false) snapshot = null
        if (acceptedStamp?.satisfies(required) == false) acceptedStamp = null
    }

    private fun currentStamp(): AnalysisStamp = AnalysisStamp.current(
        editor,
        AnalysisCapabilities.from(options),
        options.disabledLanguageIds,
    )

    private fun isCurrent(candidate: AnalysisSnapshot): Boolean =
        candidate.stamp.satisfies(currentStamp())

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    private fun BracketPair.contains(offset: Int): Boolean =
        offset > openOffset && offset < closeOffset + closeTokenLength

    private fun BracketPair.withDepthHint(previous: BracketPair?): BracketPair {
        if (previous == null) return this
        val depth = when {
            openOffset == previous.openOffset && closeOffset == previous.closeOffset ->
                previous.depth
            openOffset > previous.openOffset && closeOffset < previous.closeOffset ->
                previous.depth + 1
            else -> previous.depth
        }
        return copy(depth = depth)
    }

    private fun BracketPair.hasDifferentRangeFrom(other: BracketPair?): Boolean {
        return other == null ||
            openOffset != other.openOffset ||
            openTokenLength != other.openTokenLength ||
            closeOffset != other.closeOffset ||
            closeTokenLength != other.closeTokenLength
    }

    companion object {
        private val KEY = Key.create<EditorGuideSession>("bracket.pair.guides.editor.session")

        fun install(
            editor: Editor,
            resolver: ActiveBracketPairResolver,
            visibleRangeProvider: (Editor) -> TextRange,
        ): EditorGuideSession {
            assertEdt()
            val existing = editor.getUserData(KEY)
            if (existing != null) return existing
            return EditorGuideSession(
                editor,
                resolver,
                visibleRangeProvider,
                PluginSettings.getInstance().options,
                retainAnalysisWhenInactive = false,
            ).also {
                editor.putUserData(KEY, it)
            }
        }

        fun detached(
            editor: Editor,
            options: PluginOptions,
            visibleRangeProvider: (Editor) -> TextRange,
        ): EditorGuideSession {
            assertEdt()
            return EditorGuideSession(
                editor,
                ActiveBracketPairResolver.NONE,
                visibleRangeProvider,
                options,
                retainAnalysisWhenInactive = true,
            )
        }

        /** The only session query allowed from a background highlighting pass. */
        fun hasAcceptedAnalysis(editor: Editor, required: AnalysisStamp): Boolean =
            editor.getUserData(KEY)?.acceptedStamp?.satisfies(required) == true

        fun get(editor: Editor): EditorGuideSession? = editor.getUserData(KEY)

        fun dispose(editor: Editor) {
            val application = ApplicationManager.getApplication()
            if (!application.isDisposed) assertEdt()
            val session = editor.getUserData(KEY)
            editor.putUserData(KEY, null)
            if (application.isDisposed && !application.isDispatchThread) {
                session?.acceptedStamp = null
                return
            }
            session?.dispose()
        }

        private fun assertEdt() {
            ApplicationManager.getApplication().assertIsDispatchThread()
        }
    }
}
