package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.sijunyang.bracketpairguides.analysis.index.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.analysis.index.BracketTokenIndex
import com.sijunyang.bracketpairguides.analysis.index.GuidePositionIndex
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

@ApiStatus.Internal
public data class AnalysisCapabilities(
    public val tokens: Boolean,
    public val activePair: Boolean,
    public val guidePosition: Boolean,
) {
    public val pairs: Boolean
        get() = tokens || activePair
}

private fun AnalysisCapabilities.includes(required: AnalysisCapabilities): Boolean =
    (!required.tokens || tokens) &&
        (!required.activePair || activePair) &&
        (!required.guidePosition || guidePosition)

@ApiStatus.Internal
public data class AnalysisStamp(
    public val documentStamp: Long,
    public val tabSize: Int,
    public val highlighterIdentity: Int,
    public val capabilities: AnalysisCapabilities,
    public val disabledLanguageIds: Set<String> = emptySet(),
) {
    public fun satisfies(required: AnalysisStamp): Boolean =
        documentStamp == required.documentStamp &&
            (!required.capabilities.guidePosition || tabSize == required.tabSize) &&
            highlighterIdentity == required.highlighterIdentity &&
            disabledLanguageIds == required.disabledLanguageIds &&
            capabilities.includes(required.capabilities)

    public companion object {
        public fun current(
            editor: Editor,
            capabilities: AnalysisCapabilities,
            disabledLanguageIds: Set<String> = emptySet(),
        ): AnalysisStamp = AnalysisStamp(
            documentStamp = editor.document.modificationStamp,
            tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
            highlighterIdentity = System.identityHashCode(editor.highlighter),
            capabilities = capabilities,
            disabledLanguageIds = disabledLanguageIds,
        )
    }
}

@ApiStatus.Internal
public data class AnalysisSnapshot(
    public val stamp: AnalysisStamp,
    public val pairs: List<BracketPair>,
    public val tokenIndex: BracketTokenIndex,
    public val activeIndex: ActiveBracketPairIndex,
    public val positionIndex: GuidePositionIndex?,
)

@ApiStatus.Internal
public object AnalysisSnapshotBuilder {
    public fun build(
        editor: Editor,
        pairProvider: BracketPairProvider,
        stamp: AnalysisStamp,
        progress: ProgressIndicator,
    ): AnalysisSnapshot {
        if (!stamp.capabilities.pairs) return empty(stamp)

        val pairs = pairProvider.collect(progress)
        if (pairs.isEmpty()) return empty(stamp)

        val activeIndex = if (stamp.capabilities.activePair) {
            ActiveBracketPairIndex.build(pairs, progress::checkCanceled)
        } else {
            ActiveBracketPairIndex.build(emptyList())
        }
        // Build the larger active index before retaining the token index. This
        // avoids overlapping its peak workspace with 16 bytes per pair of
        // stable token-index payload.
        val tokenIndex = if (stamp.capabilities.tokens) {
            if (stamp.capabilities.activePair) {
                BracketTokenIndex.build(pairs, progress::checkCanceled)
            } else {
                BracketTokenIndex.buildDetached(pairs, progress::checkCanceled)
            }
        } else {
            BracketTokenIndex.build(emptyList())
        }
        val guideLineRange = if (stamp.capabilities.guidePosition) {
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
            // Oversized guide spans intentionally return null here. The session
            // then uses ActiveGuidePositionResolver's bounded on-demand scan.
            GuidePositionIndex.from(
                document = editor.document,
                tabSize = stamp.tabSize,
                progress = progress,
                indexedLineRange = guideLineRange,
            )
        } else {
            null
        }
        return AnalysisSnapshot(
            stamp = stamp,
            pairs = pairs.takeIf { stamp.capabilities.activePair }.orEmpty(),
            tokenIndex = tokenIndex,
            activeIndex = activeIndex,
            positionIndex = positionIndex,
        )
    }

    @TestOnly
    public fun multilineGuideRange(
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

    private fun empty(stamp: AnalysisStamp): AnalysisSnapshot = AnalysisSnapshot(
        stamp = stamp,
        pairs = emptyList(),
        tokenIndex = BracketTokenIndex.build(emptyList()),
        activeIndex = ActiveBracketPairIndex.build(emptyList()),
        positionIndex = null,
    )

    private const val CANCELLATION_MASK = 0xFF
}
