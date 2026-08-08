package com.sijunyang.bracketpairguides.settings.ui

import com.sijunyang.bracketpairguides.analysis.api.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.api.FakeBracketEngine
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.sijunyang.bracketpairguides.settings.StoredBracketColors
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

class PluginConfigurableTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginOptions())
    }

    fun testUsesBoundPlatformControlsForEveryEditorSetting() {
        withConfigurable(emptyList()) { configurable, component ->
            val checkBoxes = component.descendants()
                .filterIsInstance<JBCheckBox>()
                .mapNotNull(JBCheckBox::getText)
                .toSet()

            assertTrue("Enabled" in checkBoxes)
            assertTrue("Bracket colorization" in checkBoxes)
            assertTrue("Active guide" in checkBoxes)
            assertTrue("Horizontal" in checkBoxes)
            assertTrue("Vertical" in checkBoxes)
            assertTrue("Pair border" in checkBoxes)
            assertTrue("Pair background" in checkBoxes)
            assertTrue("Component overrides" in checkBoxes)
            assertEquals(
                StoredBracketColors.COLOR_COUNT * 4,
                component.descendants().filterIsInstance<ColorPanel>().size,
            )
            assertEquals(
                component.checkBox("Enabled"),
                configurable.preferredFocusedComponent,
            )
            assertFalse(configurable.isModified)
        }
    }

    fun testScalarBindingsApplyAndResetWithoutWritingDraftValues() {
        withConfigurable(emptyList()) { configurable, component ->
            component.checkBox("Bracket colorization").doClick()
            component.checkBox("Horizontal").doClick()
            component.checkBox("Vertical").doClick()
            component.spinner("guideLineWidth").value = 3
            component.spinner("guideOpacityPercent").value = 65
            component.checkBox("Active guide").doClick()
            component.checkBox("Pair border").doClick()
            component.checkBox("Pair background").doClick()
            component.spinner("pairBackgroundOpacityPercent").value = 37
            component.checkBox("Enabled").doClick()

            assertTrue(configurable.isModified)
            assertEquals(PluginOptions(), PluginSettings.getInstance().options)

            configurable.apply()

            val applied = PluginSettings.getInstance().options
            assertFalse(applied.enabled)
            assertFalse(applied.colorBracketTokens)
            assertFalse(applied.showActiveGuide)
            assertFalse(applied.showHorizontalGuides)
            assertFalse(applied.showVerticalGuide)
            assertEquals(3, applied.guideLineWidth)
            assertEquals(65, applied.guideOpacityPercent)
            assertTrue(applied.showActivePairBorder)
            assertTrue(applied.showActivePairBackground)
            assertEquals(37, applied.pairBackgroundOpacityPercent)
            assertFalse(configurable.isModified)

            component.checkBox("Enabled").doClick()
            assertTrue(configurable.isModified)
            configurable.reset()
            assertFalse(component.checkBox("Enabled").isSelected)
            assertFalse(configurable.isModified)
        }
    }

    fun testLanguageBindingsPreserveUnavailableDisabledIds() {
        val languages = listOf(
            ALPHA_FAMILY,
            BETA_FAMILY,
        )
        PluginSettings.getInstance().loadState(
            PluginOptions(
                disabledLanguageIds = setOf(UNAVAILABLE_LANGUAGE_ID, BETA_LANGUAGE_ID),
            ),
        )

        withConfigurable(languages) { configurable, component ->
            val alpha = component.languageCheckBox(ALPHA_LANGUAGE_ID)
            val beta = component.languageCheckBox(BETA_LANGUAGE_ID)
            assertTrue(alpha.isSelected)
            assertFalse(beta.isSelected)
            assertTrue(beta.toolTipText.contains("Beta Dialect"))

            alpha.doClick()
            beta.doClick()
            configurable.apply()

            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID, ALPHA_LANGUAGE_ID),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
            assertFalse(configurable.isModified)
        }
    }

    fun testCustomFileTypeLanguageUsesSettingsSpecificLabelAndConstraint() {
        withConfigurable(listOf(TEXT_FAMILY)) {
                _, component ->
            val language = component.languageCheckBox("TEXT")

            assertEquals("Custom file types", language.text)
            assertTrue(language.toolTipText.contains("raw plain text"))
        }
    }

    fun testStandardColorPanelsPreserveAutomaticAndOverrideSemantics() {
        PluginSettings.getInstance().loadState(
            PluginOptions(
                useIndependentComponentColors = true,
                levelBaseColors = colorsWithFirst(0x123456),
                guideLineColors = colorsWithFirst(0x234567),
                pairBorderColors = colorsWithFirst(0x345678),
                pairBackgroundColors = colorsWithFirst(0x456789),
            ),
        )

        withConfigurable(emptyList()) { configurable, component ->
            val base = component.colorPanel("base", 0)
            val guide = component.colorPanel("guide", 0)
            assertEquals(Color(0x123456), base.selectedColor)
            assertEquals(Color(0x234567), guide.selectedColor)
            assertTrue(guide.isEnabled)

            base.selectedColor = Color(0x654321)
            guide.selectedColor = null
            component.colorPanel("border", 0).selectedColor = Color(0x102030)
            component.colorPanel("background", 0).selectedColor = Color(0x203040)
            assertTrue(configurable.isModified)
            configurable.apply()

            val applied = PluginSettings.getInstance().options
            assertEquals(0x654321, applied.levelBaseColors[0])
            assertEquals(
                StoredBracketColors.AUTOMATIC_COLOR,
                applied.guideLineColors[0],
            )
            assertEquals(0x102030, applied.pairBorderColors[0])
            assertEquals(0x203040, applied.pairBackgroundColors[0])

            component.checkBox("Component overrides").doClick()
            assertFalse(guide.isEnabled)
            configurable.apply()
            assertFalse(PluginSettings.getInstance().options.useIndependentComponentColors)
            assertEquals(0x102030, PluginSettings.getInstance().options.pairBorderColors[0])

            component.button("Reset colors").doClick()
            assertTrue(
                component.descendants().filterIsInstance<ColorPanel>()
                    .all { it.selectedColor == null },
            )
            configurable.apply()
            val reset = PluginSettings.getInstance().options
            assertFalse(reset.useIndependentComponentColors)
            assertTrue(reset.levelBaseColors.all(::isAutomatic))
            assertTrue(reset.guideLineColors.all(::isAutomatic))
            assertTrue(reset.pairBorderColors.all(::isAutomatic))
            assertTrue(reset.pairBackgroundColors.all(::isAutomatic))
        }
    }

    fun testApplyingLanguageChangeRunsOneImmediateResolverAcrossEditors() {
        val language = ALPHA_FAMILY
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("{ value }")
        val firstEditor = editorFactory.createEditor(document, project)
        val secondEditor = editorFactory.createEditor(document, project)
        val firstEngine = FakeBracketEngine()
        val secondEngine = FakeBracketEngine()
        try {
            EditorGuideSession.install(
                editor = firstEditor,
                engine = firstEngine,
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )
            EditorGuideSession.install(
                editor = secondEditor,
                engine = secondEngine,
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )

            withConfigurable(listOf(language)) { configurable, component ->
                component.languageCheckBox(ALPHA_LANGUAGE_ID).doClick()
                configurable.apply()
            }

            assertEquals(
                1,
                firstEngine.activePairCallCount + secondEngine.activePairCallCount,
            )
        } finally {
            EditorGuideSession.dispose(firstEditor)
            EditorGuideSession.dispose(secondEditor)
            editorFactory.releaseEditor(firstEditor)
            editorFactory.releaseEditor(secondEditor)
        }
    }

    private inline fun withConfigurable(
        supportedLanguages: List<BraceLanguageFamily>,
        block: (PluginConfigurable, Component) -> Unit,
    ) {
        val configurable = PluginConfigurable.forTest { supportedLanguages }
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

    private fun Component.button(text: String): JButton = descendants()
        .filterIsInstance<JButton>()
        .single { it.text == text }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { addAll(it.descendants()) }
        }
    }

    private fun colorsWithFirst(color: Int): List<Int> =
        List(StoredBracketColors.COLOR_COUNT) { index ->
            if (index == 0) color else StoredBracketColors.AUTOMATIC_COLOR
        }

    private fun isAutomatic(color: Int): Boolean =
        color == StoredBracketColors.AUTOMATIC_COLOR

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
