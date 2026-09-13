package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** Receives each fully reconciled settings snapshot that can start or end a conflict episode. */
internal fun interface NativeGuideConflictSettingsListener {
    fun settingsChanged(previous: BracketGuidePreferences, current: BracketGuidePreferences)
}
