package com.sijunyang.bracketpairguides.ui.settings

import com.sijunyang.bracketpairguides.ui.editor.events.BracketGuideSettingsController
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences

/** Production settings command used by composition and SDK settings adapters. */
object GuideUiSettings {
    fun apply(options: BracketGuidePreferences) = BracketGuideSettingsController.getInstance().applySettings(options)
    fun reconcileNative() = BracketGuideSettingsController.getInstance().reconcileNativeSettings()
    fun current(): BracketGuidePreferences = BracketGuideSettings.getInstance().options
}
