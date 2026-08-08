package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

internal class GuideLineHighlightingPassFactory public constructor() :
    TextEditorHighlightingPassFactory,
    TextEditorHighlightingPassFactoryRegistrar,
    DumbAware {

    public override fun registerHighlightingPassFactory(
        registrar: TextEditorHighlightingPassRegistrar,
        project: Project,
    ): Unit {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    public override fun createHighlightingPass(
        file: PsiFile,
        editor: Editor,
    ): TextEditorHighlightingPass {
        return GuideLineHighlightingPass(file.project, editor, file.fileType)
    }
}
