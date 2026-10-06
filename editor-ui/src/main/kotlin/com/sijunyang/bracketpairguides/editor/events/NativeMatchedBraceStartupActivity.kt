package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Applies persisted native-visual choices before the first editor interaction. */
internal class NativeMatchedBraceStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (project.isDisposed || application.isUnitTestMode) return

        BracketGuideSettingsController.getInstance().reconcileNativeSettings()
    }
}
