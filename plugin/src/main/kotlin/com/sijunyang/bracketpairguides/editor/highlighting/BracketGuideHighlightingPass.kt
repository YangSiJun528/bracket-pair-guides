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
import com.sijunyang.bracketpairguides.analysis.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.BracketAnalysis
import com.sijunyang.bracketpairguides.editor.EditorGuideEvents
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.analysisCoverage
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

/**
 * Collects an immutable result in the platform highlighting lifecycle.
 * Platform-managed passes may be constructed off EDT and are collected off EDT,
 * then applied on EDT. The analysis remains synchronous and observes the pass's
 * [ProgressIndicator].
 */
internal class BracketGuideHighlightingPass(
    project: Project,
    private val editor: Editor,
    private val fileType: FileType,
    private val analyze: (AnalysisInput, ProgressIndicator) -> AnalysisOutcome,
    private val visibleRange: (Editor) -> TextRange = Editor::calculateVisibleRange,
) : TextEditorHighlightingPass(project, editor.document, false) {
    private var collected: AnalysisOutcome? = null
    private var collectedStamp: AnalysisStamp? = null

    init {
        if (ApplicationManager.getApplication().isDispatchThread && !editor.isDisposed) {
            installSession()
        }
    }

    constructor(project: Project, editor: Editor, fileType: FileType) : this(
        project = project,
        editor = editor,
        fileType = fileType,
        analyze = service<BracketAnalysis>()::analyze,
    )

    override fun doCollectInformation(progress: ProgressIndicator): Unit {
        collected = null
        collectedStamp = null
        val input = currentInput()
        collectedStamp = input.stamp
        if (EditorGuideSessions.hasAcceptedAnalysis(editor, input.stamp)) return
        collected = analyze(input, progress)
    }

    override fun doApplyInformationToEditor(): Unit {
        val result = collected
        val passStamp = collectedStamp
        collected = null
        collectedStamp = null
        if (editor.isDisposed || passStamp?.let { stamp ->
                when (result) {
                    is AnalysisOutcome.Unavailable -> isExactCurrent(stamp)
                    else -> isCurrent(stamp)
                }
            } == false
        ) {
            return
        }
        val session = installSession() ?: return
        if (passStamp != null) {
            session.updateDependenciesIfCurrent(
                visibleRange = visibleRange,
                passStamp = passStamp,
            )
        }
        when (result) {
            is AnalysisOutcome.Complete -> session.accept(result.snapshot)
            is AnalysisOutcome.Unavailable -> session.acceptUnavailable(result.stamp)
            null -> Unit
        }
    }

    private fun currentInput(): AnalysisInput {
        val options = BracketGuideSettings.getInstance().options
        return AnalysisInput(
            editor = editor,
            fileType = fileType,
            coverage = options.analysisCoverage(),
            disabledLanguageIds = options.disabledLanguageIds,
        )
    }

    private fun isCurrent(passStamp: AnalysisStamp): Boolean {
        val options = BracketGuideSettings.getInstance().options
        return passStamp.matchesCurrent(
            editor,
            editorFileType(editor),
            options.analysisCoverage(),
            options.disabledLanguageIds,
        )
    }

    private fun isExactCurrent(passStamp: AnalysisStamp): Boolean {
        val options = BracketGuideSettings.getInstance().options
        val requiredCoverage = options.analysisCoverage()
        return passStamp.coverage == requiredCoverage &&
            passStamp.matchesCurrent(
                editor,
                editorFileType(editor),
                requiredCoverage,
                options.disabledLanguageIds,
            )
    }

    private fun installSession(): EditorGuideSession? {
        if (editor.isDisposed) return null
        EditorGuideEvents.ensureInitialized()
        return EditorGuideSessions.install(
            editor = editor,
            visibleRange = visibleRange,
        )
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
