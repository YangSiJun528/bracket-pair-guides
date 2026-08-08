package com.sijunyang.bracketpairguides.editor.highlighting

import com.sijunyang.bracketpairguides.analysis.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analysis.BracketPairProvider
import com.sijunyang.bracketpairguides.analysis.ActiveBracketPairResolver
import com.sijunyang.bracketpairguides.analysis.AnalysisCapabilities
import com.sijunyang.bracketpairguides.analysis.AnalysisSnapshot
import com.sijunyang.bracketpairguides.analysis.AnalysisSnapshotBuilder
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.EditorHighlighterActiveBracketPairResolver
import com.sijunyang.bracketpairguides.editor.EditorGuideEventRouter
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.analysisCapabilities
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
import org.jetbrains.annotations.TestOnly

/**
 * Collects an immutable snapshot in the platform highlighting lifecycle.
 * Platform-managed passes may be constructed off EDT and are collected off EDT,
 * then applied on EDT.
 */
internal class GuideLineHighlightingPass private constructor(
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

    @TestOnly
    public constructor(project: Project, editor: Editor) : this(
        project,
        editor,
        editorFileType(editor),
    )

    @TestOnly
    public constructor(
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

    public constructor(project: Project, editor: Editor, fileType: FileType) : this(
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

    public override fun doCollectInformation(progress: ProgressIndicator): Unit {
        collected = null
        collectedStamp = null
        val options = PluginSettings.getInstance().options
        val capabilities = options.analysisCapabilities()
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

    public override fun doApplyInformationToEditor(): Unit {
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
                options.analysisCapabilities(),
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

    public companion object {
        @TestOnly
        public fun forTest(
            project: Project,
            editor: Editor,
            pairProviderFactory: (Set<String>) -> BracketPairProvider,
            visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
            activePairResolver: ActiveBracketPairResolver = ActiveBracketPairResolver.NONE,
        ): GuideLineHighlightingPass = GuideLineHighlightingPass(
            project = project,
            editor = editor,
            pairProviderFactory = pairProviderFactory,
            visibleRangeProvider = visibleRangeProvider,
            activePairResolver = activePairResolver,
        )
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE

private fun isConfiguredLanguageEnabled(capabilityId: String): Boolean =
    PluginSettings.getInstance().options.isLanguageEnabled(capabilityId)
