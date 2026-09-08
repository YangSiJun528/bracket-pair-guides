package com.sijunyang.bracketpairguides.editor.events

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.lang.reflect.Proxy

class NativeVisualSettingsCoordinatorTest {
    @Test
    fun `default owns only matched braces and preserves regular indent guides`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))

        val effective = fixture.coordinator.apply(BracketGuidePreferences())

        assertThat(effective).isEqualTo(BracketGuidePreferences())
        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = false, scope = true, indent = true),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(restoreValue = true),
        )
        assertThat(fixture.highlightRefreshes).hasSize(1)
        assertThat(fixture.indentRefreshes).isEmpty()
    }

    @Test
    fun `current scope mode owns only current scope`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))

        fixture.coordinator.apply(preferences(mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY))

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = false, indent = true),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(
                currentScopeRestoreValue = true,
            ),
        )
    }

    @Test
    fun `leave unchanged mode owns neither highlight value`() {
        val fixture = fixture(NativeValues(braces = true, scope = false, indent = true))

        fixture.coordinator.apply(
            preferences(mode = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED),
        )

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = false, indent = true),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
        assertThat(fixture.writeLog).isEmpty()
    }

    @Test
    fun `mode transitions acquire the new slot before releasing the old slot`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        fixture.coordinator.apply(BracketGuidePreferences())
        fixture.writeLog.clear()

        fixture.coordinator.apply(preferences(mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY))

        assertThat(fixture.writeLog).containsExactly("scope=false", "braces=true")
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(
                currentScopeRestoreValue = true,
            ),
        )

        fixture.writeLog.clear()
        fixture.coordinator.apply(BracketGuidePreferences())

        assertThat(fixture.writeLog).containsExactly("braces=false", "scope=true")
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(restoreValue = true),
        )
    }

    @Test
    fun `parent and plugin gates release everything without clearing child selections`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        val selected =
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                hideIndentGuides = true,
            )
        fixture.coordinator.apply(selected)

        val parentOff =
            selected.copy(
                intelliJIntegration =
                selected.intelliJIntegration.copy(manageNativeVisuals = false),
            )
        assertThat(fixture.coordinator.apply(parentOff)).isEqualTo(parentOff)
        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )

        fixture.coordinator.apply(selected)
        val pluginOff = selected.copy(enabled = false)
        assertThat(fixture.coordinator.apply(pluginOff)).isEqualTo(pluginOff)
        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(pluginOff.intelliJIntegration).isEqualTo(selected.intelliJIntegration)
    }

    @Test
    fun `all three slots restore exact original true values`() {
        assertExactRestoration(original = true)
    }

    @Test
    fun `all three slots restore exact original false values`() {
        assertExactRestoration(original = false)
    }

    @Test
    fun `external brace and indent enablement updates only owning children`() {
        val fixture = fixture(NativeValues(braces = true, scope = false, indent = true))
        val requested = preferences(hideIndentGuides = true)
        fixture.coordinator.apply(requested)
        fixture.braces.enabled = true
        fixture.indent.enabled = true

        val effective = fixture.coordinator.apply(requested)

        assertThat(effective.enabled).isTrue()
        assertThat(effective.intelliJIntegration.manageNativeVisuals).isTrue()
        assertThat(effective.intelliJIntegration.nativeHighlightMode).isEqualTo(
            NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
        )
        assertThat(effective.intelliJIntegration.hideNativeIndentGuides).isFalse()
        assertThat(fixture.scope.enabled).isFalse()
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    @Test
    fun `external matched brace enablement preserves the indent child and parent`() {
        val fixture = fixture(NativeValues(braces = true, scope = false, indent = true))
        val requested = preferences(hideIndentGuides = true)
        fixture.coordinator.apply(requested)
        fixture.braces.enabled = true

        val effective = fixture.coordinator.apply(requested)

        assertThat(effective.intelliJIntegration).isEqualTo(
            IntelliJIntegrationPreferences(
                nativeHighlightMode =
                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                hideNativeIndentGuides = true,
            ),
        )
        assertThat(fixture.indent.enabled).isFalse()
        assertThat(fixture.coordinator.state.indentGuidesRestoreValue).isTrue()
    }

    @Test
    fun `external indent enablement preserves the highlight child and parent`() {
        val fixture = fixture(NativeValues(braces = true, scope = false, indent = true))
        val requested = preferences(hideIndentGuides = true)
        fixture.coordinator.apply(requested)
        fixture.indent.enabled = true

        val effective = fixture.coordinator.apply(requested)

        assertThat(effective.intelliJIntegration).isEqualTo(
            IntelliJIntegrationPreferences(
                nativeHighlightMode =
                NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE,
                hideNativeIndentGuides = false,
            ),
        )
        assertThat(fixture.braces.enabled).isFalse()
        assertThat(fixture.coordinator.state.restoreValue).isTrue()
    }

    @Test
    fun `external current scope enablement leaves other children unchanged`() {
        val fixture = fixture(NativeValues(braces = false, scope = true, indent = false))
        val requested =
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                hideIndentGuides = true,
            )
        fixture.coordinator.apply(requested)
        fixture.scope.enabled = true

        val effective = fixture.coordinator.apply(requested)

        assertThat(effective.intelliJIntegration).isEqualTo(
            IntelliJIntegrationPreferences(
                nativeHighlightMode =
                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                hideNativeIndentGuides = true,
            ),
        )
        assertThat(fixture.braces.enabled).isFalse()
        assertThat(fixture.indent.enabled).isFalse()
        assertThat(fixture.coordinator.state.indentGuidesRestoreValue).isFalse()
    }

    @Test
    fun `plugin writes are ignored by a synchronous reentrant reconciliation`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        val requested = preferences(hideIndentGuides = true)
        val reentrantResults = mutableListOf<BracketGuidePreferences>()
        fixture.indent.afterWrite = {
            reentrantResults += fixture.coordinator.apply(requested)
        }

        fixture.coordinator.apply(requested)

        assertThat(reentrantResults).containsExactly(requested)
        assertThat(fixture.coordinator.state.indentGuidesRestoreValue).isTrue()
        assertThat(fixture.externalOverrides).isEmpty()
    }

    @Test
    fun `native writes refresh only their corresponding editor surfaces`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        val requested = preferences(hideIndentGuides = true)

        fixture.coordinator.apply(requested)
        fixture.coordinator.apply(requested)

        assertThat(fixture.highlightRefreshes).hasSize(1)
        assertThat(fixture.indentRefreshes).hasSize(1)

        fixture.coordinator.apply(
            requested.copy(
                intelliJIntegration =
                requested.intelliJIntegration.copy(manageNativeVisuals = false),
            ),
        )

        assertThat(fixture.highlightRefreshes).hasSize(2)
        assertThat(fixture.indentRefreshes).hasSize(2)
    }

    @Test
    fun `shutdown restores all slots and persists exactly once after clearing ownership`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        fixture.coordinator.apply(
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                hideIndentGuides = true,
            ),
        )

        fixture.coordinator.appWillBeClosed(false)
        fixture.coordinator.dispose()

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.persistedSnapshots).containsExactly(
            PersistedSnapshot(
                values = NativeValues(braces = true, scope = true, indent = true),
                ownership = NativeVisualSettingsCoordinator.OwnershipState(),
            ),
        )
    }

    @Test
    fun `shutdown persists cleared ownership even when every original was false`() {
        val fixture = fixture(NativeValues(braces = false, scope = false, indent = false))
        fixture.coordinator.apply(
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                hideIndentGuides = true,
            ),
        )

        fixture.coordinator.appWillBeClosed(false)

        assertThat(fixture.persistedSnapshots).containsExactly(
            PersistedSnapshot(
                values = NativeValues(braces = false, scope = false, indent = false),
                ownership = NativeVisualSettingsCoordinator.OwnershipState(),
            ),
        )
    }

    @Test
    fun `dynamic unload ignores other plugins and persists this plugin once`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        fixture.coordinator.apply(preferences(hideIndentGuides = true))

        fixture.pluginListener.beforePluginUnload(pluginDescriptor("unrelated.plugin"), false)
        assertThat(fixture.persistedSnapshots).isEmpty()
        assertThat(fixture.braces.enabled).isFalse()

        fixture.pluginListener.beforePluginUnload(
            pluginDescriptor("com.sijunyang.bracketpairguides"),
            true,
        )
        fixture.coordinator.dispose()

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.persistedSnapshots).hasSize(1)
        assertThat(fixture.persistedSnapshots.single().ownership).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    @Test
    fun `lifecycle release reports only externally enabled owned slots`() {
        val fixture = fixture(NativeValues(braces = true, scope = true, indent = true))
        fixture.coordinator.apply(preferences(hideIndentGuides = true))
        fixture.braces.enabled = true

        fixture.coordinator.appWillBeClosed(false)

        assertThat(fixture.externalOverrides).containsExactly(
            setOf(NativeVisualSettingTarget.MATCHED_BRACES),
        )
    }

    @Test
    fun `nullable false ownership values survive serialization`() {
        val state =
            NativeVisualSettingsCoordinator.OwnershipState(
                restoreValue = false,
                currentScopeRestoreValue = false,
                indentGuidesRestoreValue = false,
            )

        val serialized = XmlSerializer.serialize(state)
        val restored =
            XmlSerializer.deserialize(
                serialized,
                NativeVisualSettingsCoordinator.OwnershipState::class.java,
            )

        assertThat(restored).isEqualTo(state)
    }

    @Test
    fun `excluded environments never mutate or release native settings`() {
        val fixture =
            fixture(
                NativeValues(braces = true, scope = true, indent = true),
                mayMutate = { false },
            )

        fixture.coordinator.apply(preferences(hideIndentGuides = true))
        fixture.coordinator.appWillBeClosed(false)
        fixture.coordinator.dispose()

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.writeLog).isEmpty()
        assertThat(fixture.persistedSnapshots).isEmpty()
    }

    @Test
    fun `becoming multi-client unwinds local ownership without clearing child selections`() {
        var mayMutate = true
        val requested = preferences(hideIndentGuides = true)
        val fixture =
            fixture(
                NativeValues(braces = true, scope = true, indent = true),
                mayMutate = { mayMutate },
                mayRestoreOwned = { true },
            )
        fixture.coordinator.apply(requested)

        mayMutate = false
        val effective = fixture.coordinator.apply(requested)

        assertThat(effective).isEqualTo(requested)
        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    @Test
    fun `environment transition returns CAS correction for an external override`() {
        var mayMutate = true
        val requested = preferences(hideIndentGuides = true)
        val fixture =
            fixture(
                NativeValues(braces = true, scope = false, indent = true),
                mayMutate = { mayMutate },
                mayRestoreOwned = { true },
            )
        fixture.coordinator.apply(requested)
        fixture.braces.enabled = true

        mayMutate = false
        val effective = fixture.coordinator.apply(requested)

        assertThat(effective.intelliJIntegration).isEqualTo(
            IntelliJIntegrationPreferences(
                nativeHighlightMode =
                NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                hideNativeIndentGuides = true,
            ),
        )
        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = false, indent = true),
        )
        assertThat(fixture.externalOverrides).isEmpty()
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    @Test
    fun `shutdown can restore local ownership after a remote session begins`() {
        var mayMutate = true
        val fixture =
            fixture(
                NativeValues(braces = true, scope = true, indent = true),
                mayMutate = { mayMutate },
                mayRestoreOwned = { true },
            )
        fixture.coordinator.apply(preferences(hideIndentGuides = true))

        mayMutate = false
        fixture.coordinator.appWillBeClosed(false)

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = true, scope = true, indent = true),
        )
        assertThat(fixture.persistedSnapshots).hasSize(1)
        assertThat(fixture.persistedSnapshots.single().ownership).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    @Test
    fun `remote launch modes are matched as exact command tokens`() {
        for (
        token in listOf(
            "remoteDevHost",
            "remoteDevMode",
            "cwmHost",
            "cwmHostNoLobby",
            "serverMode",
            "splitMode",
        )
        ) {
            assertThat(
                NativeVisualEnvironment.hasExcludedLaunchToken("com.intellij.idea.Main $token project"),
            ).describedAs("launch token %s", token).isTrue()
        }
    }

    @Test
    fun `remote launch substrings do not exclude a standard command`() {
        assertThat(
            NativeVisualEnvironment.hasExcludedLaunchToken(
                "com.example.remoteDevHostTools /projects/serverModeDemo",
            ),
        ).isFalse()
    }

    private fun assertExactRestoration(original: Boolean) {
        val fixture =
            fixture(
                NativeValues(braces = original, scope = original, indent = original),
            )
        val scopeAndIndent =
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                hideIndentGuides = true,
            )
        fixture.coordinator.apply(scopeAndIndent)
        fixture.coordinator.apply(preferences(hideIndentGuides = true))
        fixture.coordinator.apply(
            preferences(
                mode = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
            ),
        )

        assertThat(fixture.values()).isEqualTo(
            NativeValues(braces = original, scope = original, indent = original),
        )
        assertThat(fixture.coordinator.state).isEqualTo(
            NativeVisualSettingsCoordinator.OwnershipState(),
        )
    }

    private fun preferences(
        mode: NativeHighlightMode = NativeHighlightMode.SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE,
        hideIndentGuides: Boolean = false,
    ): BracketGuidePreferences = BracketGuidePreferences(
        intelliJIntegration =
        IntelliJIntegrationPreferences(
            nativeHighlightMode = mode,
            hideNativeIndentGuides = hideIndentGuides,
        ),
    )

    private fun fixture(
        initialValues: NativeValues,
        mayMutate: () -> Boolean = { true },
        mayRestoreOwned: () -> Boolean = mayMutate,
    ): Fixture {
        val writeLog = mutableListOf<String>()
        val braces = FakeNativeSetting("braces", initialValues.braces, writeLog)
        val scope = FakeNativeSetting("scope", initialValues.scope, writeLog)
        val indent = FakeNativeSetting("indent", initialValues.indent, writeLog)
        val externalOverrides = mutableListOf<Set<NativeVisualSettingTarget>>()
        val highlightRefreshes = mutableListOf<Unit>()
        val indentRefreshes = mutableListOf<Unit>()
        val persistedSnapshots = mutableListOf<PersistedSnapshot>()
        lateinit var pluginListener: DynamicPluginListener
        lateinit var coordinator: NativeVisualSettingsCoordinator
        coordinator =
            NativeVisualSettingsCoordinator(
                matchedBraceSetting = braces,
                currentScopeSetting = scope,
                indentGuidesSetting = indent,
                onExternalOverrides = externalOverrides::add,
                mayMutate = mayMutate,
                mayRestoreOwned = mayRestoreOwned,
                refreshHighlights = { highlightRefreshes += Unit },
                refreshIndentGuides = { indentRefreshes += Unit },
                persistSettings = {
                    persistedSnapshots +=
                        PersistedSnapshot(
                            values = NativeValues(braces.enabled, scope.enabled, indent.enabled),
                            ownership = coordinator.state,
                        )
                },
                subscribeToLifecycle = { _, listener, _ -> pluginListener = listener },
            )
        return Fixture(
            coordinator = coordinator,
            pluginListener = pluginListener,
            braces = braces,
            scope = scope,
            indent = indent,
            writeLog = writeLog,
            externalOverrides = externalOverrides,
            highlightRefreshes = highlightRefreshes,
            indentRefreshes = indentRefreshes,
            persistedSnapshots = persistedSnapshots,
        )
    }

    private data class Fixture(
        val coordinator: NativeVisualSettingsCoordinator,
        val pluginListener: DynamicPluginListener,
        val braces: FakeNativeSetting,
        val scope: FakeNativeSetting,
        val indent: FakeNativeSetting,
        val writeLog: MutableList<String>,
        val externalOverrides: List<Set<NativeVisualSettingTarget>>,
        val highlightRefreshes: List<Unit>,
        val indentRefreshes: List<Unit>,
        val persistedSnapshots: List<PersistedSnapshot>,
    ) {
        fun values(): NativeValues = NativeValues(braces.enabled, scope.enabled, indent.enabled)
    }

    private data class NativeValues(val braces: Boolean, val scope: Boolean, val indent: Boolean)

    private data class PersistedSnapshot(
        val values: NativeValues,
        val ownership: NativeVisualSettingsCoordinator.OwnershipState,
    )

    private class FakeNativeSetting(
        private val name: String,
        initialValue: Boolean,
        private val writeLog: MutableList<String>,
    ) : NativeBooleanSetting {
        var afterWrite: (() -> Unit)? = null

        override var enabled: Boolean = initialValue
            set(value) {
                field = value
                writeLog += "$name=$value"
                afterWrite?.invoke()
            }
    }

    private fun pluginDescriptor(id: String): IdeaPluginDescriptor = Proxy.newProxyInstance(
        IdeaPluginDescriptor::class.java.classLoader,
        arrayOf(IdeaPluginDescriptor::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPluginId" -> PluginId.getId(id)
            "toString" -> id
            else -> error("Unexpected descriptor call: ${method.name}")
        }
    } as IdeaPluginDescriptor
}
