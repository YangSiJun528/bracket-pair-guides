package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.renderer.AnalysisCapabilities
import com.sijunyang.bracketpairguides.renderer.EditorGuideSession
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.and
import com.intellij.ui.layout.or
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.ChangeListener

/** One settings page owns both feature switches and every visual color. */
internal class PluginConfigurable : Configurable, Configurable.NoScroll {
    private lateinit var enabled: JBCheckBox
    private lateinit var colorBracketTokens: JBCheckBox
    private lateinit var showActiveGuide: JBCheckBox
    private lateinit var showVerticalGuide: JBCheckBox
    private lateinit var showHorizontalGuides: JBCheckBox
    private lateinit var guideLineWidth: JSpinner
    private lateinit var guideOpacityPercent: JSpinner
    private lateinit var showActivePairBorder: JBCheckBox
    private lateinit var showActivePairBackground: JBCheckBox
    private lateinit var pairBackgroundOpacityPercent: JSpinner
    private lateinit var useIndependentComponentColors: JBCheckBox
    private lateinit var resetColors: JButton
    private lateinit var paletteTable: ColorPaletteTable
    private var preview: BracketSettingsPreview? = null
    private var panel: JComponent? = null
    private var resetSnapshot: PluginOptions? = null
    private var baseColorsAreAutomatic = BooleanArray(BracketColorPalette.COLOR_COUNT)
    private var componentColorsAreAutomatic = Array(PaletteComponent.entries.size) {
        BooleanArray(BracketColorPalette.COLOR_COUNT) { true }
    }
    private var controlsCreated = false
    private var updatingUi = false

    override fun getDisplayName(): String = "Bracket Pair Guides"

    override fun createComponent(): JComponent {
        panel?.let { return it }
        createControls()
        val previewComponent = checkNotNull(preview)
        val controlsPanel = panel {
            row {
                cell(enabled)
            }

            group("Appearance") {
                row {
                    cell(colorBracketTokens)
                }.enabledIf(enabled.selected)

                row {
                    cell(showActiveGuide)
                }.enabledIf(enabled.selected)
                indent {
                    row("Segments:") {
                        cell(showHorizontalGuides)
                        cell(showVerticalGuide)
                    }.enabledIf(enabled.selected.and(showActiveGuide.selected))
                    rowsRange {
                        row("Width:") {
                            cell(guideLineWidth)
                            label("px")
                        }
                        row("Opacity:") {
                            cell(guideOpacityPercent)
                            label("%")
                        }
                    }.enabledIf(
                        enabled.selected
                            .and(showActiveGuide.selected)
                            .and(
                                showVerticalGuide.selected.or(
                                    showHorizontalGuides.selected,
                                ),
                            ),
                    )
                }

                row {
                    cell(showActivePairBorder)
                }.enabledIf(enabled.selected)

                row {
                    cell(showActivePairBackground)
                }.enabledIf(enabled.selected)
                indent {
                    row("Opacity:") {
                        cell(pairBackgroundOpacityPercent)
                        label("%")
                    }.enabledIf(enabled.selected.and(showActivePairBackground.selected))
                }
            }

            group("Colors") {
                row {
                    cell(useIndependentComponentColors)
                }.enabledIf(enabled.selected).rowComment(
                    "Guide, border, and background overrides.",
                )
                row {
                    cell(paletteTable)
                }.enabledIf(enabled.selected).rowComment(
                    "Levels 7+ repeat.",
                )
                row {
                    cell(resetColors)
                }.enabledIf(enabled.selected)
            }

        }
        val controlsScrollPane = JBScrollPane(
            controlsPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            minimumSize = JBUI.size(CONTROLS_MINIMUM_WIDTH, PAGE_MINIMUM_HEIGHT)
            preferredSize = JBUI.size(CONTROLS_PREFERRED_WIDTH, PAGE_PREFERRED_HEIGHT)
        }
        val result = OnePixelSplitter(
            false,
            SPLITTER_PROPORTION_KEY,
            DEFAULT_SPLITTER_PROPORTION,
        ).apply {
            firstComponent = controlsScrollPane
            secondComponent = previewComponent
            dividerPositionStrategy =
                Splitter.DividerPositionStrategy.KEEP_FIRST_SIZE
            lackOfSpaceStrategy =
                Splitter.LackOfSpaceStrategy.HONOR_THE_FIRST_MIN_SIZE
            setHonorComponentsMinimumSize(true)
            isShowDividerControls = true
            minimumSize = JBUI.size(PAGE_MINIMUM_WIDTH, PAGE_MINIMUM_HEIGHT)
            preferredSize = JBUI.size(PAGE_PREFERRED_WIDTH, PAGE_PREFERRED_HEIGHT)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        panel = result
        wireListeners()
        reset()
        return result
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        enabled.takeIf { controlsCreated }

    override fun isModified(): Boolean {
        return controlsCreated && captureDraftState() != resetSnapshot
    }

    override fun apply() {
        if (!controlsCreated) return
        val settings = PluginSettings.getInstance()
        val previous = settings.options
        val draft = captureDraftState()
        val capabilitiesChanged = AnalysisCapabilities.from(previous) !=
            AnalysisCapabilities.from(draft)
        settings.replace(draft)

        for (editor in EditorFactory.getInstance().allEditors) {
            EditorGuideSession.get(editor)?.updateOptions(draft)
        }
        if (capabilitiesChanged) {
            for (project in ProjectManager.getInstance().openProjects) {
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }
        resetSnapshot = captureDraftState()
    }

    override fun reset() {
        if (!controlsCreated) return
        val state = PluginSettings.getInstance().options
        val scheme = EditorColorsManager.getInstance().globalScheme
        updatingUi = true
        try {
            enabled.isSelected = state.enabled
            colorBracketTokens.isSelected = state.colorBracketTokens
            showActiveGuide.isSelected = state.showActiveGuide
            showVerticalGuide.isSelected = state.showVerticalGuide
            showHorizontalGuides.isSelected = state.showHorizontalGuides
            guideLineWidth.value = state.guideLineWidth
            guideOpacityPercent.value = state.guideOpacityPercent
            showActivePairBorder.isSelected = state.showActivePairBorder
            showActivePairBackground.isSelected = state.showActivePairBackground
            pairBackgroundOpacityPercent.value = state.pairBackgroundOpacityPercent
            useIndependentComponentColors.isSelected =
                state.useIndependentComponentColors

            for (index in 0 until BracketColorPalette.COLOR_COUNT) {
                baseColorsAreAutomatic[index] =
                    BracketColorPalette.storedColor(state.levelBaseColors.getOrNull(index)) == null
                val baseColor = BracketColorPalette.baseColor(scheme, state, index)
                paletteTable.setColor(
                    index,
                    PaletteComponent.BASE,
                    baseColor,
                )
                loadComponentColor(
                    index,
                    PaletteComponent.GUIDE,
                    state.guideLineColors,
                    baseColor,
                )
                loadComponentColor(
                    index,
                    PaletteComponent.BORDER,
                    state.pairBorderColors,
                    baseColor,
                )
                loadComponentColor(
                    index,
                    PaletteComponent.BACKGROUND,
                    state.pairBackgroundColors,
                    baseColor,
                )
            }
        } finally {
            updatingUi = false
        }
        updateControlStates()
        refreshPreview()
        resetSnapshot = captureDraftState()
    }

    override fun disposeUIResources() {
        preview?.dispose()
        preview = null
        panel = null
        resetSnapshot = null
        controlsCreated = false
    }

    private fun createControls() {
        enabled = JBCheckBox("Enabled")
        colorBracketTokens = JBCheckBox("Bracket colorization")
        showActiveGuide = JBCheckBox("Active guide")
        showVerticalGuide = JBCheckBox("Vertical")
        showHorizontalGuides = JBCheckBox("Horizontal")
        guideLineWidth = numberSpinner(
            PluginSettings.DEFAULT_GUIDE_LINE_WIDTH,
            PluginSettings.MIN_GUIDE_LINE_WIDTH,
            PluginSettings.MAX_GUIDE_LINE_WIDTH,
            1,
        )
        guideOpacityPercent = numberSpinner(
            PluginSettings.DEFAULT_GUIDE_OPACITY_PERCENT,
            PluginSettings.MIN_GUIDE_OPACITY_PERCENT,
            PluginSettings.MAX_GUIDE_OPACITY_PERCENT,
            5,
        )
        showActivePairBorder = JBCheckBox("Pair border")
        showActivePairBackground = JBCheckBox("Pair background")
        pairBackgroundOpacityPercent = numberSpinner(
            PluginSettings.DEFAULT_PAIR_BACKGROUND_OPACITY_PERCENT,
            PluginSettings.MIN_PAIR_BACKGROUND_OPACITY_PERCENT,
            PluginSettings.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
            1,
        )
        useIndependentComponentColors =
            JBCheckBox("Component overrides")
        resetColors = JButton("Reset colors")
        paletteTable = ColorPaletteTable(
            disabledReason = ::paletteDisabledReason,
            onColorChanged = ::paletteColorChanged,
        )
        preview = BracketSettingsPreview()
        baseColorsAreAutomatic = BooleanArray(BracketColorPalette.COLOR_COUNT)
        componentColorsAreAutomatic = Array(PaletteComponent.entries.size) {
            BooleanArray(BracketColorPalette.COLOR_COUNT) { true }
        }
        controlsCreated = true
    }

    private fun wireListeners() {
        for (button in listOf<AbstractButton>(
            enabled,
            colorBracketTokens,
            showActiveGuide,
            showVerticalGuide,
            showHorizontalGuides,
            showActivePairBorder,
            showActivePairBackground,
        )) {
            button.addActionListener {
                if (updatingUi) return@addActionListener
                updateControlStates()
                refreshPreview()
            }
        }
        useIndependentComponentColors.addActionListener {
            if (updatingUi) return@addActionListener
            refreshAutomaticComponentColorsFromBase()
            updateControlStates()
            refreshPreview()
        }
        val spinnerListener = ChangeListener {
            if (!updatingUi) refreshPreview()
        }
        guideLineWidth.addChangeListener(spinnerListener)
        guideOpacityPercent.addChangeListener(spinnerListener)
        pairBackgroundOpacityPercent.addChangeListener(spinnerListener)
        resetColors.addActionListener { resetPaletteToThemeDefaults() }
    }

    private fun paletteDisabledReason(component: PaletteComponent): String? {
        if (!controlsCreated || !enabled.isSelected) return "Plugin is disabled"
        if (
            component != PaletteComponent.BASE &&
            !useIndependentComponentColors.isSelected
        ) {
            return "Inherited from Base"
        }
        return when (component) {
            PaletteComponent.BASE -> null
            PaletteComponent.GUIDE -> when {
                !showActiveGuide.isSelected -> "Active guide is disabled"
                !showVerticalGuide.isSelected && !showHorizontalGuides.isSelected ->
                    "No guide segments are enabled"
                else -> null
            }
            PaletteComponent.BORDER ->
                if (showActivePairBorder.isSelected) null else "Pair border is disabled"
            PaletteComponent.BACKGROUND ->
                if (showActivePairBackground.isSelected) null else {
                    "Pair background is disabled"
                }
        }
    }

    private fun paletteColorChanged(
        level: Int,
        component: PaletteComponent,
        color: Color,
    ) {
        if (updatingUi) return
        if (component == PaletteComponent.BASE) {
            baseColorsAreAutomatic[level] = false
            refreshAutomaticComponentColorsFromBase(level)
        } else {
            componentColorsAreAutomatic[component.ordinal][level] = false
            paletteTable.setColor(level, component, color)
        }
        refreshPreview()
    }

    private fun updateControlStates() {
        if (!controlsCreated) return
        paletteTable.refreshAvailability()
    }

    private fun resetPaletteToThemeDefaults() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        updatingUi = true
        try {
            for (index in 0 until BracketColorPalette.COLOR_COUNT) {
                baseColorsAreAutomatic[index] = true
                val baseColor =
                    scheme.getAttributes(BracketColorPalette.LEVEL_KEYS[index]).foregroundColor
                        ?: scheme.defaultForeground
                paletteTable.setColor(index, PaletteComponent.BASE, baseColor)
                for (component in PaletteComponent.entries) {
                    if (component == PaletteComponent.BASE) continue
                    componentColorsAreAutomatic[component.ordinal][index] = true
                    paletteTable.setColor(index, component, baseColor)
                }
            }
            useIndependentComponentColors.isSelected = false
        } finally {
            updatingUi = false
        }
        updateControlStates()
        refreshPreview()
    }

    private fun loadComponentColor(
        level: Int,
        component: PaletteComponent,
        storedColors: List<Int>,
        fallback: Color,
    ) {
        val stored = BracketColorPalette.storedColor(storedColors.getOrNull(level))
        componentColorsAreAutomatic[component.ordinal][level] = stored == null
        paletteTable.setColor(level, component, stored ?: fallback)
    }

    private fun refreshAutomaticComponentColorsFromBase(level: Int? = null) {
        val wasUpdating = updatingUi
        updatingUi = true
        try {
            val levels = level?.let { it..it }
                ?: (0 until BracketColorPalette.COLOR_COUNT)
            for (index in levels) {
                val base = paletteTable.color(index, PaletteComponent.BASE)
                for (component in PaletteComponent.entries) {
                    if (component == PaletteComponent.BASE) continue
                    if (componentColorsAreAutomatic[component.ordinal][index]) {
                        paletteTable.setColor(index, component, base)
                    }
                }
            }
        } finally {
            updatingUi = wasUpdating
        }
    }

    private fun captureDraftState(): PluginOptions {
        val independent = useIndependentComponentColors.isSelected
        return PluginOptions(
            enabled = enabled.isSelected,
            colorBracketTokens = colorBracketTokens.isSelected,
            showActiveGuide = showActiveGuide.isSelected,
            showVerticalGuide = showVerticalGuide.isSelected,
            showHorizontalGuides = showHorizontalGuides.isSelected,
            guideLineWidth = spinnerValue(guideLineWidth),
            guideOpacityPercent = spinnerValue(guideOpacityPercent),
            showActivePairBorder = showActivePairBorder.isSelected,
            showActivePairBackground = showActivePairBackground.isSelected,
            pairBackgroundOpacityPercent = spinnerValue(pairBackgroundOpacityPercent),
            useIndependentComponentColors = independent,
            levelBaseColors = List(BracketColorPalette.COLOR_COUNT) { index ->
                if (baseColorsAreAutomatic[index]) {
                    BracketColorPalette.AUTOMATIC_COLOR
                } else {
                    storedColor(paletteTable.color(index, PaletteComponent.BASE))
                }
            },
            guideLineColors = componentColorsOrAutomatic(PaletteComponent.GUIDE),
            pairBorderColors = componentColorsOrAutomatic(PaletteComponent.BORDER),
            pairBackgroundColors = componentColorsOrAutomatic(
                PaletteComponent.BACKGROUND,
            ),
        )
    }

    private fun componentColorsOrAutomatic(
        component: PaletteComponent,
    ): List<Int> {
        return List(BracketColorPalette.COLOR_COUNT) { level ->
            if (componentColorsAreAutomatic[component.ordinal][level]) {
                BracketColorPalette.AUTOMATIC_COLOR
            } else {
                storedColor(paletteTable.color(level, component))
            }
        }
    }

    private fun refreshPreview() {
        if (updatingUi || !controlsCreated) return
        preview?.update(captureDraftState())
    }

    private fun storedColor(color: Color): Int =
        BracketColorPalette.colorToStoredValue(color)

    private fun spinnerValue(spinner: JSpinner): Int =
        (spinner.value as Number).toInt()

    companion object {
        private const val SPLITTER_PROPORTION_KEY =
            "BracketPairGuides.Settings.PreviewSplitter.v2"
        private const val DEFAULT_SPLITTER_PROPORTION = 0.45f
        private const val CONTROLS_MINIMUM_WIDTH = 350
        private const val CONTROLS_PREFERRED_WIDTH = 410
        private const val PAGE_MINIMUM_WIDTH = 600
        private const val PAGE_PREFERRED_WIDTH = 850
        private const val PAGE_MINIMUM_HEIGHT = 420
        private const val PAGE_PREFERRED_HEIGHT = 620
        private fun numberSpinner(
            value: Int,
            minimum: Int,
            maximum: Int,
            step: Int,
        ): JSpinner = JSpinner(SpinnerNumberModel(value, minimum, maximum, step))
    }
}
