package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis

/** IntelliJ registration and composition root for the highlighting pass. */
internal class BracketGuidePassRegistration :
    TextEditorHighlightingPassFactory,
    TextEditorHighlightingPassFactoryRegistrar,
    DumbAware {

    override fun registerHighlightingPassFactory(
        registrar: TextEditorHighlightingPassRegistrar,
        project: Project,
    ) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(
        file: PsiFile,
        editor: Editor,
    ): TextEditorHighlightingPass {
        return BracketGuideHighlightingPass(
            project = file.project,
            editor = editor,
            fileType = file.fileType,
            sourceFile = file.virtualFile,
            analyze = service<BracketAnalysis>()::analyze,
        )
    }
}
