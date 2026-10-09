package com.sijunyang.bracketpairguides.plugin

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sijunyang.bracketpairguides.ui.editor.EditorEffectGuard

/** Daemon request adapter; execution owns its independent capture and publication lifetime. */
internal class BracketGuideHighlightingPass(project: Project, private val editor: Editor) :
    TextEditorHighlightingPass(project, editor.document, false) {
    override fun doCollectInformation(progress: ProgressIndicator) {
        if (EditorEffectGuard.allowsEffects() &&
            !editor.isDisposed
        ) {
            com.intellij.openapi.components.service<GuidePlugin>().request(editor)
        }
    }

    override fun doApplyInformationToEditor() = Unit
}
