package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.renderer.BracketGuide
import com.sijunyang.bracketpairguides.renderer.GuidePositionIndex
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator

/** Injectable boundary between preview recognition and preview decoration. */
internal fun interface PreviewPairProviderFactory {
    fun create(editor: Editor, fileType: FileType): BracketPairProvider
}

internal data class PreviewRecognitionResult(
    val pairs: List<BracketPair>,
    val guides: List<BracketGuide?>,
    val activeIndex: ActiveBracketPairIndex,
) {
    companion object {
        val EMPTY = PreviewRecognitionResult(
            pairs = emptyList(),
            guides = emptyList(),
            activeIndex = ActiveBracketPairIndex.build(emptyList()),
        )
    }
}

/** Builds a document-stamped, caret-independent recognition snapshot. */
internal class PreviewRecognizer(
    private val providerFactory: PreviewPairProviderFactory =
        PreviewPairProviderFactory(::BracketPairAnalyzer),
) {
    fun recognize(
        editor: Editor,
        fileType: FileType,
        progress: ProgressIndicator,
    ): PreviewRecognitionResult {
        val pairs = providerFactory.create(editor, fileType).collect(progress)
        if (pairs.isEmpty()) return PreviewRecognitionResult.EMPTY

        val positionIndex = if (pairs.any { it.openLine != it.closeLine }) {
            GuidePositionIndex.from(
                document = editor.document,
                tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1),
                progress = progress,
            )
        } else {
            null
        }
        val documentLength = editor.document.textLength
        val guides = pairs.map { pair ->
            val closeEnd = pair.closeOffset.toLong() + pair.closeTokenLength
            when {
                pair.openOffset < 0 -> null
                pair.openOffset.toLong() >= closeEnd -> null
                closeEnd > documentLength -> null
                pair.openLine == pair.closeLine -> BracketGuide(pair, guideColumn = 0)
                else -> checkNotNull(positionIndex).guideFor(pair)
            }
        }
        return PreviewRecognitionResult(
            pairs = pairs,
            guides = guides,
            activeIndex = ActiveBracketPairIndex.build(
                pairs,
                progress::checkCanceled,
            ),
        )
    }
}
