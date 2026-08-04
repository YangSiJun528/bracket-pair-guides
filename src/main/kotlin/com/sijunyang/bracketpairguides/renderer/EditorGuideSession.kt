package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
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
) {
    private var disposed = false
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

    fun updateDependencies(
        resolver: ActiveBracketPairResolver,
        rangeProvider: (Editor) -> TextRange,
    ) {
        activePairResolver = resolver
        visibleRangeProvider = rangeProvider
    }

    fun hasSnapshot(stamp: AnalysisStamp): Boolean = snapshot?.stamp?.satisfies(stamp) == true

    fun accept(nextSnapshot: AnalysisSnapshot) {
        if (disposed || editor.isDisposed || !nextSnapshot.stamp.satisfies(currentStamp())) return

        snapshot = nextSnapshot
        tokenDecorations = VisibleTokenDecorationManager.replace(
            editor,
            tokenDecorations,
            nextSnapshot.tokenIndex,
            visibleRangeProvider(editor),
            options,
        )
        val pairIndex = nextSnapshot.activeIndex.activePairIndex(caretOffset())
        replaceActive(
            pair = nextSnapshot.pairs.getOrNull(pairIndex),
            pairIndex = pairIndex,
            positionIndex = nextSnapshot.positionIndex,
            change = null,
        )
        editor.contentComponent.repaint()
    }

    fun caretMoved() {
        if (disposed || editor.isDisposed) return
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

    fun documentChanged(change: DocumentChange) {
        if (disposed || editor.isDisposed) return
        updateProvisional(change)
    }

    fun visibleAreaChanged() {
        if (disposed || editor.isDisposed) return
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
        if (disposed || editor.isDisposed) return
        options = nextOptions
        val currentSnapshot = snapshot
        tokenDecorations = if (currentSnapshot != null && isCurrent(currentSnapshot)) {
            VisibleTokenDecorationManager.replace(
                editor,
                tokenDecorations,
                currentSnapshot.tokenIndex,
                visibleRangeProvider(editor),
                options,
            )
        } else {
            VisibleTokenDecorationManager.updateAttributes(editor, tokenDecorations, options)
        }

        val pairIndex = if (currentSnapshot != null && isCurrent(currentSnapshot)) {
            currentSnapshot.activeIndex.activePairIndex(caretOffset())
        } else {
            ActiveBracketPairIndex.NO_PAIR
        }
        val pair = if (pairIndex == ActiveBracketPairIndex.NO_PAIR) {
            adjustedActivePair()
        } else {
            currentSnapshot?.pairs?.getOrNull(pairIndex)
        }
        replaceActive(
            pair = pair,
            pairIndex = pairIndex,
            positionIndex = currentSnapshot?.positionIndex
                ?.takeIf { isCurrent(currentSnapshot) },
            change = null,
        )
        editor.contentComponent.repaint()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        clear()
    }

    fun clear() {
        snapshot = null
        clearActive(preserveGuide = false)
        VisibleTokenDecorationManager.dispose(tokenDecorations)
        tokenDecorations = VisibleTokenDecorations.EMPTY
    }

    private fun updateProvisional(change: DocumentChange?) {
        val adjustedPair = adjustedActivePair()
        val caretOffset = caretOffset()
        val pair = when (
            val resolution = activePairResolver.findInnermost(editor, caretOffset)
        ) {
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
        } else {
            refreshProvisionalPair(pair, change)
        }
        editor.contentComponent.repaint()
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
            !pair.isValid(editor.document.textLength)
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

    private fun currentStamp(): AnalysisStamp = AnalysisStamp.current(
        editor,
        AnalysisCapabilities.from(options),
    )

    private fun isCurrent(candidate: AnalysisSnapshot): Boolean =
        candidate.stamp.satisfies(currentStamp())

    private fun caretOffset(): Int = editor.caretModel.primaryCaret.offset

    private fun BracketPair.contains(offset: Int): Boolean =
        offset > openOffset && offset < closeOffset + closeTokenLength

    private fun BracketPair.isValid(documentLength: Int): Boolean {
        val closeEnd = closeOffset.toLong() + closeTokenLength
        return openOffset >= 0 && openOffset.toLong() < closeEnd && closeEnd <= documentLength
    }

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
            val existing = editor.getUserData(KEY)
            if (existing != null) {
                existing.updateDependencies(resolver, visibleRangeProvider)
                return existing
            }
            return EditorGuideSession(
                editor,
                resolver,
                visibleRangeProvider,
                PluginSettings.getInstance().options,
            ).also {
                editor.putUserData(KEY, it)
            }
        }

        fun detached(
            editor: Editor,
            options: PluginOptions,
            visibleRangeProvider: (Editor) -> TextRange,
        ): EditorGuideSession = EditorGuideSession(
            editor,
            ActiveBracketPairResolver.NONE,
            visibleRangeProvider,
            options,
        )

        fun get(editor: Editor): EditorGuideSession? = editor.getUserData(KEY)

        fun dispose(editor: Editor) {
            editor.getUserData(KEY)?.dispose()
            editor.putUserData(KEY, null)
        }
    }
}
