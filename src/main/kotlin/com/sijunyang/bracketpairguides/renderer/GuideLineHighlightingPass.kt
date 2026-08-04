package com.sijunyang.bracketpairguides.renderer

import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * Collects an immutable snapshot in the platform highlighting lifecycle.
 * Platform-managed passes may be constructed off EDT and are collected off EDT,
 * then applied on EDT.
 */
internal class GuideLineHighlightingPass(
    project: Project,
    private val editor: Editor,
    private val pairProviderFactory: (Set<String>) -> BracketPairProvider,
    private val visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
    private val activePairResolver: ActiveBracketPairResolver = ActiveBracketPairResolver.NONE,
) : TextEditorHighlightingPass(project, editor.document, false) {
    private var collected: AnalysisSnapshot? = null
    private var collectedStamp: AnalysisStamp? = null

    init {
        if (ApplicationManager.getApplication().isDispatchThread && !editor.isDisposed) {
            installSession()
        }
    }

    constructor(project: Project, editor: Editor) : this(
        project,
        editor,
        editorFileType(editor),
    )

    constructor(
        project: Project,
        editor: Editor,
        pairProvider: BracketPairProvider,
        visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
        activePairResolver: ActiveBracketPairResolver = ActiveBracketPairResolver.NONE,
    ) : this(
        project = project,
        editor = editor,
        pairProviderFactory = { pairProvider },
        visibleRangeProvider = visibleRangeProvider,
        activePairResolver = activePairResolver,
    )

    constructor(project: Project, editor: Editor, fileType: FileType) : this(
        project = project,
        editor = editor,
        pairProviderFactory = { disabledLanguageIds ->
            BracketPairAnalyzer(editor, fileType) { capabilityId ->
                capabilityId !in disabledLanguageIds
            }
        },
        activePairResolver = EditorHighlighterActiveBracketPairResolver(
            fileType = fileType,
            isLanguageEnabled = ::isConfiguredLanguageEnabled,
        ),
    )

    override fun doCollectInformation(progress: ProgressIndicator) {
        collected = null
        collectedStamp = null
        val options = PluginSettings.getInstance().options
        val capabilities = AnalysisCapabilities.from(options)
        val stamp = AnalysisStamp.current(
            editor,
            capabilities,
            options.disabledLanguageIds,
        )
        collectedStamp = stamp
        if (EditorGuideSession.hasAcceptedAnalysis(editor, stamp)) return
        val pairProvider = pairProviderFactory(stamp.disabledLanguageIds)
        collected = AnalysisSnapshotBuilder.build(editor, pairProvider, stamp, progress)
    }

    override fun doApplyInformationToEditor() {
        val snapshot = collected
        val passStamp = collectedStamp
        collected = null
        collectedStamp = null
        if (editor.isDisposed || passStamp?.let(::isCurrent) == false) return
        val session = installSession() ?: return
        if (passStamp != null) {
            session.updateDependenciesIfCurrent(
                activePairResolver,
                visibleRangeProvider,
                passStamp,
            )
        }
        if (snapshot != null) session.accept(snapshot)
    }

    private fun isCurrent(passStamp: AnalysisStamp): Boolean {
        val options = PluginSettings.getInstance().options
        return passStamp.satisfies(
            AnalysisStamp.current(
                editor,
                AnalysisCapabilities.from(options),
                options.disabledLanguageIds,
            ),
        )
    }

    private fun installSession(): EditorGuideSession? {
        if (editor.isDisposed) return null
        EditorGuideEventRouter.ensureInitialized()
        return EditorGuideSession.install(
            editor,
            activePairResolver,
            visibleRangeProvider,
        )
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE

private fun isConfiguredLanguageEnabled(capabilityId: String): Boolean =
    PluginSettings.getInstance().options.isLanguageEnabled(capabilityId)
