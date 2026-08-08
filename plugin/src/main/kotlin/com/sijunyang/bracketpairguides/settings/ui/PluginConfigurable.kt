package com.sijunyang.bracketpairguides.settings.ui

import com.sijunyang.bracketpairguides.analysis.BracketLanguageSupport
import com.sijunyang.bracketpairguides.analysis.BraceLanguageFamily
import com.sijunyang.bracketpairguides.editor.EditorGuideSettingsApplier
import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.sijunyang.bracketpairguides.settings.PluginSettings
import com.sijunyang.bracketpairguides.settings.StoredBracketColors
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
import com.intellij.ui.layout.or
import java.awt.Color
import java.util.Locale

/** Standard platform controls bound directly to the persisted plugin options. */
internal class PluginConfigurable(
    private val supportedLanguagesProvider: () -> List<BraceLanguageFamily> =
        BracketLanguageSupport::installedFamilies,
) : BoundConfigurable("Bracket Pair Guides") {
    private var appliedSnapshot = PluginOptions()

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance()
        val colorPanels = mutableListOf<ColorPanel>()
        appliedSnapshot = settings.options

        return panel {
            lateinit var enabled: Cell<JBCheckBox>
            row {
                enabled = boundCheckBox(
                    settings,
                    "Enabled",
                    PluginOptions::enabled,
                ) { options, value -> options.copy(enabled = value) }
                    .focused()
            }

            group("Languages") {
                val supportedLanguages = supportedLanguagesProvider()
                    .map(::languageSetting)
                    .sortedWith(
                        compareBy<LanguageSetting>(
                            { language -> language.displayName.lowercase(Locale.ROOT) },
                            LanguageSetting::id,
                        ),
                    )
                if (supportedLanguages.isEmpty()) {
                    row {
                        comment("No installed language provides brace-matching support.")
                    }
                } else {
                    val duplicateNames = supportedLanguages
                        .groupingBy(LanguageSetting::displayName)
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
                row {
                    boundCheckBox(
                        settings,
                        "Bracket colorization",
                        PluginOptions::colorBracketTokens,
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
                        PluginOptions::showActiveGuide,
                    ) { options, value -> options.copy(showActiveGuide = value) }
                        .enabledIf(enabled.selected)
                }
                indent {
                    row("Segments:") {
                        horizontalGuides = boundCheckBox(
                            settings,
                            "Horizontal",
                            PluginOptions::showHorizontalGuides,
                        ) { options, value -> options.copy(showHorizontalGuides = value) }
                        verticalGuide = boundCheckBox(
                            settings,
                            "Vertical",
                            PluginOptions::showVerticalGuide,
                        ) { options, value -> options.copy(showVerticalGuide = value) }
                    }.enabledIf(enabled.selected.and(activeGuide.selected))
                    rowsRange {
                        row("Width (px):") {
                            boundSpinner(
                                settings,
                                PluginOptions.MIN_GUIDE_LINE_WIDTH..
                                    PluginOptions.MAX_GUIDE_LINE_WIDTH,
                                "guideLineWidth",
                                PluginOptions::guideLineWidth,
                            ) { options, value -> options.copy(guideLineWidth = value) }
                        }
                        row("Opacity:") {
                            boundSpinner(
                                settings,
                                PluginOptions.MIN_GUIDE_OPACITY_PERCENT..
                                    PluginOptions.MAX_GUIDE_OPACITY_PERCENT,
                                "guideOpacityPercent",
                                PluginOptions::guideOpacityPercent,
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
                        PluginOptions::showActivePairBorder,
                    ) { options, value -> options.copy(showActivePairBorder = value) }
                        .enabledIf(enabled.selected)
                }

                lateinit var pairBackground: Cell<JBCheckBox>
                row {
                    pairBackground = boundCheckBox(
                        settings,
                        "Pair background",
                        PluginOptions::showActivePairBackground,
                    ) { options, value -> options.copy(showActivePairBackground = value) }
                        .enabledIf(enabled.selected)
                }
                indent {
                    row("Opacity:") {
                        boundSpinner(
                            settings,
                            PluginOptions.MIN_PAIR_BACKGROUND_OPACITY_PERCENT..
                                PluginOptions.MAX_PAIR_BACKGROUND_OPACITY_PERCENT,
                            "pairBackgroundOpacityPercent",
                            PluginOptions::pairBackgroundOpacityPercent,
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
                        PluginOptions::useIndependentComponentColors,
                    ) { options, value ->
                        options.copy(useIndependentComponentColors = value)
                    }
                        .enabledIf(enabled.selected)
                }.rowComment(
                    "When off, guide, border, and background inherit each level's Base color.",
                )

                row("Level") {
                    for (target in ColorTarget.entries) {
                        label(target.displayName)
                    }
                }
                for (level in 0 until StoredBracketColors.COLOR_COUNT) {
                    row("${level + 1}:") {
                        for (target in ColorTarget.entries) {
                            val predicate = if (target == ColorTarget.BASE) {
                                enabled.selected
                            } else {
                                enabled.selected.and(componentOverrides.selected)
                            }
                            val colorPanel = boundColorPanel(
                                settings = settings,
                                target = target,
                                level = level,
                                enabled = predicate,
                            )
                            colorPanels += colorPanel.component
                        }
                    }
                }
                row {
                    comment(
                        "Empty Base colors use the editor theme. Empty component colors inherit Base.",
                    )
                }
                row {
                    button("Reset colors") {
                        colorPanels.forEach { colorPanel ->
                            colorPanel.selectedColor = null
                        }
                        componentOverrides.component.isSelected = false
                    }.enabledIf(enabled.selected)
                }
            }

            onApply {
                val applied = settings.options
                EditorGuideSettingsApplier.applyChanges(appliedSnapshot, applied)
                appliedSnapshot = applied
            }
            onReset {
                appliedSnapshot = settings.options
            }
        }
    }

    private fun Row.boundCheckBox(
        settings: PluginSettings,
        text: String,
        get: (PluginOptions) -> Boolean,
        set: (PluginOptions, Boolean) -> PluginOptions,
    ): Cell<JBCheckBox> = checkBox(text).bindSelected(
        { get(settings.options) },
        { value -> settings.replace(set(settings.options, value)) },
    )

    private fun Row.boundSpinner(
        settings: PluginSettings,
        range: IntRange,
        name: String,
        get: (PluginOptions) -> Int,
        step: Int = 1,
        set: (PluginOptions, Int) -> PluginOptions,
    ): Cell<JBIntSpinner> = spinner(range, step)
        .bindIntValue(
            { get(settings.options) },
            { value -> settings.replace(set(settings.options, value)) },
        )
        .applyToComponent { this.name = name }

    private fun Row.boundColorPanel(
        settings: PluginSettings,
        target: ColorTarget,
        level: Int,
        enabled: ComponentPredicate,
    ): Cell<ColorPanel> {
        val property = MutableProperty<Color?>(
            {
                StoredBracketColors.storedColor(
                    target.colors(settings.options).getOrNull(level),
                )
            },
            { color ->
                val options = settings.options
                val storedValue = color?.let(StoredBracketColors::colorToStoredValue)
                    ?: StoredBracketColors.AUTOMATIC_COLOR
                val colors = target.colors(options).mapIndexed { index, value ->
                    if (index == level) storedValue else value
                }
                settings.replace(target.withColors(options, colors))
            },
        )
        return cell(ColorPanel())
            .applyToComponent {
                name = "color.${target.settingId}.$level"
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

    private fun languageSetting(family: BraceLanguageFamily): LanguageSetting {
        val isCustomFileType = family.id == CUSTOM_FILE_TYPE_LANGUAGE_ID
        return LanguageSetting(
            id = family.id,
            displayName = if (isCustomFileType) {
                "Custom file types"
            } else {
                family.owner.displayName.ifBlank { family.id }
            },
            familyDisplayNames = family.members
                .map { language -> language.displayName.ifBlank { language.id } }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER),
            constraintDescription = if (isCustomFileType) {
                "Custom syntax-table bracket tokens only; raw plain text is not scanned"
            } else {
                null
            },
        )
    }

    private fun languageDescription(language: LanguageSetting): String {
        val family = language.familyDisplayNames.joinToString()
        return buildList {
            add("Matcher family ID: ${language.id}")
            if (language.constraintDescription == null && family != language.displayName) {
                add("Includes: $family")
            }
            language.constraintDescription?.let(::add)
        }.joinToString(". ", postfix = ".")
    }

    private data class LanguageSetting(
        val id: String,
        val displayName: String,
        val familyDisplayNames: List<String>,
        val constraintDescription: String?,
    )

    private enum class ColorTarget(
        val displayName: String,
        val settingId: String,
    ) {
        BASE("Base", "base"),
        GUIDE("Guide", "guide"),
        BORDER("Border", "border"),
        BACKGROUND("Background", "background"),
        ;

        fun colors(options: PluginOptions): List<Int> = when (this) {
            BASE -> options.levelBaseColors
            GUIDE -> options.guideLineColors
            BORDER -> options.pairBorderColors
            BACKGROUND -> options.pairBackgroundColors
        }

        fun withColors(options: PluginOptions, colors: List<Int>): PluginOptions =
            when (this) {
                BASE -> options.copy(levelBaseColors = colors)
                GUIDE -> options.copy(guideLineColors = colors)
                BORDER -> options.copy(pairBorderColors = colors)
                BACKGROUND -> options.copy(pairBackgroundColors = colors)
            }
    }

    private companion object {
        const val CUSTOM_FILE_TYPE_LANGUAGE_ID = "TEXT"
    }
}
