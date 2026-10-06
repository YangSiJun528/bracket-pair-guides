package com.sijunyang.bracketpairguides.editor.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.NativeGuideConflictSettingsListener

/** Commits normalized preferences and applies their effects as one EDT transaction. */
@Service(Service.Level.APP)
internal class BracketGuideSettingsController internal constructor(
    private val settings: () -> BracketGuideSettings,
    private val applyNativeVisualSettings: (BracketGuidePreferences) -> BracketGuidePreferences,
    private val applyRuntimeChange: (BracketGuidePreferences, BracketGuidePreferences) -> Unit,
    private val runOnEdt: ((() -> Unit) -> Unit),
    private val reportNativeGuideConflictSettings: (
        BracketGuidePreferences,
        BracketGuidePreferences,
    ) -> Unit = { _, _ -> },
) {
    @Suppress("unused")
    constructor() : this(
        settings = { BracketGuideSettings.getInstance() },
        applyNativeVisualSettings = {
            NativeVisualSettingsCoordinator.getInstance().apply(it)
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
        reportNativeGuideConflictSettings = nativeGuideConflictSettingsReporter(),
    )

    /** The single production entry point for a committed preference snapshot. */
    fun applySettings(options: BracketGuidePreferences) {
        runOnEdt {
            // Apply is also the user's explicit request to reconcile IntelliJ's
            // native editor settings. Do this even when our persisted snapshot
            // is unchanged so a missed startup write or external drift recovers.
            commit(options, NativeReconciliation.ALWAYS)
        }
    }

    /** Reconciles an externally changed native setting without synthesizing a preference change. */
    internal fun reconcileNativeSettings() {
        runOnEdt {
            commit(settings().options, NativeReconciliation.ALWAYS)
        }
    }

    /** Records lifecycle-time overrides only if the same child still owns them. */
    internal fun nativeVisualSettingsWereOverridden(targets: Set<NativeVisualSettingTarget>) {
        runOnEdt {
            val current = settings().options
            commit(
                current.afterExternalOverrides(targets),
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
            val reconciled = applyNativeVisualSettings(current)
            persistedSettings.replace(reconciled)
            current = persistedSettings.options
        }
        reportNativeGuideConflictSettings(previous, current)
        if (current == previous) return

        applyRuntimeChange(previous, current)
    }

    private enum class NativeReconciliation {
        NONE,
        ALWAYS,
    }

    private fun BracketGuidePreferences.afterExternalOverrides(
        targets: Set<NativeVisualSettingTarget>,
    ): BracketGuidePreferences {
        if (!enabled || !intelliJIntegration.manageNativeVisuals) return this
        var integration = intelliJIntegration
        if (
            NativeVisualSettingTarget.MATCHED_BRACES in targets &&
            integration.nativeHighlightMode ==
            NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE
        ) {
            integration =
                integration.copy(
                    nativeHighlightMode =
                    NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                )
        }
        if (
            NativeVisualSettingTarget.CURRENT_SCOPE in targets &&
            integration.nativeHighlightMode == NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY
        ) {
            integration =
                integration.copy(
                    nativeHighlightMode =
                    NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                )
        }
        return if (integration == intelliJIntegration) {
            this
        } else {
            copy(intelliJIntegration = integration)
        }
    }

    companion object {
        private fun nativeGuideConflictSettingsReporter(): (
            BracketGuidePreferences,
            BracketGuidePreferences,
        ) -> Unit {
            val listener by lazy(LazyThreadSafetyMode.NONE) {
                ApplicationManager.getApplication()
                    .getService(NativeGuideConflictSettingsListener::class.java)
            }
            return { previous, current -> listener.settingsChanged(previous, current) }
        }

        @JvmStatic
        fun getInstance(): BracketGuideSettingsController =
            ApplicationManager.getApplication().getService(BracketGuideSettingsController::class.java)
    }
}
