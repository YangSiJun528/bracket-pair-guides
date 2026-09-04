package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

/** Commits normalized preferences and applies their effects as one EDT transaction. */
@Service(Service.Level.APP)
internal class BracketGuideSettingsController internal constructor(
    private val settings: () -> BracketGuideSettings,
    private val applyNativeMatchedBraceSetting: (BracketGuidePreferences) -> BracketGuidePreferences,
    private val applyRuntimeChange: (BracketGuidePreferences, BracketGuidePreferences) -> Unit,
    private val runOnEdt: ((() -> Unit) -> Unit),
) {
    @Suppress("unused")
    constructor() : this(
        settings = { BracketGuideSettings.getInstance() },
        applyNativeMatchedBraceSetting = {
            NativeMatchedBraceHighlighting.getInstance().apply(it)
        },
        applyRuntimeChange = { previous, current ->
            GuideSettingsChange(previous, current).apply()
        },
        runOnEdt = { action ->
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) {
                action()
            } else {
                application.invokeAndWait { action() }
            }
        },
    )

    /** The single production entry point for a committed preference snapshot. */
    fun applySettings(options: BracketGuidePreferences) {
        runOnEdt {
            commit(options, NativeReconciliation.IF_CHANGED)
        }
    }

    /** Reconciles an externally changed native setting without synthesizing a preference change. */
    internal fun reconcileNativeSettings() {
        runOnEdt {
            commit(settings().options, NativeReconciliation.ALWAYS)
        }
    }

    /** Records an override found while native-setting ownership is being released. */
    internal fun nativeMatchedBraceSettingWasOverridden() {
        runOnEdt {
            val current = settings().options
            commit(
                current.copy(disableNativeMatchedBraceHighlighting = false),
                NativeReconciliation.NONE,
            )
        }
    }

    private fun commit(requested: BracketGuidePreferences, nativeReconciliation: NativeReconciliation) {
        val persistedSettings = settings()
        val previous = persistedSettings.options
        persistedSettings.replace(requested)
        var current = persistedSettings.options
        if (current == previous && nativeReconciliation != NativeReconciliation.ALWAYS) return

        if (nativeReconciliation != NativeReconciliation.NONE) {
            val reconciled = applyNativeMatchedBraceSetting(current)
            persistedSettings.replace(reconciled)
            current = persistedSettings.options
        }
        if (current == previous) return

        applyRuntimeChange(previous, current)
    }

    private enum class NativeReconciliation {
        NONE,
        IF_CHANGED,
        ALWAYS,
    }

    companion object {
        @JvmStatic
        fun getInstance(): BracketGuideSettingsController =
            ApplicationManager.getApplication().getService(BracketGuideSettingsController::class.java)
    }
}
