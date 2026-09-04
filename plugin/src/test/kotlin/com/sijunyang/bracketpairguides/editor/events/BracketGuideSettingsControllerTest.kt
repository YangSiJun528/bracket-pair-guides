package com.sijunyang.bracketpairguides.editor.events

import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
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
        assertThat(fixture.edtTransactions).hasSize(1)
    }

    @Test
    fun `no-op apply has no native editor or daemon effects`() {
        val fixture = fixture()
        val modificationCount = fixture.settings.stateModificationCount

        fixture.controller.applySettings(BracketGuidePreferences())

        assertThat(fixture.settings.stateModificationCount).isEqualTo(modificationCount)
        assertThat(fixture.nativeSnapshots).isEmpty()
        assertThat(fixture.runtimeChanges).isEmpty()
        assertThat(fixture.edtTransactions).hasSize(1)
    }

    @Test
    fun `native override correction is persisted before runtime effects`() {
        val fixture = fixture(
            reconcileNative = { options ->
                options.copy(disableNativeMatchedBraceHighlighting = false)
            },
        )

        fixture.controller.applySettings(
            BracketGuidePreferences(colorBracketTokens = false),
        )

        val effective = fixture.settings.options
        assertThat(effective.colorBracketTokens).isFalse()
        assertThat(effective.disableNativeMatchedBraceHighlighting).isFalse()
        assertThat(fixture.nativeSnapshots).containsExactly(
            BracketGuidePreferences(colorBracketTokens = false),
        )
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
    }

    @Test
    fun `native reconciliation can commit an externally forced correction`() {
        val fixture = fixture(
            reconcileNative = { options ->
                options.copy(disableNativeMatchedBraceHighlighting = false)
            },
        )

        fixture.controller.reconcileNativeSettings()

        val effective = fixture.settings.options
        assertThat(effective.disableNativeMatchedBraceHighlighting).isFalse()
        assertThat(fixture.nativeSnapshots).containsExactly(BracketGuidePreferences())
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
    }

    @Test
    fun `native ownership callback commits without recursively reconciling native state`() {
        val fixture = fixture()

        fixture.controller.nativeMatchedBraceSettingWasOverridden()

        val effective = fixture.settings.options
        assertThat(effective.disableNativeMatchedBraceHighlighting).isFalse()
        assertThat(fixture.nativeSnapshots).isEmpty()
        assertThat(fixture.runtimeChanges).containsExactly(
            SettingsTransition(BracketGuidePreferences(), effective),
        )
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

    private fun fixture(reconcileNative: (BracketGuidePreferences) -> BracketGuidePreferences = { it }): Fixture {
        val settings = BracketGuideSettings()
        val nativeSnapshots = mutableListOf<BracketGuidePreferences>()
        val runtimeChanges = mutableListOf<SettingsTransition>()
        val edtTransactions = mutableListOf<Unit>()
        val controller =
            BracketGuideSettingsController(
                settings = { settings },
                applyNativeMatchedBraceSetting = { options ->
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
            )
        return Fixture(
            controller = controller,
            settings = settings,
            nativeSnapshots = nativeSnapshots,
            runtimeChanges = runtimeChanges,
            edtTransactions = edtTransactions,
        )
    }

    private data class Fixture(
        val controller: BracketGuideSettingsController,
        val settings: BracketGuideSettings,
        val nativeSnapshots: List<BracketGuidePreferences>,
        val runtimeChanges: List<SettingsTransition>,
        val edtTransactions: List<Unit>,
    )

    private data class SettingsTransition(val previous: BracketGuidePreferences, val current: BracketGuidePreferences)
}
