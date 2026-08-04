package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/** Collects an immutable snapshot in the platform highlighting lifecycle. */
internal class GuideLineHighlightingPass(
    project: Project,
    private val editor: Editor,
    private val pairProvider: BracketPairProvider,
    visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
    activePairResolver: ActiveBracketPairResolver = ActiveBracketPairResolver.NONE,
) : TextEditorHighlightingPass(project, editor.document, false) {
    private val session = EditorGuideSession.install(
        editor,
        activePairResolver,
        visibleRangeProvider,
    )
    private var collected: AnalysisSnapshot? = null

    init {
        EditorGuideEventRouter.ensureInitialized()
    }

    constructor(project: Project, editor: Editor) : this(
        project = project,
        editor = editor,
        pairProvider = BracketPairAnalyzer(
            editor = editor,
            isLanguageEnabled = ::isConfiguredLanguageEnabled,
        ),
        activePairResolver = EditorHighlighterActiveBracketPairResolver(
            fileType = FileDocumentManager.getInstance().getFile(editor.document)?.fileType
                ?: PlainTextFileType.INSTANCE,
            isLanguageEnabled = ::isConfiguredLanguageEnabled,
        ),
    )

    constructor(project: Project, editor: Editor, fileType: FileType) : this(
        project = project,
        editor = editor,
        pairProvider = BracketPairAnalyzer(
            editor = editor,
            fileType = fileType,
            isLanguageEnabled = ::isConfiguredLanguageEnabled,
        ),
        activePairResolver = EditorHighlighterActiveBracketPairResolver(
            fileType = fileType,
            isLanguageEnabled = ::isConfiguredLanguageEnabled,
        ),
    )

    override fun doCollectInformation(progress: ProgressIndicator) {
        collected = null
        val options = PluginSettings.getInstance().options
        val capabilities = AnalysisCapabilities.from(options)
        val stamp = AnalysisStamp.current(
            editor,
            capabilities,
            options.disabledLanguageIds,
        )
        if (session.hasSnapshot(stamp)) return
        collected = AnalysisSnapshotBuilder.build(editor, pairProvider, stamp, progress)
    }

    override fun doApplyInformationToEditor() {
        val snapshot = collected ?: return
        collected = null
        session.accept(snapshot)
    }
}

private fun isConfiguredLanguageEnabled(capabilityId: String): Boolean =
    PluginSettings.getInstance().options.isLanguageEnabled(capabilityId)
