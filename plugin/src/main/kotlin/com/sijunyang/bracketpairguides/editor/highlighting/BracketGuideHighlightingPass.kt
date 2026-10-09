package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard

/** Daemon request adapter; execution owns its independent capture and publication lifetime. */
internal class BracketGuideHighlightingPass(
    project: Project,
    private val editor: Editor,
    private val requestAnalysis: (Editor) -> Unit = EditorAnalysisExecution.Companion::request,
) : TextEditorHighlightingPass(project, editor.document, false) {
    override fun doCollectInformation(progress: ProgressIndicator) {
        if (EditorEffectGuard.allowsEffects() && !editor.isDisposed) requestAnalysis(editor)
    }

    override fun doApplyInformationToEditor() = Unit
}
