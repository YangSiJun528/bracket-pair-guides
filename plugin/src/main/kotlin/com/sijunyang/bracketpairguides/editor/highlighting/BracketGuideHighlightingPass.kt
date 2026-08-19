package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.sijunyang.bracketpairguides.analysis.AnalysisStamp
import com.sijunyang.bracketpairguides.analysis.AnalysisInput
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.preferences.analysisCoverage
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
    private val sourceFile: VirtualFile?,
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

    override fun doCollectInformation(progress: ProgressIndicator) {
        collected = null
        collectedStamp = null
        val input = currentInput()
        collectedStamp = input.stamp
        if (sourceIsTooLarge()) {
            collected = AnalysisOutcome.Unavailable(
                input.stamp,
                AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE,
            )
            return
        }
        if (EditorGuideSessions.canSkipAnalysis(editor, input.stamp)) {
            return
        }
        collected = analyze(input, progress)
    }

    override fun doApplyInformationToEditor() {
        val result = collected
        val passStamp = collectedStamp
        collected = null
        collectedStamp = null
        val ideCodeInsightLimitApplies = sourceIsTooLarge()
        if (ideCodeInsightLimitApplies) {
            if (editor.isDisposed) return
            val currentStamp = currentInput(editorFileType(editor)).stamp
            val session = installSession() ?: return
            session.updateDependenciesIfCurrent(
                visibleRange = visibleRange,
                passStamp = currentStamp,
            )
            session.accept(
                AnalysisOutcome.Unavailable(
                    currentStamp,
                    AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE,
                ),
            )
            return
        }
        val collectedIdeSizeRefusal = result is AnalysisOutcome.Unavailable &&
            result.limit == AnalysisLimit.IDE_CODE_INSIGHT_FILE_SIZE
        val effectiveResult = result.takeUnless { collectedIdeSizeRefusal }
        if (editor.isDisposed || passStamp?.let { stamp ->
                when {
                    collectedIdeSizeRefusal -> isExactCurrent(stamp)
                    effectiveResult is AnalysisOutcome.Limited ||
                        effectiveResult is AnalysisOutcome.Unavailable -> isExactCurrent(stamp)
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
        effectiveResult?.let(session::accept)
    }

    private fun currentInput(currentFileType: FileType = fileType): AnalysisInput {
        val options = BracketGuideSettings.getInstance().options
        return AnalysisInput(
            editor = editor,
            fileType = currentFileType,
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

    private fun sourceIsTooLarge(): Boolean =
        sourceFile?.let { file ->
            if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) {
                // IntelliJ's document-commit path uses textLength for current
                // in-memory content; saved content keeps VirtualFile byte size.
                SingleRootFileViewProvider.isTooLargeForIntelligence(
                    file,
                    editor.document.textLength.toLong(),
                )
            } else {
                SingleRootFileViewProvider.isTooLargeForIntelligence(file)
            }
        } == true

    private fun installSession(): EditorGuideSession? {
        if (editor.isDisposed) return null
        EditorGuideEvents.ensureInitialized()
        return EditorGuideSessions.install(
            editor = editor,
            visibleRange = visibleRange,
            preferences = BracketGuideSettings.getInstance().options,
            matcherAvailabilityChanged = UnsupportedBackendNotificationProvider::update,
        )
    }
}

private fun editorFileType(editor: Editor): FileType =
    FileDocumentManager.getInstance().getFile(editor.document)?.fileType
        ?: PlainTextFileType.INSTANCE
