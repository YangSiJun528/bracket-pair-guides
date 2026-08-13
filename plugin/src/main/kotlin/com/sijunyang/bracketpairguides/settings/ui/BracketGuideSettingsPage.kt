package com.sijunyang.bracketpairguides.settings.ui

import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.analysis.pairing.BraceLanguageCatalog
import com.sijunyang.bracketpairguides.editor.events.GuideSettingsChange
import com.sijunyang.bracketpairguides.editor.events.NativeMatchedBraceHighlighting
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.StoredColorFormat
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.and
import com.intellij.ui.layout.not
import com.intellij.ui.layout.or
import java.awt.Color
import java.util.Locale
import javax.swing.JTextField

/** Standard platform controls bound directly to the persisted plugin options. */
internal class BracketGuideSettingsPage(
    private val installedLanguages: () -> List<BraceLanguageFamily>,
) : BoundConfigurable("Bracket Pair Guides") {
    private var appliedSnapshot = BracketGuidePreferences()

    @Suppress("unused")
    constructor() : this(BraceLanguageCatalog()::installedFamilies)

    override fun createPanel(): DialogPanel {
        val settings = BracketGuideSettings.getInstance()
        val colorPanels = mutableListOf<LeveledColorPanel>()
        NativeMatchedBraceHighlighting.getInstance().apply(settings.options)
        appliedSnapshot = settings.options

        return panel {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = boundCheckBox(
                    settings,
                    "Enabled",
                    BracketGuidePreferences::enabled,
                ) { options, value -> options.copy(enabled = value) }
                    .focused()
            }

            group("Languages") {
                val supportedLanguages = installedLanguages()
                    .map(::languageChoice)
                    .sortedWith(
                        compareBy<LanguageChoice>(
                            { language -> language.displayName.lowercase(Locale.ROOT) },
                            LanguageChoice::id,
                        ),
                    )
                if (supportedLanguages.isEmpty()) {
                    row {
                        comment("No installed language provides brace-matching support.")
                    }
                } else {
                    val duplicateNames = supportedLanguages
                        .groupingBy(LanguageChoice::displayName)
                        .eachCount()
                    for (language in supportedLanguages) {
                        val label = if (duplicateNames.getValue(language.displayName) > 1) {
                            "${language.displayName} (${language.id})"
                        } else {
                            language.displayName
                        }
                        row {
                            checkBox(label)
                                .applyToComponent {
                                    name = "language.${language.id}"
                                    val description = languageDescription(language)
                                    toolTipText = description
                                    accessibleContext.accessibleName = "$label language"
                                    accessibleContext.accessibleDescription = description
                                }
                                .bindSelected(
                                    {
                                        settings.options.isLanguageEnabled(language.id)
                                    },
                                    { selected ->
                                        val options = settings.options
                                        val disabledIds = if (selected) {
                                            options.disabledLanguageIds - language.id
                                        } else {
                                            options.disabledLanguageIds + language.id
                                        }
                                        settings.replace(
                                            options.copy(disabledLanguageIds = disabledIds),
                                        )
                                    },
                                )
                        }
                    }
                }
            }

            group("Appearance") {
                lateinit var disableNativeMatchedBraceHighlighting: Cell<JBCheckBox>
                row {
                    disableNativeMatchedBraceHighlighting = boundCheckBox(
                        settings,
                        "Disable IntelliJ matched-brace highlighting",
                        BracketGuidePreferences::disableNativeMatchedBraceHighlighting,
                    ) { options, value ->
                        options.copy(disableNativeMatchedBraceHighlighting = value)
                    }
                        .enabledIf(enabled.selected)
                }
                row {
                    comment(
                        "This mode is not tested and may not match the intended appearance.",
                    ).applyToComponent {
                        name = "nativeMatchedBraceWarning"
                    }
                }.visibleIf(
                    enabled.selected.and(disableNativeMatchedBraceHighlighting.selected.not()),
                )

                row {
                    boundCheckBox(
                        settings,
                        "Bracket colorization",
                        BracketGuidePreferences::colorBracketTokens,
                    ) { options, value -> options.copy(colorBracketTokens = value) }
                        .enabledIf(enabled.selected)
                }

                lateinit var activeGuide: Cell<JBCheckBox>
                lateinit var verticalGuide: Cell<JBCheckBox>
                lateinit var horizontalGuides: Cell<JBCheckBox>
                row {
                    activeGuide = boundCheckBox(
                        settings,
                        "Active guide",
                        BracketGuidePreferences::showActiveGuide,
                    ) { options, value -> options.copy(showActiveGuide = value) }
                        .enabledIf(enabled.selected)
                }
                indent {
                    row("Segments:") {
                        horizontalGuides = boundCheckBox(
                            settings,
                            "Horizontal",
                            BracketGuidePreferences::showHorizontalGuides,
                        ) { options, value -> options.copy(showHorizontalGuides = value) }
                        verticalGuide = boundCheckBox(
                            settings,
                            "Vertical",
                            BracketGuidePreferences::showVerticalGuide,
                        ) { options, value -> options.copy(showVerticalGuide = value) }
                    }.enabledIf(enabled.selected.and(activeGuide.selected))
                    rowsRange {
                        row("Width (px):") {
                            boundSpinner(
                                settings,
                                BracketGuidePreferences.MIN_GUIDE_LINE_WIDTH..
                                    BracketGuidePreferences.MAX_GUIDE_LINE_WIDTH,
                                "guideLineWidth",
                                BracketGuidePreferences::guideLineWidth,
                            ) { options, value -> options.copy(guideLineWidth = value) }
                        }
                        row("Opacity:") {
                            boundSpinner(
                                settings,
                                BracketGuidePreferences.MIN_GUIDE_OPACITY_PERCENT..
                                    BracketGuidePreferences.MAX_GUIDE_OPACITY_PERCENT,
                                "guideOpacityPercent",
                                BracketGuidePreferences::guideOpacityPercent,
                                5,
                            ) { options, value ->
                                options.copy(guideOpacityPercent = value)
                            }
                            label("%")
                        }
                    }.enabledIf(
                        enabled.selected
                            .and(activeGuide.selected)
                            .and(
                                verticalGuide.selected.or(horizontalGuides.selected),
                            ),
                    )
                }

                row {
                    boundCheckBox(
                        settings,
                        "Pair border",
                        BracketGuidePreferences::showActivePairBorder,
                    ) { options, value -> options.copy(showActivePairBorder = value) }
                        .enabledIf(enabled.selected)
                }

                lateinit var pairBackground: Cell<JBCheckBox>
                row {
                    pairBackground = boundCheckBox(
                        settings,
                        "Pair background",
                        BracketGuidePreferences::showActivePairBackground,
                    ) { options, value -> options.copy(showActivePairBackground = value) }
                        .enabledIf(enabled.selected)
                }
                indent {
                    row("Opacity:") {
                        boundSpinner(
                            settings,
                            BracketGuidePreferences.MIN_PAIR_BACKGROUND_OPACITY_PERCENT..
                                BracketGuidePreferences.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
                            "pairBackgroundOpacityPercent",
                            BracketGuidePreferences::pairBackgroundOpacityPercent,
                        ) { options, value ->
                            options.copy(pairBackgroundOpacityPercent = value)
                        }
                        label("%")
                    }.enabledIf(enabled.selected.and(pairBackground.selected))
                }
            }

            group("Colors") {
                lateinit var componentOverrides: Cell<JBCheckBox>
                row {
                    componentOverrides = boundCheckBox(
                        settings,
                        "Component overrides",
                        BracketGuidePreferences::useIndependentComponentColors,
                    ) { options, value ->
                        options.copy(useIndependentComponentColors = value)
                    }
                        .enabledIf(enabled.selected)
                }.rowComment(
                    "When off, Base is applied to guide, border, and background. " +
                        "Their saved colors remain visible but read-only.",
                )

                row("Level") {
                    for (target in ColorRole.entries) {
                        label(target.displayName)
                    }
                }
                for (level in 0 until StoredColorFormat.COLOR_COUNT) {
                    row("${level + 1}:") {
                        for (target in ColorRole.entries) {
                            val colorPanel = boundColorPanel(
                                settings = settings,
                                target = target,
                                level = level,
                                enabled = enabled.selected,
                                editable = if (target == ColorRole.BASE) {
                                    null
                                } else {
                                    componentOverrides.selected
                                },
                            )
                            colorPanels += LeveledColorPanel(level, colorPanel.component)
                        }
                    }
                }
                row {
                    comment(
                        "Reset colors restores the built-in six-color palette for every column.",
                    )
                }
                row {
                    button("Reset Colors") {
                        colorPanels.forEach { (level, colorPanel) ->
                            colorPanel.selectedColor = StoredColorFormat.storedColor(
                                StoredColorFormat.defaultColor(level),
                            )
                        }
                        componentOverrides.component.isSelected = false
                    }.enabledIf(enabled.selected)
                }
            }

            onApply {
                val applied = settings.options
                GuideSettingsChange(appliedSnapshot, applied).apply()
                appliedSnapshot = applied
            }
            onReset {
                appliedSnapshot = settings.options
            }
        }
    }

    private fun Row.boundCheckBox(
        settings: BracketGuideSettings,
        text: String,
        get: (BracketGuidePreferences) -> Boolean,
        set: (BracketGuidePreferences, Boolean) -> BracketGuidePreferences,
    ): Cell<JBCheckBox> = checkBox(text).bindSelected(
        { get(settings.options) },
        { value -> settings.replace(set(settings.options, value)) },
    )

    private fun Row.boundSpinner(
        settings: BracketGuideSettings,
        range: IntRange,
        name: String,
        get: (BracketGuidePreferences) -> Int,
        step: Int = 1,
        set: (BracketGuidePreferences, Int) -> BracketGuidePreferences,
    ): Cell<JBIntSpinner> = spinner(range, step)
        .bindIntValue(
            { get(settings.options) },
            { value -> settings.replace(set(settings.options, value)) },
        )
        .applyToComponent { this.name = name }

    private fun Row.boundColorPanel(
        settings: BracketGuideSettings,
        target: ColorRole,
        level: Int,
        enabled: ComponentPredicate,
        editable: ComponentPredicate?,
    ): Cell<ColorPanel> {
        val property = MutableProperty<Color?>(
            {
                StoredColorFormat.storedColor(
                    target.colors(settings.options)[level],
                )
            },
            { color ->
                val options = settings.options
                val storedValue = color?.let(StoredColorFormat::colorToStoredValue)
                    ?: StoredColorFormat.defaultColor(level)
                val colors = target.colors(options).mapIndexed { index, value ->
                    if (index == level) storedValue else value
                }
                settings.replace(target.withColors(options, colors))
            },
        )
        return cell(ColorPanel())
            .applyToComponent {
                name = "color.${target.settingId}.$level"
                val initiallyEditable = editable?.invoke() ?: true
                setEditable(initiallyEditable)
                updateColorAccessibility(target, level, initiallyEditable)
                editable?.addListener { value ->
                    setEditable(value)
                    updateColorAccessibility(target, level, value)
                }
            }
            .bind(
                { colorPanel: ColorPanel -> colorPanel.selectedColor },
                { colorPanel: ColorPanel, color: Color? ->
                    colorPanel.selectedColor = color
                },
                property,
            )
            .enabledIf(enabled)
    }

    private fun ColorPanel.updateColorAccessibility(
        target: ColorRole,
        level: Int,
        editable: Boolean,
    ) {
        val accessibleName = "Level ${level + 1} ${target.displayName} color"
        val accessibleDescription = when {
            target == ColorRole.BASE -> "Editable base color."
            editable -> "Editable component override."
            else -> "Read-only; Base is applied while Component overrides is off."
        }
        components.filterIsInstance<JTextField>().single().accessibleContext.apply {
            this.accessibleName = accessibleName
            this.accessibleDescription = accessibleDescription
        }
    }

    private fun languageChoice(family: BraceLanguageFamily): LanguageChoice {
        val isCustomFileType = family.id == CUSTOM_FILE_TYPE_LANGUAGE_ID
        return LanguageChoice(
            id = family.id,
            displayName = if (isCustomFileType) {
                "Custom file types"
            } else {
                family.displayName.ifBlank { family.id }
            },
            familyDisplayNames = family.memberDisplayNames
                .map { displayName -> displayName.ifBlank { family.id } }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER),
            constraintDescription = if (isCustomFileType) {
                "Custom syntax-table bracket tokens only; raw plain text is not scanned"
            } else {
                null
            },
        )
    }

    private fun languageDescription(language: LanguageChoice): String {
        val family = language.familyDisplayNames.joinToString()
        return buildList {
            add("Matcher family ID: ${language.id}")
            if (language.constraintDescription == null && family != language.displayName) {
                add("Includes: $family")
            }
            language.constraintDescription?.let(::add)
        }.joinToString(". ", postfix = ".")
    }

    private data class LanguageChoice(
        val id: String,
        val displayName: String,
        val familyDisplayNames: List<String>,
        val constraintDescription: String?,
    )

    private data class LeveledColorPanel(
        val level: Int,
        val panel: ColorPanel,
    )

    private enum class ColorRole(
        val displayName: String,
        val settingId: String,
    ) {
        BASE("Base", "base"),
        GUIDE("Guide", "guide"),
        BORDER("Border", "border"),
        BACKGROUND("Background", "background"),
        ;

        fun colors(options: BracketGuidePreferences): List<Int> = when (this) {
            BASE -> options.levelBaseColors
            GUIDE -> options.guideLineColors
            BORDER -> options.pairBorderColors
            BACKGROUND -> options.pairBackgroundColors
        }

        fun withColors(options: BracketGuidePreferences, colors: List<Int>): BracketGuidePreferences =
            when (this) {
                BASE -> options.copy(levelBaseColors = colors)
                GUIDE -> options.copy(guideLineColors = colors)
                BORDER -> options.copy(pairBorderColors = colors)
                BACKGROUND -> options.copy(pairBackgroundColors = colors)
            }
    }

    companion object {
        private const val CUSTOM_FILE_TYPE_LANGUAGE_ID = "TEXT"
    }
}
