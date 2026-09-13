package com.sijunyang.bracketpairguides.editor.events

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

class BracketGuideSettingsControllerTest {
    @Test
    fun `normalizes the committed snapshot before every runtime effect`() {
        val fixture = fixture()

        fixture.controller.applySettings(
            BracketGuidePreferences(
                disabledLanguageIds = setOf(" Rust ", "", "Rust"),
                guideLineWidth = Int.MAX_VALUE,
                guideOpacityPercent = Int.MIN_VALUE,
            ),
        )

        val normalized = fixture.settings.options
        assertThat(normalized.disabledLanguageIds).containsExactly("Rust")
        assertThat(normalized.guideLineWidth)
            .isEqualTo(BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH)
        assertThat(normalized.guideOpacityPercent)
            .isEqualTo(BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT)
        assertThat(fixture.nativeSnapshots).containsExactly(normalized)
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), normalized),
        )
        assertThat(fixture.nativeConflictSettings).containsExactly(
            SettingsTransition(BracketGuidePreferences(), normalized),
        )
        assertThat(fixture.edtTransactions).hasSize(1)
    }

    @Test
    fun `no-op apply still reconciles native settings without runtime effects`() {
        val fixture = fixture()
        val modificationCount = fixture.settings.stateModificationCount

        fixture.controller.applySettings(BracketGuidePreferences())

        assertThat(fixture.settings.stateModificationCount).isEqualTo(modificationCount)
        assertThat(fixture.nativeSnapshots).containsExactly(BracketGuidePreferences())
        assertThat(fixture.runtimeChanges).isEmpty()
        assertThat(fixture.nativeConflictSettings).containsExactly(
            SettingsTransition(BracketGuidePreferences(), BracketGuidePreferences()),
        )
        assertThat(fixture.edtTransactions).hasSize(1)
    }

    @Test
    fun `native override correction is persisted before runtime effects`() {
        val fixture = fixture(
            reconcileNative = { options -> options.withMode(LEAVE_UNCHANGED) },
        )

        fixture.controller.applySettings(
            BracketGuidePreferences(colorBracketTokens = false),
        )

        val effective = fixture.settings.options
        assertThat(effective.colorBracketTokens).isFalse()
        assertThat(effective.intelliJIntegration.nativeHighlightMode)
            .isEqualTo(LEAVE_UNCHANGED)
        assertThat(fixture.nativeSnapshots).containsExactly(
            BracketGuidePreferences(colorBracketTokens = false),
        )
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
        assertThat(fixture.nativeConflictSettings).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
    }

    @Test
    fun `native reconciliation can commit an externally forced correction`() {
        val fixture = fixture(
            reconcileNative = { options -> options.withMode(LEAVE_UNCHANGED) },
        )

        fixture.controller.reconcileNativeSettings()

        val effective = fixture.settings.options
        assertThat(effective.intelliJIntegration.nativeHighlightMode)
            .isEqualTo(LEAVE_UNCHANGED)
        assertThat(fixture.nativeSnapshots).containsExactly(BracketGuidePreferences())
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
    }

    @Test
    fun `ownership callback updates only its still owning highlight`() {
        val initial = BracketGuidePreferences()
        val fixture = fixture(initialOptions = initial)

        fixture.controller.nativeVisualSettingsWereOverridden(
            setOf(
                NativeVisualSettingTarget.MATCHED_BRACES,
                NativeVisualSettingTarget.CURRENT_SCOPE,
            ),
        )

        val effective = fixture.settings.options
        assertThat(effective.enabled).isTrue()
        assertThat(effective.intelliJIntegration.manageNativeVisuals).isTrue()
        assertThat(effective.intelliJIntegration.nativeHighlightMode)
            .isEqualTo(LEAVE_UNCHANGED)
        assertThat(fixture.nativeSnapshots).isEmpty()
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(initial, effective),
        )
    }

    @Test
    fun `delayed matched brace cleanup cannot replace a newer scope-only mode`() {
        val newer = preferences(mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY)
        val fixture = fixture(initialOptions = newer)

        fixture.controller.nativeVisualSettingsWereOverridden(
            setOf(NativeVisualSettingTarget.MATCHED_BRACES),
        )

        assertThat(fixture.settings.options).isEqualTo(newer)
        assertThat(fixture.nativeSnapshots).isEmpty()
        assertThat(fixture.runtimeChanges).isEmpty()
    }

    @Test
    fun `delayed scope cleanup cannot replace a newer matched brace mode`() {
        val newer = BracketGuidePreferences()
        val fixture = fixture(initialOptions = newer)

        fixture.controller.nativeVisualSettingsWereOverridden(
            setOf(NativeVisualSettingTarget.CURRENT_SCOPE),
        )

        assertThat(fixture.settings.options).isEqualTo(newer)
        assertThat(fixture.runtimeChanges).isEmpty()
    }

    @Test
    fun `external callback is ignored after the parent gate is disabled`() {
        val parentOff =
            preferences(
                mode = NativeHighlightMode.SUPPRESS_CURRENT_SCOPE_ONLY,
                manageNativeVisuals = false,
            )
        val fixture = fixture(initialOptions = parentOff)

        fixture.controller.nativeVisualSettingsWereOverridden(
            NativeVisualSettingTarget.entries.toSet(),
        )

        assertThat(fixture.settings.options).isEqualTo(parentOff)
        assertThat(fixture.runtimeChanges).isEmpty()
    }

    @Test
    fun `application service entry points retain stable JVM names`() {
        val controllerClass = BracketGuideSettingsController::class.java

        assertThat(
            controllerClass.getDeclaredMethod(
                "applySettings",
                BracketGuidePreferences::class.java,
            ).name,
        ).isEqualTo("applySettings")
        assertThat(Modifier.isStatic(controllerClass.getDeclaredMethod("getInstance").modifiers))
            .isTrue()
    }

    private fun fixture(
        initialOptions: BracketGuidePreferences = BracketGuidePreferences(),
        reconcileNative: (BracketGuidePreferences) -> BracketGuidePreferences = { it },
    ): Fixture {
        val settings = BracketGuideSettings().apply { loadState(initialOptions) }
        val nativeSnapshots = mutableListOf<BracketGuidePreferences>()
        val runtimeChanges = mutableListOf<SettingsTransition>()
        val nativeConflictSettings = mutableListOf<SettingsTransition>()
        val edtTransactions = mutableListOf<Unit>()
        val controller =
            BracketGuideSettingsController(
                settings = { settings },
                applyNativeVisualSettings = { options ->
                    nativeSnapshots += options
                    reconcileNative(options)
                },
                applyRuntimeChange = { previous, current ->
                    runtimeChanges += SettingsTransition(previous, current)
                },
                runOnEdt = { action ->
                    edtTransactions += Unit
                    action()
                },
                reportNativeGuideConflictSettings = { previous, current ->
                    nativeConflictSettings += SettingsTransition(previous, current)
                },
            )
        return Fixture(
            controller = controller,
            settings = settings,
            nativeSnapshots = nativeSnapshots,
            runtimeChanges = runtimeChanges,
            nativeConflictSettings = nativeConflictSettings,
            edtTransactions = edtTransactions,
        )
    }

    private fun preferences(mode: NativeHighlightMode, manageNativeVisuals: Boolean = true): BracketGuidePreferences =
        BracketGuidePreferences(
            intelliJIntegration =
            IntelliJIntegrationPreferences(
                manageNativeVisuals = manageNativeVisuals,
                nativeHighlightMode = mode,
            ),
        )

    private fun BracketGuidePreferences.withMode(mode: NativeHighlightMode): BracketGuidePreferences = copy(
        intelliJIntegration = intelliJIntegration.copy(nativeHighlightMode = mode),
    )

    private data class Fixture(
        val controller: BracketGuideSettingsController,
        val settings: BracketGuideSettings,
        val nativeSnapshots: List<BracketGuidePreferences>,
        val runtimeChanges: List<SettingsTransition>,
        val nativeConflictSettings: List<SettingsTransition>,
        val edtTransactions: List<Unit>,
    )

    private data class SettingsTransition(val previous: BracketGuidePreferences, val current: BracketGuidePreferences)

    private companion object {
        val LEAVE_UNCHANGED = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED
    }
}
