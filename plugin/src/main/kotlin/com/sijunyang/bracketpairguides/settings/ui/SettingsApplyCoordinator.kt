package com.sijunyang.bracketpairguides.settings.ui

import com.sijunyang.bracketpairguides.editor.EditorGuideEventRouter
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.editor.analysisCapabilities
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager

/** Propagates applied settings to live editors and IntelliJ's highlighting daemon. */
internal object SettingsApplyCoordinator {
    fun applyChanges(previous: PluginOptions, applied: PluginOptions) {
        if (previous == applied) return

        val capabilitiesChanged = previous.analysisCapabilities() !=
            applied.analysisCapabilities()
        val languagesChanged = previous.disabledLanguageIds != applied.disabledLanguageIds
        val sessionEditors = EditorFactory.getInstance().allEditors.filter { editor ->
            !editor.isDisposed && EditorGuideSession.get(editor) != null
        }
        val immediateEditor = EditorGuideEventRouter.preferredImmediateEditor(sessionEditors)

        for (editor in sessionEditors) {
            EditorGuideSession.get(editor)?.updateOptions(
                applied,
                resolveImmediately = editor === immediateEditor,
            )
        }
        if (capabilitiesChanged || languagesChanged) {
            for (project in ProjectManager.getInstance().openProjects) {
                DaemonRestartBridge.restart(DaemonCodeAnalyzer.getInstance(project))
            }
        }
    }
}
