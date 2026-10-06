package com.sijunyang.bracketpairguides.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.sijunyang.bracketpairguides.editor.policy.EditorActivity
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

/** Swing and popup ownership are read only on EDT, never by background analysis. */
object EditorActivitySource {
    fun capture(editor: Editor): EditorActivity {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (editor.isDisposed) return EditorActivity.INACTIVE
        val content = editor.contentComponent
        if (!content.isShowing) return EditorActivity.INACTIVE
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return EditorActivity(
            visible = true,
            active = focus != null && SwingUtilities.isDescendingFrom(focus, content) ||
                JBPopupFactory.getInstance().isChildPopupFocused(content),
        )
    }
}
