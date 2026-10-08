package com.sijunyang.bracketpairguides.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences

@State(
    name = "BracketPairGuides",
    storages = [Storage("bracket-pair-guides.xml")],
)
class BracketGuideSettings :
    SerializablePersistentStateComponent<BracketGuidePreferences>(
        BracketGuidePreferences(),
    ) {
    val options: BracketGuidePreferences
        get() = state

    override fun loadState(state: BracketGuidePreferences) {
        super.loadState(state.normalizedForStorage())
    }

    fun replace(options: BracketGuidePreferences) {
        val normalized = options.normalizedForStorage(state)
        if (state != normalized) {
            updateState { normalized }
        }
    }

    companion object {
        fun getInstance(): BracketGuideSettings =
            ApplicationManager.getApplication().getService(BracketGuideSettings::class.java)
    }
}
