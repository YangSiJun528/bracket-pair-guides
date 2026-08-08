package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.sijunyang.bracketpairguides.analysis.api.AnalysisResult
import com.sijunyang.bracketpairguides.analysis.api.AnalysisRevision
import com.sijunyang.bracketpairguides.analysis.api.AnalyzeRequest
import com.sijunyang.bracketpairguides.analysis.api.BracketEngine
import com.sijunyang.bracketpairguides.editor.EditorGuideEventRouter
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.analysisCapabilities
import com.sijunyang.bracketpairguides.settings.PluginSettings
import org.jetbrains.annotations.TestOnly

/**
 * Collects an immutable result in the platform highlighting lifecycle.
 * Platform-managed passes may be constructed off EDT and are collected off EDT,
 * then applied on EDT. The engine remains synchronous and observes the pass's
 * [ProgressIndicator].
 */
internal class GuideLineHighlightingPass private constructor(
    project: Project,
    private val editor: Editor,
    private val fileType: FileType,
    private val engineProvider: () -> BracketEngine,
    private val visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
) : TextEditorHighlightingPass(project, editor.document, false) {
    private var collected: AnalysisResult? = null
    private var collectedRevision: AnalysisRevision? = null

    init {
        if (ApplicationManager.getApplication().isDispatchThread && !editor.isDisposed) {
            installSession(engineProvider())
        }
    }

    @TestOnly
    public constructor(project: Project, editor: Editor) : this(
        project = project,
        editor = editor,
        fileType = editorFileType(editor),
        engineProvider = ::bracketEngine,
    )

    @TestOnly
    public constructor(
        project: Project,
        editor: Editor,
        engine: BracketEngine,
        visibleRangeProvider: (Editor) -> TextRange = Editor::calculateVisibleRange,
        fileType: FileType = editorFileType(editor),
    ) : this(
        project = project,
        editor = editor,
        fileType = fileType,
        engineProvider = { engine },
        visibleRangeProvider = visibleRangeProvider,
    )

    public constructor(project: Project, editor: Editor, fileType: FileType) : this(
        project = project,
        editor = editor,
        fileType = fileType,
        engineProvider = ::bracketEngine,
    )

    public override fun doCollectInformation(progress: ProgressIndicator): Unit {
        collected = null
        collectedRevision = null
        val request = currentRequest()
        collectedRevision = request.revision
        if (EditorGuideSession.hasAcceptedAnalysis(editor, request.revision)) return
        collected = engineProvider().analyze(request, progress)
    }

    public override fun doApplyInformationToEditor(): Unit {
        val result = collected
        val passRevision = collectedRevision
        collected = null
        collectedRevision = null
        if (editor.isDisposed || passRevision?.let(::isCurrent) == false) return
        val engine = engineProvider()
        val session = installSession(engine) ?: return
        if (passRevision != null) {
            session.updateDependenciesIfCurrent(
                engine = engine,
                rangeProvider = visibleRangeProvider,
                passRevision = passRevision,
            )
        }
        if (result != null) session.accept(result)
    }

    private fun currentRequest(): AnalyzeRequest {
        val options = PluginSettings.getInstance().options
        return AnalyzeRequest(
            editor = editor,
            fileType = fileType,
            capabilities = options.analysisCapabilities(),
            disabledLanguageIds = options.disabledLanguageIds,
        )
    }

    private fun isCurrent(passRevision: AnalysisRevision): Boolean {
        val options = PluginSettings.getInstance().options
        return passRevision.satisfiesCurrent(
            editor,
            editorFileType(editor),
            options.analysisCapabilities(),
            options.disabledLanguageIds,
        )
    }

    private fun installSession(engine: BracketEngine): EditorGuideSession? {
        if (editor.isDisposed) return null
        EditorGuideEventRouter.ensureInitialized()
        return EditorGuideSession.install(
            editor = editor,
            engine = engine,
            visibleRangeProvider = visibleRangeProvider,
        )
    }
}

private fun bracketEngine(): BracketEngine = service()

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
