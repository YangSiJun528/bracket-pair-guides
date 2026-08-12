package com.sijunyang.bracketpairguides.settings.ui

import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import com.sijunyang.bracketpairguides.presentation.observedBracketMarkup
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JTextField
import org.assertj.core.api.Assertions.assertThat

class BracketGuideSettingsPageTest : BasePlatformTestCase() {
    private var originalNativeMatchedBraceHighlighting = true

    override fun setUp() {
        super.setUp()
        originalNativeMatchedBraceHighlighting =
            CodeInsightSettings.getInstance().HIGHLIGHT_BRACES
        BracketGuideSettings.getInstance().loadState(BracketGuidePreferences())
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().HIGHLIGHT_BRACES =
                originalNativeMatchedBraceHighlighting
        } finally {
            super.tearDown()
        }
    }

    fun testUsesBoundPlatformControlsForEveryEditorSetting() {
        withConfigurable(emptyList()) { configurable, component ->
            val checkBoxes = component.descendants()
                .filterIsInstance<JBCheckBox>()
                .mapNotNull(JBCheckBox::getText)
                .toSet()

            assertThat(checkBoxes).contains(
                "Enabled",
                "Disable IntelliJ matched-brace highlighting",
                "Bracket colorization",
                "Active guide",
                "Horizontal",
                "Vertical",
                "Pair border",
                "Pair background",
                "Component overrides",
            )
            assertThat(
                component.descendants().filterIsInstance<ColorPanel>().size,
            ).isEqualTo(StoredColorFormat.COLOR_COUNT * 4)
            assertThat(configurable.preferredFocusedComponent).isEqualTo(component.checkBox("Enabled"))
            assertThat(configurable.isModified).isFalse()
        }
    }

    fun testDefaultPaletteShowsEveryConcreteColorWithOnlyBaseEditable() {
        withConfigurable(emptyList()) { configurable, component ->
            assertThat(component.checkBox("Component overrides").isSelected).isFalse()
            for (level in 0 until StoredColorFormat.COLOR_COUNT) {
                val expected = Color(StoredColorFormat.defaultColor(level))
                val base = component.colorPanel("base", level)
                assertThat(base.selectedColor).isEqualTo(expected)
                assertThat(base.isEnabled).isTrue()
                assertThat(base.toolTipText).isNotNull()
                assertThat(base.colorTextField().accessibleContext.accessibleName)
                    .isEqualTo("Level ${level + 1} Base color")

                for (target in listOf("guide", "border", "background")) {
                    val componentColor = component.colorPanel(target, level)
                    assertThat(componentColor.selectedColor).isEqualTo(expected)
                    assertThat(componentColor.isEnabled).isTrue()
                    assertThat(componentColor.toolTipText).isNull()
                    assertThat(componentColor.colorTextField().accessibleContext.accessibleDescription)
                        .isEqualTo(
                            "Read-only; Base is applied while Component overrides is off.",
                        )
                }
            }
            assertThat(configurable.isModified).isFalse()
        }
    }

    fun testScalarBindingsApplyAndResetWithoutWritingDraftValues() {
        withConfigurable(emptyList()) { configurable, component ->
            component.checkBox("Bracket colorization").doClick()
            component.checkBox("Disable IntelliJ matched-brace highlighting").doClick()
            component.checkBox("Horizontal").doClick()
            component.checkBox("Vertical").doClick()
            component.spinner("guideLineWidth").value = 3
            component.spinner("guideOpacityPercent").value = 65
            component.checkBox("Active guide").doClick()
            component.checkBox("Pair border").doClick()
            component.checkBox("Pair background").doClick()
            component.spinner("pairBackgroundOpacityPercent").value = 37
            component.checkBox("Enabled").doClick()

            assertThat(configurable.isModified).isTrue()
            assertThat(BracketGuideSettings.getInstance().options).isEqualTo(BracketGuidePreferences())

            configurable.apply()

            val applied = BracketGuideSettings.getInstance().options
            assertThat(applied.enabled).isFalse()
            assertThat(applied.disableNativeMatchedBraceHighlighting).isFalse()
            assertThat(applied.colorBracketTokens).isFalse()
            assertThat(applied.showActiveGuide).isFalse()
            assertThat(applied.showHorizontalGuides).isFalse()
            assertThat(applied.showVerticalGuide).isFalse()
            assertThat(applied.guideLineWidth).isEqualTo(3)
            assertThat(applied.guideOpacityPercent).isEqualTo(65)
            assertThat(applied.showActivePairBorder).isTrue()
            assertThat(applied.showActivePairBackground).isTrue()
            assertThat(applied.pairBackgroundOpacityPercent).isEqualTo(37)
            assertThat(configurable.isModified).isFalse()

            component.checkBox("Enabled").doClick()
            assertThat(configurable.isModified).isTrue()
            configurable.reset()
            assertThat(component.checkBox("Enabled").isSelected).isFalse()
            assertThat(configurable.isModified).isFalse()
        }
    }

    fun testWarnsOnlyWhenNativeMatchedBraceHighlightingIsAllowed() {
        withConfigurable(emptyList()) { _, component ->
            val disableNative =
                component.checkBox("Disable IntelliJ matched-brace highlighting")
            val warning = component.editorPane("nativeMatchedBraceWarning")

            assertThat(warning.text).contains(
                "This mode is not tested and may not match the intended appearance.",
            )

            assertThat(disableNative.isSelected).isTrue()
            assertThat(warning.isVisible).isFalse()

            disableNative.doClick()

            assertThat(disableNative.isSelected).isFalse()
            assertThat(warning.isVisible).isTrue()
        }
    }

    fun testLanguageBindingsPreserveUnavailableDisabledIds() {
        val languages = listOf(
            ALPHA_FAMILY,
            BETA_FAMILY,
        )
        BracketGuideSettings.getInstance().loadState(
            BracketGuidePreferences(
                disabledLanguageIds = setOf(UNAVAILABLE_LANGUAGE_ID, BETA_LANGUAGE_ID),
            ),
        )

        withConfigurable(languages) { configurable, component ->
            val alpha = component.languageCheckBox(ALPHA_LANGUAGE_ID)
            val beta = component.languageCheckBox(BETA_LANGUAGE_ID)
            assertThat(alpha.isSelected).isTrue()
            assertThat(beta.isSelected).isFalse()
            assertThat(beta.toolTipText).contains("Beta Dialect")

            alpha.doClick()
            beta.doClick()
            configurable.apply()

            assertThat(
                BracketGuideSettings.getInstance().options.disabledLanguageIds,
            ).isEqualTo(setOf(UNAVAILABLE_LANGUAGE_ID, ALPHA_LANGUAGE_ID))
            assertThat(configurable.isModified).isFalse()
        }
    }

    fun testCustomFileTypeLanguageUsesSettingsSpecificLabelAndConstraint() {
        withConfigurable(listOf(TEXT_FAMILY)) {
                _, component ->
            val language = component.languageCheckBox("TEXT")

            assertThat(language.text).isEqualTo("Custom file types")
            assertThat(language.toolTipText).contains("raw plain text")
        }
    }

    fun testComponentOverrideTogglePreservesVisibleColorsAndControlsEditing() {
        BracketGuideSettings.getInstance().loadState(
            BracketGuidePreferences(
                useIndependentComponentColors = false,
                levelBaseColors = colorsWithFirst(0x123456),
                guideLineColors = colorsWithFirst(0x234567),
                pairBorderColors = colorsWithFirst(0x345678),
                pairBackgroundColors = colorsWithFirst(0x456789),
            ),
        )

        withConfigurable(emptyList()) { configurable, component ->
            val base = component.colorPanel("base", 0)
            val guide = component.colorPanel("guide", 0)
            assertThat(base.selectedColor).isEqualTo(Color(0x123456))
            assertThat(guide.selectedColor).isEqualTo(Color(0x234567))
            assertThat(guide.isEnabled).isTrue()
            assertThat(guide.toolTipText).isNull()

            component.checkBox("Component overrides").doClick()
            assertThat(guide.isEnabled).isTrue()
            assertThat(guide.selectedColor).isEqualTo(Color(0x234567))
            assertThat(guide.toolTipText).isNotNull()
            assertThat(guide.colorTextField().accessibleContext.accessibleDescription)
                .isEqualTo("Editable component override.")

            base.selectedColor = Color(0x654321)
            guide.selectedColor = Color(0x765432)
            component.colorPanel("border", 0).selectedColor = Color(0x102030)
            component.colorPanel("background", 0).selectedColor = Color(0x203040)
            assertThat(configurable.isModified).isTrue()
            configurable.apply()

            val applied = BracketGuideSettings.getInstance().options
            assertThat(applied.levelBaseColors[0]).isEqualTo(0x654321)
            assertThat(applied.guideLineColors[0]).isEqualTo(0x765432)
            assertThat(applied.pairBorderColors[0]).isEqualTo(0x102030)
            assertThat(applied.pairBackgroundColors[0]).isEqualTo(0x203040)
            assertThat(applied.useIndependentComponentColors).isTrue()

            component.checkBox("Component overrides").doClick()
            assertThat(guide.isEnabled).isTrue()
            assertThat(guide.selectedColor).isEqualTo(Color(0x765432))
            assertThat(guide.toolTipText).isNull()
            configurable.apply()
            assertThat(BracketGuideSettings.getInstance().options.useIndependentComponentColors).isFalse()
            assertThat(BracketGuideSettings.getInstance().options.pairBorderColors[0]).isEqualTo(0x102030)
        }
    }

    fun testResetRestoresAppliedColorsOverridesAndEditability() {
        val applied = BracketGuidePreferences(
            useIndependentComponentColors = true,
            levelBaseColors = colorsWithFirst(0x123456),
            guideLineColors = colorsWithFirst(0x234567),
            pairBorderColors = colorsWithFirst(0x345678),
            pairBackgroundColors = colorsWithFirst(0x456789),
        )
        BracketGuideSettings.getInstance().loadState(applied)

        withConfigurable(emptyList()) { configurable, component ->
            val overrides = component.checkBox("Component overrides")
            val guide = component.colorPanel("guide", 0)
            assertThat(overrides.isSelected).isTrue()
            assertThat(guide.toolTipText).isNotNull()

            guide.selectedColor = Color(0xABCDEF)
            overrides.doClick()
            assertThat(configurable.isModified).isTrue()
            assertThat(BracketGuideSettings.getInstance().options).isEqualTo(applied)

            configurable.reset()

            assertThat(guide.selectedColor).isEqualTo(Color(0x234567))
            assertThat(overrides.isSelected).isTrue()
            assertThat(guide.toolTipText).isNotNull()
            assertThat(guide.colorTextField().accessibleContext.accessibleDescription)
                .isEqualTo("Editable component override.")
            assertThat(configurable.isModified).isFalse()
            assertThat(BracketGuideSettings.getInstance().options).isEqualTo(applied)
        }
    }

    fun testResetColorsRestoresConcreteDefaultsAndDisablesOverrides() {
        BracketGuideSettings.getInstance().loadState(
            BracketGuidePreferences(
                useIndependentComponentColors = true,
                levelBaseColors = List(StoredColorFormat.COLOR_COUNT) { 0x101010 + it },
                guideLineColors = List(StoredColorFormat.COLOR_COUNT) { 0x202020 + it },
                pairBorderColors = List(StoredColorFormat.COLOR_COUNT) { 0x303030 + it },
                pairBackgroundColors = List(StoredColorFormat.COLOR_COUNT) { 0x404040 + it },
            ),
        )

        withConfigurable(emptyList()) { configurable, component ->
            component.button("Reset colors").doClick()
            assertThat(component.checkBox("Component overrides").isSelected).isFalse()
            for (level in 0 until StoredColorFormat.COLOR_COUNT) {
                val expected = Color(StoredColorFormat.defaultColor(level))
                for (target in listOf("base", "guide", "border", "background")) {
                    assertThat(component.colorPanel(target, level).selectedColor)
                        .isEqualTo(expected)
                }
                assertThat(component.colorPanel("guide", level).toolTipText).isNull()
            }
            configurable.apply()
            val reset = BracketGuideSettings.getInstance().options
            assertThat(reset.useIndependentComponentColors).isFalse()
            assertThat(reset.levelBaseColors).isEqualTo(StoredColorFormat.defaultColors())
            assertThat(reset.guideLineColors).isEqualTo(StoredColorFormat.defaultColors())
            assertThat(reset.pairBorderColors).isEqualTo(StoredColorFormat.defaultColors())
            assertThat(reset.pairBackgroundColors).isEqualTo(StoredColorFormat.defaultColors())
        }
    }

    fun testApplyingLanguageChangeDoesNotSynthesizeEditorPresentation() {
        val language = ALPHA_FAMILY
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("{ value }")
        val firstEditor = editorFactory.createEditor(document, project)
        val secondEditor = editorFactory.createEditor(document, project)
        try {
            EditorGuideSessions.install(
                editor = firstEditor,
                visibleRange = { TextRange(0, document.textLength) },
                preferences = BracketGuideSettings.getInstance().options,
            )
            EditorGuideSessions.install(
                editor = secondEditor,
                visibleRange = { TextRange(0, document.textLength) },
                preferences = BracketGuideSettings.getInstance().options,
            )

            withConfigurable(listOf(language)) { configurable, component ->
                component.languageCheckBox(ALPHA_LANGUAGE_ID).doClick()
                configurable.apply()
            }

            assertThat(firstEditor.observedBracketMarkup().allMarks).isEmpty()
            assertThat(secondEditor.observedBracketMarkup().allMarks).isEmpty()
        } finally {
            EditorGuideSessions.dispose(firstEditor)
            EditorGuideSessions.dispose(secondEditor)
            editorFactory.releaseEditor(firstEditor)
            editorFactory.releaseEditor(secondEditor)
        }
    }

    private inline fun withConfigurable(
        supportedLanguages: List<BraceLanguageFamily>,
        block: (BracketGuideSettingsPage, Component) -> Unit,
    ) {
        val configurable = BracketGuideSettingsPage { supportedLanguages }
        val component = configurable.createComponent()
        try {
            block(configurable, component)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun Component.checkBox(text: String): JBCheckBox = descendants()
        .filterIsInstance<JBCheckBox>()
        .single { it.text == text }

    private fun Component.languageCheckBox(languageId: String): JBCheckBox = descendants()
        .filterIsInstance<JBCheckBox>()
        .single { it.name == "language.$languageId" }

    private fun Component.spinner(name: String): JBIntSpinner = descendants()
        .filterIsInstance<JBIntSpinner>()
        .single { it.name == name }

    private fun Component.colorPanel(target: String, level: Int): ColorPanel = descendants()
        .filterIsInstance<ColorPanel>()
        .single { it.name == "color.$target.$level" }

    private fun ColorPanel.colorTextField(): JTextField = components
        .filterIsInstance<JTextField>()
        .single()

    private fun Component.button(text: String): JButton = descendants()
        .filterIsInstance<JButton>()
        .single { it.text == text }

    private fun Component.editorPane(name: String): JEditorPane = descendants()
        .filterIsInstance<JEditorPane>()
        .single { it.name == name }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { addAll(it.descendants()) }
        }
    }

    private fun colorsWithFirst(color: Int): List<Int> =
        StoredColorFormat.defaultColors().mapIndexed { index, defaultColor ->
            if (index == 0) color else defaultColor
        }

    private companion object {
        const val UNAVAILABLE_LANGUAGE_ID = "BRACKET_PAIR_GUIDES_UNAVAILABLE_TEST"
        const val ALPHA_LANGUAGE_ID = "BRACKET_PAIR_GUIDES_ALPHA_TEST"
        const val BETA_LANGUAGE_ID = "BRACKET_PAIR_GUIDES_BETA_TEST"

        val ALPHA_FAMILY = BraceLanguageFamily(
            id = ALPHA_LANGUAGE_ID,
            displayName = "Alpha",
            memberDisplayNames = listOf("Alpha"),
        )
        val BETA_FAMILY = BraceLanguageFamily(
            id = BETA_LANGUAGE_ID,
            displayName = "Beta",
            memberDisplayNames = listOf("Beta", "Beta Dialect"),
        )
        val TEXT_FAMILY = BraceLanguageFamily(
            id = "TEXT",
            displayName = "Plain text",
            memberDisplayNames = listOf("Plain text"),
        )
    }
}
