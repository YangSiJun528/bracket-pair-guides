package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analysis.pairing.SupportedBraceLanguage
import com.sijunyang.bracketpairguides.analysis.ActiveBracketPairResolution
import com.sijunyang.bracketpairguides.analysis.ActiveBracketPairResolver
import com.sijunyang.bracketpairguides.editor.EditorGuideSession
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
                BracketColorPalette.COLOR_COUNT * 4,
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
            SupportedBraceLanguage("alpha", "Alpha", listOf("Alpha")),
            SupportedBraceLanguage("beta", "Beta", listOf("Beta", "Beta Dialect")),
        )
        PluginSettings.getInstance().loadState(
            PluginOptions(
                disabledLanguageIds = setOf(UNAVAILABLE_LANGUAGE_ID, "beta"),
            ),
        )

        withConfigurable(languages) { configurable, component ->
            val alpha = component.languageCheckBox("alpha")
            val beta = component.languageCheckBox("beta")
            assertTrue(alpha.isSelected)
            assertFalse(beta.isSelected)
            assertTrue(beta.toolTipText.contains("Beta Dialect"))

            alpha.doClick()
            beta.doClick()
            configurable.apply()

            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID, "alpha"),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
            assertFalse(configurable.isModified)
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
                BracketColorPalette.AUTOMATIC_COLOR,
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
        val language = SupportedBraceLanguage("alpha", "Alpha", listOf("Alpha"))
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("{ value }")
        val firstEditor = editorFactory.createEditor(document, project)
        val secondEditor = editorFactory.createEditor(document, project)
        var firstResolverCalls = 0
        var secondResolverCalls = 0
        try {
            EditorGuideSession.install(
                firstEditor,
                resolver = ActiveBracketPairResolver { _, _ ->
                    firstResolverCalls++
                    ActiveBracketPairResolution.Complete(null)
                },
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )
            EditorGuideSession.install(
                secondEditor,
                resolver = ActiveBracketPairResolver { _, _ ->
                    secondResolverCalls++
                    ActiveBracketPairResolution.Complete(null)
                },
                visibleRangeProvider = { TextRange(0, document.textLength) },
            )

            withConfigurable(listOf(language)) { configurable, component ->
                component.languageCheckBox("alpha").doClick()
                configurable.apply()
            }

            assertEquals(1, firstResolverCalls + secondResolverCalls)
        } finally {
            EditorGuideSession.dispose(firstEditor)
            EditorGuideSession.dispose(secondEditor)
            editorFactory.releaseEditor(firstEditor)
            editorFactory.releaseEditor(secondEditor)
        }
    }

    private inline fun withConfigurable(
        supportedLanguages: List<SupportedBraceLanguage>,
        block: (PluginConfigurable, Component) -> Unit,
    ) {
        val configurable = PluginConfigurable { supportedLanguages }
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
        List(BracketColorPalette.COLOR_COUNT) { index ->
            if (index == 0) color else BracketColorPalette.AUTOMATIC_COLOR
        }

    private fun isAutomatic(color: Int): Boolean =
        color == BracketColorPalette.AUTOMATIC_COLOR

    private companion object {
        const val UNAVAILABLE_LANGUAGE_ID = "BRACKET_PAIR_GUIDES_UNAVAILABLE_TEST"
    }
}
