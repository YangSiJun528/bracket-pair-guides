package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.api.AnalysisResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisRevision
import com.sijunyang.bracketpairguides.analysis.api.BracketGuide
import com.sijunyang.bracketpairguides.analysis.api.BracketPair
import com.sijunyang.bracketpairguides.analysis.api.VisibleTokens
import com.sijunyang.bracketpairguides.analysis.index.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.index.BracketTokenIndex
import com.sijunyang.bracketpairguides.analysis.index.GuidePositionIndex
import org.jetbrains.annotations.TestOnly

/** Internal immutable result backed by the engine's compact query indexes. */
internal class AnalysisSnapshot(
    public override val revision: AnalysisRevision,
    internal val pairs: List<BracketPair>,
    internal val tokenIndex: BracketTokenIndex,
    internal val activeIndex: ActiveBracketPairIndex,
    internal val positionIndex: GuidePositionIndex?,
) : AnalysisResult {
    public override fun activePairAt(caretOffset: Int): BracketPair? =
        pairs.getOrNull(activeIndex.activePairIndex(caretOffset))

    public override fun guideFor(pair: BracketPair): BracketGuide? =
        positionIndex?.guideForOrNull(pair)

    public override fun visibleTokens(
        range: TextRange,
        focusOffset: Int,
        limit: Int,
    ): VisibleTokens {
        require(limit > 0) { "Visible token limit must be positive" }

        val firstCandidate = tokenIndex.firstIndexInRange(range.startOffset)
        val lastCandidate = tokenIndex.firstIndexAtOrAfter(range.endOffset)
        val candidateCount = lastCandidate - firstCandidate
        if (candidateCount <= limit) {
            return VisibleTokenView(
                tokenIndex = tokenIndex,
                firstIndex = firstCandidate,
                afterLastIndex = lastCandidate,
                isCapped = false,
                stableFocusStartOffset = range.startOffset,
                stableFocusEndOffset = range.endOffset,
            )
        }

        val focusIndex = tokenIndex.firstIndexAtOrAfter(focusOffset)
            .coerceIn(firstCandidate, lastCandidate)
        var firstSelected = (focusIndex - limit / 2).coerceAtLeast(firstCandidate)
        var lastSelected = minOf(
            firstSelected.toLong() + limit,
            lastCandidate.toLong(),
        ).toInt()
        firstSelected = (lastSelected - limit).coerceAtLeast(firstCandidate)

        val selectedFocusIndex = focusIndex.coerceIn(firstSelected, lastSelected - 1)
        val tolerance = limit / 4
        val stableFirstIndex = (selectedFocusIndex - tolerance)
            .coerceAtLeast(firstSelected)
        val stableAfterLastIndex = minOf(
            selectedFocusIndex.toLong() + tolerance + 1L,
            lastSelected.toLong(),
        ).toInt()
        return VisibleTokenView(
            tokenIndex = tokenIndex,
            firstIndex = firstSelected,
            afterLastIndex = lastSelected,
            isCapped = true,
            stableFocusStartOffset = if (stableFirstIndex == firstCandidate) {
                range.startOffset
            } else {
                tokenIndex.offsetAt(stableFirstIndex)
            },
            stableFocusEndOffset = if (stableAfterLastIndex == lastCandidate) {
                range.endOffset
            } else {
                tokenIndex.offsetAt(stableAfterLastIndex)
            },
        )
    }
}

private class VisibleTokenView(
    private val tokenIndex: BracketTokenIndex,
    private val firstIndex: Int,
    private val afterLastIndex: Int,
    public override val isCapped: Boolean,
    public override val stableFocusStartOffset: Int,
    public override val stableFocusEndOffset: Int,
) : VisibleTokens {
    public override val size: Int
        get() = afterLastIndex - firstIndex

    public override fun offsetAt(index: Int): Int =
        tokenIndex.offsetAt(globalIndex(index))

    public override fun lengthAt(index: Int): Int =
        tokenIndex.lengthAt(globalIndex(index))

    public override fun depthAt(index: Int): Int =
        tokenIndex.depthAt(globalIndex(index))

    private fun globalIndex(index: Int): Int {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("Token index $index is outside 0 until $size")
        }
        return firstIndex + index
    }
}

internal object AnalysisSnapshotBuilder {
    public fun build(
        editor: Editor,
        pairProvider: BracketPairProvider,
        revision: AnalysisRevision,
        progress: ProgressIndicator,
    ): AnalysisSnapshot {
        if (!revision.capabilities.pairs) return empty(revision)

        val pairs = pairProvider.collect(progress)
        if (pairs.isEmpty()) return empty(revision)

        val activeIndex = if (revision.capabilities.activePair) {
            ActiveBracketPairIndex.build(pairs, progress::checkCanceled)
        } else {
            ActiveBracketPairIndex.build(emptyList())
        }
        // Build the larger active index before retaining the token index. This
        // avoids overlapping its peak workspace with 16 bytes per pair of
        // stable token-index payload.
        val tokenIndex = if (revision.capabilities.tokens) {
            if (revision.capabilities.activePair) {
                BracketTokenIndex.build(pairs, progress::checkCanceled)
            } else {
                BracketTokenIndex.buildDetached(pairs, progress::checkCanceled)
            }
        } else {
            BracketTokenIndex.build(emptyList())
        }
        val guideLineRange = if (revision.capabilities.guidePosition) {
            multilineGuideRange(
                pairs,
                editor.document.textLength,
                editor.document.lineCount,
                progress::checkCanceled,
            )
        } else {
            null
        }
        val positionIndex = if (guideLineRange != null) {
            // Oversized guide spans intentionally return null here. The plugin
            // then uses its bounded on-demand provisional scan.
            GuidePositionIndex.from(
                document = editor.document,
                tabSize = revision.tabSize,
                progress = progress,
                indexedLineRange = guideLineRange,
            )
        } else {
            null
        }
        return AnalysisSnapshot(
            revision = revision,
            pairs = pairs.takeIf { revision.capabilities.activePair }.orEmpty(),
            tokenIndex = tokenIndex,
            activeIndex = activeIndex,
            positionIndex = positionIndex,
        )
    }

    @TestOnly
    internal fun multilineGuideRange(
        pairs: List<BracketPair>,
        documentLength: Int,
        documentLineCount: Int,
        checkCanceled: () -> Unit = {},
    ): IntRange? {
        if (documentLineCount <= 0) return null
        val lastDocumentLine = documentLineCount - 1
        var firstGuideLine = Int.MAX_VALUE
        var lastGuideLine = -1
        var index = 0
        for (pair in pairs) {
            if (index and CANCELLATION_MASK == 0) checkCanceled()
            if (pair.hasWellFormedTokenRange(documentLength) &&
                pair.openLine >= 0 &&
                pair.openLine < pair.closeLine &&
                pair.closeLine <= lastDocumentLine
            ) {
                val firstLine = pair.openLine + 1
                val lastLine = pair.closeLine
                firstGuideLine = minOf(firstGuideLine, firstLine)
                lastGuideLine = maxOf(lastGuideLine, lastLine)
            }
            index++
        }
        checkCanceled()
        return if (lastGuideLine < 0) null else firstGuideLine..lastGuideLine
    }

    private fun empty(revision: AnalysisRevision): AnalysisSnapshot = AnalysisSnapshot(
        revision = revision,
        pairs = emptyList(),
        tokenIndex = BracketTokenIndex.build(emptyList()),
        activeIndex = ActiveBracketPairIndex.build(emptyList()),
        positionIndex = null,
    )

    private const val CANCELLATION_MASK = 0xFF
}
