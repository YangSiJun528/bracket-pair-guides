package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator

internal data class AnalysisCapabilities(
    val tokens: Boolean,
    val activePair: Boolean,
    val guidePosition: Boolean,
) {
    val pairs: Boolean
        get() = tokens || activePair

    fun includes(required: AnalysisCapabilities): Boolean =
        (!required.tokens || tokens) &&
            (!required.activePair || activePair) &&
            (!required.guidePosition || guidePosition)

    companion object {
        fun from(options: PluginOptions): AnalysisCapabilities {
            val enabled = options.enabled
            val activePair = enabled && (options.showsGuide || options.showsActivePair)
            return AnalysisCapabilities(
                tokens = enabled && options.colorBracketTokens,
                activePair = activePair,
                guidePosition = activePair && options.showsGuide,
            )
        }

        val PREVIEW = AnalysisCapabilities(
            tokens = true,
            activePair = true,
            guidePosition = true,
        )
    }
}

internal data class AnalysisStamp(
    val documentStamp: Long,
    val tabSize: Int,
    val highlighterIdentity: Int,
    val capabilities: AnalysisCapabilities,
    val disabledLanguageIds: Set<String> = emptySet(),
) {
    fun satisfies(required: AnalysisStamp): Boolean =
        documentStamp == required.documentStamp &&
            (!required.capabilities.guidePosition || tabSize == required.tabSize) &&
            highlighterIdentity == required.highlighterIdentity &&
            disabledLanguageIds == required.disabledLanguageIds &&
            capabilities.includes(required.capabilities)

    companion object {
        fun current(
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

internal data class AnalysisSnapshot(
    val stamp: AnalysisStamp,
    val pairs: List<BracketPair>,
    val tokenIndex: BracketTokenIndex,
    val activeIndex: ActiveBracketPairIndex,
    val positionIndex: GuidePositionIndex?,
)

internal object AnalysisSnapshotBuilder {
    fun build(
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
        val positionIndex = if (
            stamp.capabilities.guidePosition &&
            containsMultilinePair(pairs, progress::checkCanceled)
        ) {
            // Oversized documents intentionally return null here. The session
            // then uses ActiveGuidePositionResolver's bounded on-demand scan.
            GuidePositionIndex.from(
                document = editor.document,
                tabSize = stamp.tabSize,
                progress = progress,
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

    internal fun containsMultilinePair(
        pairs: List<BracketPair>,
        checkCanceled: () -> Unit = {},
    ): Boolean {
        var index = 0
        for (pair in pairs) {
            if (index and CANCELLATION_MASK == 0) checkCanceled()
            if (pair.openLine != pair.closeLine) return true
            index++
        }
        checkCanceled()
        return false
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
