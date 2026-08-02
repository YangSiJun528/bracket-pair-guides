package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.renderer.AnalysisCapabilities
import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshot
import com.sijunyang.bracketpairguides.renderer.AnalysisSnapshotBuilder
import com.sijunyang.bracketpairguides.renderer.AnalysisStamp
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator

/** Injectable boundary between preview recognition and preview decoration. */
internal fun interface PreviewPairProviderFactory {
    fun create(editor: Editor, fileType: FileType): BracketPairProvider
}

internal class PreviewRecognizer(
    private val providerFactory: PreviewPairProviderFactory,
) {
    fun recognize(
        editor: Editor,
        fileType: FileType,
        progress: ProgressIndicator,
    ): AnalysisSnapshot = AnalysisSnapshotBuilder.build(
        editor = editor,
        pairProvider = providerFactory.create(editor, fileType),
        stamp = AnalysisStamp.current(editor, AnalysisCapabilities.PREVIEW),
        progress = progress,
    )
}
