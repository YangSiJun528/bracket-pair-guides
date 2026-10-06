package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.ProjectManager

/** Requests a fresh analysis pass after plugin rendering preferences change. */
internal object DaemonRefresh {
    fun request() {
        for (project in ProjectManager.getInstance().openProjects) {
            DaemonCodeAnalyzer.getInstance(project).settingsChanged()
        }
    }
}
