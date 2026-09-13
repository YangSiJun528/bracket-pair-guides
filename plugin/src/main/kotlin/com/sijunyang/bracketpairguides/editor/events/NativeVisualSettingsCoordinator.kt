package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode

/**
 * Temporarily owns the three IntelliJ Boolean settings that can render native
 * editor guides. Ownership is value based: an external write of `false` while
 * a slot already owns `false` cannot be distinguished from the plugin's value.
 */
@State(
    // Keep the original component identity so an owned HIGHLIGHT_BRACES value
    // from an earlier plugin build can still be restored.
    name = "BracketPairGuidesNativeMatchedBraceHighlighting",
    storages = [Storage("bracket-pair-guides-native-highlight.xml")],
)
internal class NativeVisualSettingsCoordinator internal constructor(
    private val matchedBraceSetting: NativeBooleanSetting,
    private val currentScopeSetting: NativeBooleanSetting,
    private val indentGuidesSetting: NativeBooleanSetting,
    private val onExternalOverrides: (Set<NativeVisualSettingTarget>) -> Unit,
    private val mayMutate: () -> Boolean,
    private val mayRestoreOwned: () -> Boolean = mayMutate,
    private val refreshNativeSettings: () -> Unit,
    private val persistSettings: () -> Unit,
    subscribeToLifecycle: (
        AppLifecycleListener,
        DynamicPluginListener,
        Disposable,
    ) -> Unit,
) : SerializablePersistentStateComponent<NativeVisualSettingsCoordinator.OwnershipState>(
    OwnershipState(),
),
    Disposable,
    AppLifecycleListener {
    @Suppress("unused")
    constructor() : this(
        matchedBraceSetting = IntelliJMatchedBraceSetting,
        currentScopeSetting = IntelliJCurrentScopeSetting,
        indentGuidesSetting = IntelliJIndentGuidesSetting,
        onExternalOverrides = ::recordExternalOverrides,
        mayMutate = NativeVisualEnvironment::canManageNativeSettings,
        refreshNativeSettings = NativeEditorSettingsRefresh::request,
        persistSettings = { ApplicationManager.getApplication().saveSettings() },
        subscribeToLifecycle = { appListener, pluginListener, parentDisposable ->
            ApplicationManager.getApplication().messageBus.connect(parentDisposable).apply {
                subscribe(AppLifecycleListener.TOPIC, appListener)
                subscribe(DynamicPluginListener.TOPIC, pluginListener)
            }
        },
    )

    private var nativeWriteDepth: Int = 0

    init {
        subscribeToLifecycle(
            this,
            NativeMatchedBracePluginUnloadListener(::beforePluginUnload),
            this,
        )
    }

    /** Reconciles every slot in one transaction and returns any CAS correction. */
    @Synchronized
    fun apply(preferences: BracketGuidePreferences): BracketGuidePreferences {
        if (nativeWriteDepth > 0) return preferences
        if (!mayMutate()) {
            // Do not retain ownership if this process can no longer host editor UI.
            val overrides =
                releaseAll(
                    refreshNative = true,
                    persist = false,
                    reportExternalOverrides = false,
                )
            return overrides.fold(preferences) { effective, target ->
                effective.afterExternalOverride(target)
            }
        }

        var effective = preferences
        for (target in NativeVisualSettingTarget.entries) {
            if (restoreValue(target) != null && setting(target).enabled) {
                clearOwnership(target)
                effective = effective.afterExternalOverride(target)
            }
        }

        val desiredHighlight = desiredHighlightTarget(effective)
        var highlightChanged = false
        var indentGuidesChanged = false

        // Acquire the new suppressing slot before releasing the old one. This
        // keeps at least one native highlight gate closed throughout B <-> S.
        if (desiredHighlight != null) {
            highlightChanged = acquire(desiredHighlight) || highlightChanged
        }
        val wantsIndentGuides = wantsIndentGuideOwnership(effective)
        if (wantsIndentGuides) {
            indentGuidesChanged =
                acquire(NativeVisualSettingTarget.INDENT_GUIDES) || indentGuidesChanged
        }

        for (target in HIGHLIGHT_TARGETS) {
            if (target != desiredHighlight) {
                val release = release(target)
                highlightChanged = release.wroteNativeValue || highlightChanged
                if (release.externalOverride) {
                    effective = effective.afterExternalOverride(target)
                }
            }
        }
        if (!wantsIndentGuides) {
            val release = release(NativeVisualSettingTarget.INDENT_GUIDES)
            indentGuidesChanged = release.wroteNativeValue
            if (release.externalOverride) {
                effective =
                    effective.afterExternalOverride(
                        NativeVisualSettingTarget.INDENT_GUIDES,
                    )
            }
        }

        refreshAfterWrites(highlightChanged, indentGuidesChanged)
        return effective
    }

    @Synchronized
    override fun dispose() {
        releaseAll(refreshNative = true, persist = false)
    }

    @Synchronized
    override fun appWillBeClosed(isRestart: Boolean) {
        releaseAll(refreshNative = false, persist = true)
    }

    @Synchronized
    internal fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId == PLUGIN_ID) {
            releaseAll(refreshNative = true, persist = true)
        }
    }

    private fun releaseAll(
        refreshNative: Boolean,
        persist: Boolean,
        reportExternalOverrides: Boolean = true,
    ): Set<NativeVisualSettingTarget> {
        if (!mayRestoreOwned()) return emptySet()
        val overrides = linkedSetOf<NativeVisualSettingTarget>()
        var hadOwnership = false
        var highlightChanged = false
        var indentGuidesChanged = false
        for (target in NativeVisualSettingTarget.entries) {
            val release = release(target)
            hadOwnership = release.wasOwned || hadOwnership
            if (release.externalOverride) overrides += target
            if (target == NativeVisualSettingTarget.INDENT_GUIDES) {
                indentGuidesChanged = release.wroteNativeValue || indentGuidesChanged
            } else {
                highlightChanged = release.wroteNativeValue || highlightChanged
            }
        }
        if (reportExternalOverrides && overrides.isNotEmpty()) onExternalOverrides(overrides)
        if (refreshNative) refreshAfterWrites(highlightChanged, indentGuidesChanged)
        if (persist && hadOwnership) {
            // IntelliJ saves application state before appWillBeClosed, while
            // dynamic unload does not save this service after disposal.
            persistSettings()
        }
        return overrides
    }

    private fun acquire(target: NativeVisualSettingTarget): Boolean {
        if (restoreValue(target) != null) return false
        val nativeSetting = setting(target)
        val original = nativeSetting.enabled
        setRestoreValue(target, original)
        if (!original) return false
        nativeWrite { nativeSetting.enabled = false }
        return true
    }

    private fun release(target: NativeVisualSettingTarget): OwnershipRelease {
        val original = restoreValue(target) ?: return OwnershipRelease.NONE
        val nativeSetting = setting(target)
        if (nativeSetting.enabled) {
            clearOwnership(target)
            return OwnershipRelease(
                wasOwned = true,
                externalOverride = true,
                wroteNativeValue = false,
            )
        }

        if (original) nativeWrite { nativeSetting.enabled = true }
        clearOwnership(target)
        return OwnershipRelease(
            wasOwned = true,
            externalOverride = false,
            wroteNativeValue = original,
        )
    }

    private fun refreshAfterWrites(highlightChanged: Boolean, indentGuidesChanged: Boolean) {
        if (highlightChanged || indentGuidesChanged) refreshNativeSettings()
    }

    private inline fun nativeWrite(action: () -> Unit) {
        nativeWriteDepth++
        try {
            action()
        } finally {
            nativeWriteDepth--
        }
    }

    private fun setting(target: NativeVisualSettingTarget): NativeBooleanSetting = when (target) {
        NativeVisualSettingTarget.MATCHED_BRACES -> matchedBraceSetting
        NativeVisualSettingTarget.CURRENT_SCOPE -> currentScopeSetting
        NativeVisualSettingTarget.INDENT_GUIDES -> indentGuidesSetting
    }

    private fun restoreValue(target: NativeVisualSettingTarget): Boolean? = when (target) {
        NativeVisualSettingTarget.MATCHED_BRACES -> state.restoreValue
        NativeVisualSettingTarget.CURRENT_SCOPE -> state.currentScopeRestoreValue
        NativeVisualSettingTarget.INDENT_GUIDES -> state.indentGuidesRestoreValue
    }

    private fun setRestoreValue(target: NativeVisualSettingTarget, value: Boolean) {
        updateState { current ->
            when (target) {
                NativeVisualSettingTarget.MATCHED_BRACES -> current.copy(restoreValue = value)
                NativeVisualSettingTarget.CURRENT_SCOPE -> current.copy(currentScopeRestoreValue = value)
                NativeVisualSettingTarget.INDENT_GUIDES -> current.copy(indentGuidesRestoreValue = value)
            }
        }
    }

    private fun clearOwnership(target: NativeVisualSettingTarget) {
        updateState { current ->
            when (target) {
                NativeVisualSettingTarget.MATCHED_BRACES -> current.copy(restoreValue = null)
                NativeVisualSettingTarget.CURRENT_SCOPE -> current.copy(currentScopeRestoreValue = null)
                NativeVisualSettingTarget.INDENT_GUIDES -> current.copy(indentGuidesRestoreValue = null)
            }
        }
    }

    internal data class OwnershipState(
        /** Legacy field name retained for persisted HIGHLIGHT_BRACES ownership. */
        @JvmField @field:Property val restoreValue: Boolean? = null,
        @JvmField @field:Property val currentScopeRestoreValue: Boolean? = null,
        @JvmField @field:Property val indentGuidesRestoreValue: Boolean? = null,
    )

    private data class OwnershipRelease(
        val wasOwned: Boolean,
        val externalOverride: Boolean,
        val wroteNativeValue: Boolean,
    ) {
        companion object {
            val NONE = OwnershipRelease(
                wasOwned = false,
                externalOverride = false,
                wroteNativeValue = false,
            )
        }
    }

    companion object {
        private val PLUGIN_ID = PluginId.getId("com.sijunyang.bracketpairguides")
        private val HIGHLIGHT_TARGETS =
            listOf(
                NativeVisualSettingTarget.MATCHED_BRACES,
                NativeVisualSettingTarget.CURRENT_SCOPE,
            )

        fun getInstance(): NativeVisualSettingsCoordinator =
            ApplicationManager.getApplication().getService(NativeVisualSettingsCoordinator::class.java)

        private fun desiredHighlightTarget(preferences: BracketGuidePreferences): NativeVisualSettingTarget? {
            if (!preferences.enabled || !preferences.intelliJIntegration.manageNativeVisuals) {
                return null
            }
            return when (preferences.intelliJIntegration.nativeHighlightMode) {
                NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE ->
                    NativeVisualSettingTarget.MATCHED_BRACES

                NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY ->
                    NativeVisualSettingTarget.CURRENT_SCOPE

                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED -> null
            }
        }

        private fun wantsIndentGuideOwnership(preferences: BracketGuidePreferences): Boolean = preferences.enabled &&
            preferences.intelliJIntegration.manageNativeVisuals &&
            preferences.intelliJIntegration.hideNativeIndentGuides

        private fun BracketGuidePreferences.afterExternalOverride(
            target: NativeVisualSettingTarget,
        ): BracketGuidePreferences {
            if (!enabled || !intelliJIntegration.manageNativeVisuals) return this
            val integration = intelliJIntegration
            val corrected =
                when (target) {
                    NativeVisualSettingTarget.MATCHED_BRACES ->
                        if (
                            integration.nativeHighlightMode ==
                            NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE
                        ) {
                            integration.copy(
                                nativeHighlightMode =
                                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                            )
                        } else {
                            integration
                        }

                    NativeVisualSettingTarget.CURRENT_SCOPE ->
                        if (
                            integration.nativeHighlightMode ==
                            NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY
                        ) {
                            integration.copy(
                                nativeHighlightMode =
                                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                            )
                        } else {
                            integration
                        }

                    NativeVisualSettingTarget.INDENT_GUIDES ->
                        if (integration.hideNativeIndentGuides) {
                            integration.copy(hideNativeIndentGuides = false)
                        } else {
                            integration
                        }
                }
            return if (corrected == integration) this else copy(intelliJIntegration = corrected)
        }

        private fun recordExternalOverrides(targets: Set<NativeVisualSettingTarget>) {
            BracketGuideSettingsController.getInstance()
                .nativeVisualSettingsWereOverridden(targets)
        }
    }
}

internal enum class NativeVisualSettingTarget {
    MATCHED_BRACES,
    CURRENT_SCOPE,
    INDENT_GUIDES,
}

internal interface NativeBooleanSetting {
    var enabled: Boolean
}

/** Native editor settings are meaningful only in an interactive IDE process. */
internal object NativeVisualEnvironment {
    fun canManageNativeSettings(): Boolean {
        val application = ApplicationManager.getApplication()
        return !application.isUnitTestMode && !application.isHeadlessEnvironment
    }
}

private object IntelliJMatchedBraceSetting : NativeBooleanSetting {
    override var enabled: Boolean
        get() = CodeInsightSettings.getInstance().HIGHLIGHT_BRACES
        set(value) {
            CodeInsightSettings.getInstance().HIGHLIGHT_BRACES = value
        }
}

private object IntelliJCurrentScopeSetting : NativeBooleanSetting {
    override var enabled: Boolean
        get() = CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE
        set(value) {
            CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE = value
        }
}

private object IntelliJIndentGuidesSetting : NativeBooleanSetting {
    override var enabled: Boolean
        get() = EditorSettingsExternalizable.getInstance().isIndentGuidesShown
        set(value) {
            EditorSettingsExternalizable.getInstance().isIndentGuidesShown = value
        }
}
