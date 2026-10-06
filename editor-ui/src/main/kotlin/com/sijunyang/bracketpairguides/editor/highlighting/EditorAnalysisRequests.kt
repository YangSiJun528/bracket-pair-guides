package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.sijunyang.bracketpairguides.editor.EditorEffectGuard

/** UI request seam; runtime owns independent scheduling, cancellation, and publication. */
interface EditorAnalysisRequests {
    fun request(editor: Editor)
    fun cancel(editor: Editor)

    companion object {
        fun request(editor: Editor) {
            if (EditorEffectGuard.allowsEffects() && !editor.isDisposed) {
                service<EditorAnalysisRequests>().request(editor)
            }
        }

        fun cancel(editor: Editor) = service<EditorAnalysisRequests>().cancel(editor)
    }
}
