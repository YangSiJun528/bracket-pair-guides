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
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences

/** Owns and reverses the plugin's temporary suppression of IntelliJ's endpoint highlight. */
@State(
    name = "BracketPairGuidesNativeMatchedBraceHighlighting",
    storages = [Storage("bracket-pair-guides-native-highlight.xml")],
)
internal class NativeMatchedBraceHighlighting internal constructor(
    private val nativeSetting: NativeMatchedBraceSetting,
    private val onExternalOverride: () -> Unit,
    private val mayMutate: () -> Boolean,
    private val persistSettings: () -> Unit,
    subscribeToLifecycle: (
        AppLifecycleListener,
        DynamicPluginListener,
        Disposable,
    ) -> Unit,
) : SerializablePersistentStateComponent<NativeMatchedBraceHighlighting.OwnershipState>(
    OwnershipState(),
),
    Disposable,
    AppLifecycleListener {
    @Suppress("unused")
    constructor() : this(
        nativeSetting = IntelliJMatchedBraceSetting,
        onExternalOverride = ::recordExternalOverride,
        mayMutate = { !ApplicationManager.getApplication().isUnitTestMode },
        persistSettings = { ApplicationManager.getApplication().saveSettings() },
        subscribeToLifecycle = { appListener, pluginListener, parentDisposable ->
            ApplicationManager.getApplication().messageBus.connect(parentDisposable).apply {
                subscribe(AppLifecycleListener.TOPIC, appListener)
                subscribe(DynamicPluginListener.TOPIC, pluginListener)
            }
        },
    )

    init {
        subscribeToLifecycle(
            this,
            NativeMatchedBracePluginUnloadListener(::beforePluginUnload),
            this,
        )
    }

    @Synchronized
    fun apply(preferences: BracketGuidePreferences): BracketGuidePreferences {
        if (!mayMutate()) return preferences

        if (!preferences.enabled || !preferences.disableNativeMatchedBraceHighlighting) {
            return preferences.after(releaseOwnership())
        }

        val restoreValue = state.restoreValue
        if (restoreValue != null) {
            if (nativeSetting.enabled) {
                // A newer explicit platform choice wins over the plugin-owned false value.
                clearOwnership()
                return preferences.copy(disableNativeMatchedBraceHighlighting = false)
            }
            return preferences
        }

        updateState { OwnershipState(nativeSetting.enabled) }
        nativeSetting.enabled = false
        return preferences
    }

    @Synchronized
    override fun dispose() {
        if (!mayMutate()) return
        if (releaseOwnership() == OwnershipRelease.EXTERNAL_OVERRIDE) {
            onExternalOverride()
        }
    }

    @Synchronized
    override fun appWillBeClosed(isRestart: Boolean) {
        releaseAndPersistOwnership()
    }

    @Synchronized
    internal fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId == PLUGIN_ID) releaseAndPersistOwnership()
    }

    private fun releaseAndPersistOwnership() {
        if (!mayMutate()) return
        val release = releaseOwnership()
        if (release == OwnershipRelease.NONE) return
        if (release == OwnershipRelease.EXTERNAL_OVERRIDE) {
            onExternalOverride()
        }

        // IntelliJ 2024.1 saves application settings before appWillBeClosed, and
        // dynamic unload does not save this service after dispose. Persist both
        // the restored platform value and cleared ownership while still loaded.
        persistSettings()
    }

    private fun releaseOwnership(): OwnershipRelease {
        val restoreValue = state.restoreValue ?: return OwnershipRelease.NONE
        val externallyEnabled = nativeSetting.enabled
        if (!externallyEnabled) {
            nativeSetting.enabled = restoreValue
        }
        clearOwnership()
        return if (externallyEnabled) {
            OwnershipRelease.EXTERNAL_OVERRIDE
        } else {
            OwnershipRelease.RESTORED
        }
    }

    private fun clearOwnership() {
        updateState { OwnershipState() }
    }

    private fun BracketGuidePreferences.after(release: OwnershipRelease): BracketGuidePreferences = if (
        release == OwnershipRelease.EXTERNAL_OVERRIDE &&
        disableNativeMatchedBraceHighlighting
    ) {
        copy(disableNativeMatchedBraceHighlighting = false)
    } else {
        this
    }

    private enum class OwnershipRelease {
        NONE,
        RESTORED,
        EXTERNAL_OVERRIDE,
    }

    internal data class OwnershipState(@JvmField @field:Property val restoreValue: Boolean? = null)

    companion object {
        private val PLUGIN_ID = PluginId.getId("com.sijunyang.bracketpairguides")

        fun getInstance(): NativeMatchedBraceHighlighting = ApplicationManager.getApplication().getService(
            NativeMatchedBraceHighlighting::class.java,
        )

        private fun recordExternalOverride() {
            BracketGuideSettingsController.getInstance()
                .nativeMatchedBraceSettingWasOverridden()
        }
    }
}

internal interface NativeMatchedBraceSetting {
    var enabled: Boolean
}

private object IntelliJMatchedBraceSetting : NativeMatchedBraceSetting {
    override var enabled: Boolean
        get() = CodeInsightSettings.getInstance().HIGHLIGHT_BRACES
        set(value) {
            CodeInsightSettings.getInstance().HIGHLIGHT_BRACES = value
        }
}
