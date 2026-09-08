package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeWithMe.ClientId
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.xmlb.annotations.Property
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import java.lang.reflect.Method

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
    private val refreshHighlights: () -> Unit,
    private val refreshIndentGuides: () -> Unit,
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
        mayMutate = NativeVisualEnvironment::isStandardMonolithicApplication,
        mayRestoreOwned = NativeVisualEnvironment::isStandardLocalApplicationContext,
        refreshHighlights = DaemonRefresh::request,
        refreshIndentGuides = { EditorFactory.getInstance().refreshAllEditors() },
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
            // A standard local process can become multi-client after ownership
            // was acquired. Stop owning its local values without touching a
            // remote client's settings or clearing the selected preferences.
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
            indentGuidesChanged = release.wroteNativeValue || indentGuidesChanged
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

        val changed = original
        if (changed) nativeWrite { nativeSetting.enabled = true }
        clearOwnership(target)
        return OwnershipRelease(
            wasOwned = true,
            externalOverride = false,
            wroteNativeValue = changed,
        )
    }

    private fun refreshAfterWrites(highlightChanged: Boolean, indentGuidesChanged: Boolean) {
        if (highlightChanged) refreshHighlights()
        if (indentGuidesChanged) refreshIndentGuides()
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

/** Conservative process gate for settings whose service context is client-specific. */
internal object NativeVisualEnvironment {
    fun isStandardMonolithicApplication(): Boolean {
        if (!isStandardLocalApplicationContext()) return false
        return IntelliJMultiClientProbe.hasNoRemoteAppSessions()
    }

    /**
     * A local settings context that is safe for unwinding ownership already
     * acquired by this process, even after a CWM session has begun.
     */
    fun isStandardLocalApplicationContext(): Boolean {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return false
        if (!isCurrentlyUnderLocalClientId()) return false

        val platformPrefix = systemProperty(PLATFORM_PREFIX_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: return false
        if (platformPrefix in EXCLUDED_PLATFORM_PREFIXES) return false

        val command = systemProperty(JAVA_COMMAND_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: return false
        if (hasExcludedLaunchToken(command)) return false
        return true
    }

    fun isStandardMonolithicEditor(editor: Editor): Boolean =
        isStandardMonolithicApplication() && IntelliJMultiClientProbe.isLocalEditor(editor)

    internal fun hasExcludedLaunchToken(command: String): Boolean = command
        .splitToSequence(WHITESPACE)
        .filter(String::isNotEmpty)
        .any(EXCLUDED_COMMANDS::contains)

    private fun isCurrentlyUnderLocalClientId(): Boolean = try {
        ClientId.isCurrentlyUnderLocalId
    } catch (_: LinkageError) {
        false
    }

    private fun systemProperty(name: String): String? = try {
        System.getProperty(name)
    } catch (_: SecurityException) {
        null
    }

    private const val PLATFORM_PREFIX_PROPERTY = "idea.platform.prefix"
    private const val JAVA_COMMAND_PROPERTY = "sun.java.command"
    private val WHITESPACE = Regex("\\s+")
    private val EXCLUDED_PLATFORM_PREFIXES =
        setOf("JetBrainsClient", "CodeWithMeGuest", "Gateway")
    private val EXCLUDED_COMMANDS =
        setOf(
            "remoteDevHost",
            "remoteDevMode",
            "cwmHost",
            "cwmHostNoLobby",
            "serverMode",
            "splitMode",
        )
}

/**
 * IntelliJ 2024.1 exposes no supported public API for positive monolith/editor
 * classification. These read-only probes isolate the Internal/Experimental
 * multi-client APIs behind reflection. Missing or changed API, failed service
 * lookup, and unknown return types all fail closed before any setting write.
 * A remote session can still begin after this instantaneous check; callers must
 * never use the result as an authorization or security boundary.
 */
private object IntelliJMultiClientProbe {
    fun hasNoRemoteAppSessions(): Boolean {
        val access = reflectionAccess ?: return false
        return try {
            val sessions = access.getAppSessions.invoke(null, access.remoteClientKind)
                as? Collection<*>
                ?: return false
            sessions.isEmpty()
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun isLocalEditor(editor: Editor): Boolean {
        val access = reflectionAccess ?: return false
        return try {
            val clientId = access.getEditorClientId.invoke(null, editor)
            access.isLocalClientId.invoke(null, clientId) == true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private val reflectionAccess: ReflectionAccess? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            val clientKindClass = Class.forName(CLIENT_KIND_CLASS)
            val sessionsManagerClass = Class.forName(CLIENT_SESSIONS_MANAGER_CLASS)
            val clientIdClass = Class.forName(CLIENT_ID_CLASS)
            val editorManagerClass = Class.forName(CLIENT_EDITOR_MANAGER_CLASS)
            ReflectionAccess(
                getAppSessions =
                sessionsManagerClass.getMethod("getAppSessions", clientKindClass),
                remoteClientKind = clientKindClass.getField("REMOTE").get(null),
                getEditorClientId =
                editorManagerClass.getMethod("getClientId", Editor::class.java),
                isLocalClientId = clientIdClass.getMethod("isLocal", clientIdClass),
            )
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private data class ReflectionAccess(
        val getAppSessions: Method,
        val remoteClientKind: Any,
        val getEditorClientId: Method,
        val isLocalClientId: Method,
    )

    private const val CLIENT_KIND_CLASS = "com.intellij.openapi.client.ClientKind"
    private const val CLIENT_SESSIONS_MANAGER_CLASS =
        "com.intellij.openapi.client.ClientSessionsManager"
    private const val CLIENT_ID_CLASS = "com.intellij.codeWithMe.ClientId"
    private const val CLIENT_EDITOR_MANAGER_CLASS =
        "com.intellij.openapi.editor.ClientEditorManager"
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
