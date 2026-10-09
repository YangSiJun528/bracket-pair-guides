package com.sijunyang.bracketpairguides.plugin

import com.intellij.application.options.editor.EditorOptionsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.sijunyang.bracketpairguides.runtime.bootstrap.RuntimeGuideWorkFactory
import com.sijunyang.bracketpairguides.ui.editor.EditorEffectGuard
import com.sijunyang.bracketpairguides.ui.editor.EditorGuides
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.settings.GuideUiSettings
import com.sijunyang.bracketpairguides.ui.settings.NativeGuideConflictSettingsListener
import kotlinx.coroutines.CoroutineScope

/** The only runtime/UI composition root. No calculator types cross this module. */
@Service(Service.Level.APP)
class GuidePlugin(scope: CoroutineScope) : Disposable {
    private val runtime = RuntimeGuideWorkFactory(scope)
    private var started = false

    init {
        Disposer.register(this, runtime)
    }

    fun start() {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater(::start, ModalityState.any())
            return
        }
        if (started || application.isDisposed || !EditorEffectGuard.allowsEffects()) return
        started = true
        val advisory = service<NativeGuideConflictSettingsListener>() as NativeGuideAdvisory
        EditorGuides.connect(runtime, advisory)
        application.messageBus.connect(this).subscribe(EditorOptionsListener.OPTIONS_PANEL_TOPIC, advisory)
        val editors = EditorFactory.getInstance()
        editors.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    EditorGuides.attach(event.editor)
                }
            },
            this,
        )
        editors.allEditors.forEach(EditorGuides::attach)
        GuideUiSettings.reconcileNative()
    }

    fun request(editor: Editor) {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed) return
        start()
        EditorGuides.refresh(editor)
    }

    fun applyPreferences(options: BracketGuidePreferences) {
        start()
        GuideUiSettings.apply(options)
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.forEach(EditorGuides::dispose)
        EditorGuides.disconnect()
    }
}

class GuideStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!project.isDisposed) service<GuidePlugin>().start()
    }
}
