package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.analysis.api.ActivePairRequest
import com.sijunyang.bracketpairguides.analysis.api.ActivePairResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.api.AnalysisResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisRevision
import com.sijunyang.bracketpairguides.analysis.api.AnalyzeRequest
import com.sijunyang.bracketpairguides.analysis.api.BracketEngine
import com.sijunyang.bracketpairguides.analysis.api.BracketGuide
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.presentation.ActivePairDecoration
import com.sijunyang.bracketpairguides.presentation.VisibleTokenDecorationManager
import com.sijunyang.bracketpairguides.presentation.VisibleTokenDecorations
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import org.jetbrains.annotations.TestOnly

/** EDT-owned state and presentation for one editor. */
internal class EditorGuideSession private constructor(
    private val editor: Editor,
    private var engine: BracketEngine,
    private var visibleRangeProvider: (Editor) -> TextRange,
    private var options: PluginOptions,
    private val retainAnalysisWhenInactive: Boolean,
) {
    private var disposed = false
    private var analysisHighlighterIdentity =
        System.identityHashCode(editor.highlighter)
    /** The only session state read by background highlighting passes. */
    @Volatile
    private var acceptedRevision: AnalysisRevision? = null

    /** EDT-owned recognition and presentation state. */
    private var analysis: AnalysisResult? = null
    private var activePair: BracketPair? = null
    private var activeRange: RangeMarker? = null
    private var activeAnchor: RangeMarker? = null

    private var tokenDecorationState: VisibleTokenDecorations = VisibleTokenDecorations.EMPTY
    private var activeGuideState: RangeHighlighter? = null
    private var activePairHighlightState: List<RangeHighlighter> = emptyList()

    public val hasCappedTokenDecorations: Boolean
        get() = tokenDecorationState.isCapped

    @get:TestOnly
    public val tokenDecorations: VisibleTokenDecorations
        get() = tokenDecorationState

    @get:TestOnly
    public val activeGuide: RangeHighlighter?
        get() = activeGuideState

    @get:TestOnly
    public val activePairHighlights: List<RangeHighlighter>
        get() = activePairHighlightState

    public fun updateDependenciesIfCurrent(
        engine: BracketEngine,
        rangeProvider: (Editor) -> TextRange,
        passRevision: AnalysisRevision,
    ): Boolean {
        assertEdt()
        val requiredCapabilities = options.analysisCapabilities()
        if (disposed || editor.isDisposed ||
            !passRevision.satisfiesCurrent(
                editor,
                editorFileType(editor),
                requiredCapabilities,
                options.disabledLanguageIds,
            )
        ) {
            return false
        }
        this.engine = engine
        analysisHighlighterIdentity = System.identityHashCode(editor.highlighter)
        visibleRangeProvider = rangeProvider
        return true
    }

    public fun accept(nextAnalysis: AnalysisResult): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        val requiredCapabilities = options.analysisCapabilities()
        val currentFileType = editorFileType(editor)
        if (!nextAnalysis.revision.satisfiesCurrent(
                editor,
                currentFileType,
                requiredCapabilities,
                options.disabledLanguageIds,
            )
        ) {
            return
        }
        if (!retainAnalysisWhenInactive && !requiredCapabilities.pairs) {
            clear()
            acceptedRevision = currentRevision()
            return
        }
        if (shouldReleasePairGraph(requiredCapabilities, nextAnalysis.revision.capabilities)) {
            val compactAnalysis = analysis?.takeIf { current ->
                current.revision.satisfiesCurrent(
                    editor,
                    currentFileType,
                    requiredCapabilities,
                    options.disabledLanguageIds,
                ) &&
                    !shouldReleasePairGraph(
                        requiredCapabilities,
                        current.revision.capabilities,
                    )
            }
            if (compactAnalysis != null) {
                tokenDecorationState = VisibleTokenDecorationManager.replace(
                    editor,
                    tokenDecorationState,
                    compactAnalysis,
                    visibleRangeProvider(editor),
                    options,
                )
                editor.contentComponent.repaint()
                return
            }
        }

        analysis = nextAnalysis
        val pair = nextAnalysis.activePairAt(caretOffset())
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let(nextAnalysis::guideFor),
            change = null,
        )
        tokenDecorationState = VisibleTokenDecorationManager.replace(
            editor,
            tokenDecorationState,
            nextAnalysis,
            visibleRangeProvider(editor),
            options,
        )
        if (shouldReleasePairGraph(
                requiredCapabilities,
                nextAnalysis.revision.capabilities,
            )
        ) {
            acceptedRevision = null
        } else {
            acceptedRevision = nextAnalysis.revision
        }
        editor.contentComponent.repaint()
    }

    public fun caretMoved(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (!options.analysisCapabilities().activePair) return
        val currentAnalysis = analysis
        if (currentAnalysis == null || !isCurrent(currentAnalysis)) {
            updateProvisional(null)
            return
        }

        val pair = currentAnalysis.activePairAt(caretOffset())
        if (pair == activePair) return
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let(currentAnalysis::guideFor),
            change = null,
        )
        editor.contentComponent.repaint()
    }

    public fun documentChanged(
        change: DocumentChange,
        resolveImmediately: Boolean = true,
    ): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        discardStaleAnalysis()
        updateProvisional(change, resolveImmediately)
    }

    public fun visibleAreaChanged(): Unit {
        assertEdt()
        if (disposed || editor.isDisposed) return
        if (discardPresentationFromReplacedHighlighter()) return
        val currentAnalysis = analysis ?: return
        if (!hasCurrentTokenAnalysis(currentAnalysis)) return
        val nextDecorations = VisibleTokenDecorationManager.replaceIfOutsideWindow(
            editor,
            tokenDecorationState,
            currentAnalysis,
            visibleRangeProvider(editor),
            options,
        ) ?: return
        tokenDecorationState = nextDecorations
        editor.contentComponent.repaint()
    }

    public fun updateOptions(nextOptions: PluginOptions): Unit {
        updateOptions(nextOptions, resolveImmediately = true)
    }

    public fun updateOptions(
        nextOptions: PluginOptions,
        resolveImmediately: Boolean = true,
    ): Unit {
        updateOptions(nextOptions, resolveImmediately, refreshColors = false)
    }

    public fun updateOptions(
        nextOptions: PluginOptions,
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
        if (!retainAnalysisWhenInactive &&
            !nextOptions.analysisCapabilities().pairs
        ) {
            clear()
            acceptedRevision = currentRevision()
            editor.contentComponent.repaint()
            return
        }
        if (languagesChanged) {
            clear()
            updateProvisional(null, resolveImmediately)
            return
        }
        val requiredCapabilities = options.analysisCapabilities()
        val currentFileType = editorFileType(editor)
        val currentAnalysis = analysis?.takeIf { candidate ->
            candidate.revision.satisfiesCurrent(
                editor,
                currentFileType,
                requiredCapabilities,
                options.disabledLanguageIds,
            )
        }
        val releasePairGraph = currentAnalysis?.let { candidate ->
            shouldReleasePairGraph(requiredCapabilities, candidate.revision.capabilities)
        } == true
        tokenDecorationState = updateTokenPresentation(
            previousOptions,
            currentAnalysis,
            refreshColors,
        )

        val pair = currentAnalysis
            ?.takeIf { requiredCapabilities.activePair }
            ?.activePairAt(caretOffset())
            ?: adjustedActivePair()
        replaceActive(
            pair = pair,
            indexedGuide = pair?.let { currentAnalysis?.guideFor(it) },
            change = null,
        )
        if (pair == null &&
            currentAnalysis == null &&
            options.analysisCapabilities().activePair
        ) {
            updateProvisional(null, resolveImmediately)
            return
        }
        if (releasePairGraph) {
            acceptedRevision = null
        } else if (currentAnalysis != null) {
            acceptedRevision = currentAnalysis.revision
        }
        editor.contentComponent.repaint()
    }

    private fun updateTokenPresentation(
        previousOptions: PluginOptions,
        currentAnalysis: AnalysisResult?,
        refreshColors: Boolean,
    ): VisibleTokenDecorations {
        val wasVisible = previousOptions.enabled && previousOptions.colorBracketTokens
        val isVisible = options.enabled && options.colorBracketTokens
        return when {
            wasVisible && !isVisible -> VisibleTokenDecorationManager.updateAttributes(
                editor,
                tokenDecorationState,
                options,
            )
            !wasVisible && isVisible && currentAnalysis != null ->
                VisibleTokenDecorationManager.replace(
                    editor,
                    tokenDecorationState,
                    currentAnalysis,
                    visibleRangeProvider(editor),
                    options,
                )
            isVisible &&
                (refreshColors || previousOptions.levelBaseColors != options.levelBaseColors) ->
                VisibleTokenDecorationManager.updateAttributes(
                    editor,
                    tokenDecorationState,
                    options,
                )
            else -> tokenDecorationState
        }
    }

    public fun dispose(): Unit {
        assertEdt()
        if (disposed) return
        disposed = true
        clear()
    }

    private fun clear() {
        assertEdt()
        acceptedRevision = null
        analysis = null
        clearActive(preserveGuide = false)
        VisibleTokenDecorationManager.dispose(tokenDecorationState)
        tokenDecorationState = VisibleTokenDecorations.EMPTY
    }

    private fun updateProvisional(
        change: DocumentChange?,
        resolveImmediately: Boolean = true,
    ) {
        if (!options.analysisCapabilities().activePair) {
            val hadActivePresentation =
                activeGuideState != null || activePairHighlightState.isNotEmpty()
            clearActive(preserveGuide = false)
            if (hadActivePresentation) editor.contentComponent.repaint()
            return
        }
        if (discardPresentationFromReplacedHighlighter()) return
        val adjustedPair = adjustedActivePair()
        val caretOffset = caretOffset()
        val resolution = if (resolveImmediately) {
            engine.resolveActivePair(
                ActivePairRequest(
                    editor = editor,
                    fileType = editorFileType(editor),
                    caretOffset = caretOffset,
                    disabledLanguageIds = options.disabledLanguageIds,
                ),
            )
        } else {
            ActivePairResult.Incomplete
        }
        val pair = when (resolution) {
            is ActivePairResult.Complete ->
                resolution.pair?.withDepthHint(adjustedPair)
            ActivePairResult.Incomplete -> adjustedPair
        }
        if (pair?.contains(caretOffset) != true) {
            clearActive(preserveGuide = false)
            editor.contentComponent.repaint()
            return
        }

        if (pair.hasDifferentRangeFrom(adjustedPair)) {
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

    /** Keeps inactive split editors coherent without multiplying the resolver budget. */
    private fun refreshAdjustedPair(pair: BracketPair) {
        val previousGuide = currentGuide()
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
    }

    private fun refreshProvisionalPair(pair: BracketPair, change: DocumentChange?) {
        val previousGuide = currentGuide()
        val currentAnchorLine = activeAnchorLine()
        val guide = createGuide(pair, null, previousGuide, currentAnchorLine, change)
        updateGuide(guide)
        updateAnchor(guide)
        activePair = pair
    }

    private fun replaceActive(
        pair: BracketPair?,
        indexedGuide: BracketGuide?,
        change: DocumentChange?,
    ) {
        val previousGuide = currentGuide()
        val currentAnchorLine = activeAnchorLine()
        clearActive(preserveGuide = true)
        if (pair == null || !options.enabled ||
            (!options.showsGuide && !options.showsActivePair) ||
            !pair.hasWellFormedTokenRange(editor.document.textLength)
        ) {
            activeGuideState?.dispose()
            activeGuideState = null
            return
        }

        val guide = createGuide(
            pair,
            indexedGuide,
            previousGuide,
            currentAnchorLine,
            change,
        )
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
        activePairHighlightState = ActivePairDecoration.addPairHighlights(editor, pair, options)
    }

    private fun updateGuide(guide: BracketGuide?) {
        activeGuideState = if (guide == null) {
            activeGuideState?.dispose()
            null
        } else {
            ActivePairDecoration.addGuide(editor, guide, options, activeGuideState)
        }
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
        return indexedGuide ?: ActiveGuidePositionResolver.resolve(
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
        for (highlighter in activePairHighlightState) {
            if (highlighter.isValid) highlighter.dispose()
        }
        activePairHighlightState = emptyList()
        if (!preserveGuide) {
            activeGuideState?.dispose()
            activeGuideState = null
        }
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

    private fun currentGuide(): BracketGuide? =
        ActivePairDecoration.guideOf(activeGuideState)

    private fun discardPresentationFromReplacedHighlighter(): Boolean {
        if (analysisHighlighterIdentity ==
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
        val requiredCapabilities = options.analysisCapabilities()
        val currentFileType = editorFileType(editor)
        if (analysis?.revision?.satisfiesCurrent(
                editor,
                currentFileType,
                requiredCapabilities,
                options.disabledLanguageIds,
            ) == false
        ) {
            analysis = null
        }
        if (acceptedRevision?.satisfiesCurrent(
                editor,
                currentFileType,
                requiredCapabilities,
                options.disabledLanguageIds,
            ) == false
        ) {
            acceptedRevision = null
        }
    }

    private fun currentRevision(): AnalysisRevision = AnalyzeRequest(
        editor = editor,
        fileType = editorFileType(editor),
        capabilities = options.analysisCapabilities(),
        disabledLanguageIds = options.disabledLanguageIds,
    ).revision

    private fun isCurrent(candidate: AnalysisResult): Boolean =
        candidate.revision.satisfiesCurrent(
            editor,
            editorFileType(editor),
            options.analysisCapabilities(),
            options.disabledLanguageIds,
        )

    private fun hasCurrentTokenAnalysis(candidate: AnalysisResult): Boolean {
        if (!options.analysisCapabilities().tokens) return false
        return candidate.revision.satisfiesCurrent(
            editor,
            editorFileType(editor),
            TOKEN_ANALYSIS_CAPABILITIES,
            options.disabledLanguageIds,
        )
    }

    private fun shouldReleasePairGraph(
        required: AnalysisCapabilities,
        provided: AnalysisCapabilities,
    ): Boolean = !retainAnalysisWhenInactive &&
        required.tokens &&
        !required.activePair &&
        provided.activePair

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    private fun BracketPair.contains(offset: Int): Boolean =
        offset > openOffset && offset.toLong() < closeOffset.toLong() + closeTokenLength

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

    public companion object {
        private val KEY = Key.create<EditorGuideSession>("bracket.pair.guides.editor.session")

        public fun install(
            editor: Editor,
            engine: BracketEngine,
            visibleRangeProvider: (Editor) -> TextRange,
        ): EditorGuideSession {
            assertEdt()
            val existing = editor.getUserData(KEY)
            if (existing != null) return existing
            return EditorGuideSession(
                editor,
                engine,
                visibleRangeProvider,
                PluginSettings.getInstance().options,
                retainAnalysisWhenInactive = false,
            ).also {
                editor.putUserData(KEY, it)
            }
        }

        @TestOnly
        public fun detached(
            editor: Editor,
            options: PluginOptions,
            visibleRangeProvider: (Editor) -> TextRange,
        ): EditorGuideSession {
            assertEdt()
            return EditorGuideSession(
                editor,
                bracketEngine(),
                visibleRangeProvider,
                options,
                retainAnalysisWhenInactive = true,
            )
        }

        /** The only session query allowed from a background highlighting pass. */
        public fun hasAcceptedAnalysis(editor: Editor, required: AnalysisRevision): Boolean =
            editor.getUserData(KEY)?.acceptedRevision?.satisfies(required) == true

        public fun get(editor: Editor): EditorGuideSession? = editor.getUserData(KEY)

        public fun dispose(editor: Editor): Unit {
            val application = ApplicationManager.getApplication()
            if (!application.isDisposed) assertEdt()
            val session = editor.getUserData(KEY)
            editor.putUserData(KEY, null)
            if (application.isDisposed && !application.isDispatchThread) {
                session?.acceptedRevision = null
                return
            }
            session?.dispose()
        }

        private fun assertEdt() {
            ApplicationManager.getApplication().assertIsDispatchThread()
        }
    }
}

private val TOKEN_ANALYSIS_CAPABILITIES = AnalysisCapabilities(
    tokens = true,
    activePair = false,
    guidePosition = false,
)

private fun bracketEngine(): BracketEngine = service()

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
