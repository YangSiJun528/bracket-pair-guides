package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager

/** Applies the same public refresh sequence as IntelliJ's own Editor settings page. */
internal object NativeEditorSettingsRefresh {
    fun request() {
        check(ApplicationManager.getApplication().isDispatchThread) {
            "Native editor settings must be refreshed on the EDT"
        }
        EditorFactory.getInstance().refreshAllEditors()
        UISettings.getInstance().fireUISettingsChanged()
        for (project in ProjectManager.getInstance().openProjects) {
            DaemonCodeAnalyzer.getInstance(project).settingsChanged()
        }
    }
}
