package com.sijunyang.bracketpairguides.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.sijunyang.bracketpairguides.ui.editor.events.EditorGuideEvents
import com.sijunyang.bracketpairguides.ui.editor.highlighting.NativeGuideAdvisory
import com.sijunyang.bracketpairguides.ui.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.ui.work.GuideWorkFactory

/** One registry for editor presentation and its independently owned work lifetime. */
object EditorGuides {
    private val key = Key.create<EditorGuide>("bracket.pair.guides.editor")
    private var factory: GuideWorkFactory? = null
    private var advisory: NativeGuideAdvisory? = null

    fun connect(work: GuideWorkFactory, notifications: NativeGuideAdvisory) {
        check(factory == null) { "Guide composition is installed once" }
        factory = work
        advisory = notifications
    }

    fun attach(editor: Editor) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed || get(editor) != null) return
        val workFactory = factory ?: return
        val capabilities = EditorSurfaceClassifier.capabilities(editor)
        if (capabilities == com.sijunyang.bracketpairguides.ui.policy.EditorCapabilities.NONE) return
        val guide = EditorGuide(
            editor,
            BracketGuideSettings.getInstance().options,
            capabilities,
            EditorActivitySource.capture(editor),
            checkNotNull(advisory),
            workFactory,
        )
        editor.putUserData(key, guide)
        EditorGuideEvents.ensureInitialized(editor, capabilities.activePair)
        guide.start()
    }

    /** Daemon callers never access mutable presentation state off EDT. */
    fun refresh(editor: Editor) {
        if (!EditorEffectGuard.allowsEffects() || editor.isDisposed) return
        val guide = get(editor)
        if (guide != null) {
            guide.wakeUp()
        } else {
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed) {
                    attach(editor)
                    get(editor)?.wakeUp()
                }
            }
        }
    }

    internal fun get(editor: Editor): EditorGuide? = editor.getUserData(key)

    fun dispose(editor: Editor) {
        val guide = editor.getUserData(key)
        editor.putUserData(key, null)
        guide?.close()
    }

    fun disconnect() {
        factory = null
        advisory = null
    }
}
