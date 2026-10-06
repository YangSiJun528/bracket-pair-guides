package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard

/** Starts secondary-editor observation even before a main-editor pass is created. */
internal class EditorSurfaceStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (!EditorEffectGuard.allowsEffects() || application.isUnitTestMode) return
        application.invokeLater {
            if (!project.isDisposed) SecondaryEditorAnalysis.ensureInitialized()
        }
    }
}
