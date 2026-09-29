package com.sijunyang.bracketpairguides.editor

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.application.ApplicationManager

/** Check on the originating thread, before service creation or UI scheduling. */
internal object EditorEffectGuard {
    fun allowsEffects(): Boolean = !IntentionPreviewUtils.isIntentionPreviewActive() &&
        !ApplicationManager.getApplication().isDisposed
}
