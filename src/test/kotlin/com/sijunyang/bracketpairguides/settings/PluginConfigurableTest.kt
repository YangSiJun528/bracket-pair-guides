package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPair
import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.analyzer.LanguageBraceMatchers
import com.sijunyang.bracketpairguides.analyzer.SupportedBraceLanguage
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairResolution
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairResolver
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.renderer.EditorGuideSession
import com.sijunyang.bracketpairguides.renderer.GUIDE_PAINT_STATE_KEY
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JSpinner

class PluginConfigurableTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginSettings.State())
    }

    fun testPreferredFocusIsSafeAcrossTheUiLifecycle() {
        val configurable = PluginConfigurable { emptyList() }
        try {
            assertNull(configurable.getPreferredFocusedComponent())
            configurable.disposeUIResources()
            assertNull(configurable.getPreferredFocusedComponent())

            val component = configurable.createComponent()
            assertSame(
                component.checkBox("Enabled"),
                configurable.getPreferredFocusedComponent(),
            )
        } finally {
            configurable.disposeUIResources()
        }
        assertNull(configurable.getPreferredFocusedComponent())
    }

    fun testUsesCompactPaletteAndBoundedSidebarLayout() {
        withConfigurable { configurable, component ->
            val descendants = component.descendants()
            val checkboxNames = descendants.filterIsInstance<JBCheckBox>()
                .mapNotNull { it.text }
                .toSet()

            assertTrue("Enabled" in checkboxNames)
            assertTrue("Bracket colorization" in checkboxNames)
            assertTrue("Horizontal" in checkboxNames)
            assertTrue("Vertical" in checkboxNames)
            assertTrue("Pair border" in checkboxNames)
            assertTrue("Pair background" in checkboxNames)
            assertTrue("Component overrides" in checkboxNames)
            assertFalse(component.checkBox("Pair border").isSelected)
            assertFalse(component.checkBox("Pair background").isSelected)
            assertFalse(checkboxNames.any { it.startsWith("Show ") })
            assertEquals(0, descendants.count { it is ColorPanel })

            val palette = descendants.filterIsInstance<ColorPaletteTable>().single()
            assertEquals(BracketColorPalette.COLOR_COUNT, palette.table.rowCount)
            assertEquals(5, palette.table.columnCount)
            assertEquals(
                "Bracket level colors",
                palette.table.accessibleContext.accessibleName,
            )
            assertTrue(palette.preferredSize.width <= JBUI.scale(420))
            assertTrue(palette.minimumSize.width <= palette.preferredSize.width)
            assertEquals(
                palette.table.rowHeight * BracketColorPalette.COLOR_COUNT +
                    palette.table.tableHeader.preferredSize.height + JBUI.scale(2),
                palette.preferredSize.height,
            )

            val comments = descendants.filterIsInstance<JEditorPane>()
                .filter { it.text.contains("overrides") || it.text.contains("repeat") }
            assertEquals(2, comments.size)
            comments.forEach { comment ->
                assertTrue(
                    "Comment preferred width is ${comment.preferredSize.width}",
                    comment.preferredSize.width <= JBUI.scale(560),
                )
            }

            assertTrue(component is OnePixelSplitter)
            val splitter = component as OnePixelSplitter
            val preview = descendants.filterIsInstance<BracketSettingsPreview>().single()
            val exampleLabel = descendants.filterIsInstance<JLabel>()
                .single { it.text == "Example:" }
            assertSame(preview.exampleSelector, exampleLabel.labelFor)
            assertEquals(
                "Preview example",
                preview.exampleSelector.accessibleContext.accessibleName,
            )
            assertEquals(
                preview.exampleSelector.toolTipText,
                preview.exampleSelector.accessibleContext.accessibleDescription,
            )
            assertEquals(
                "Reset preview example",
                preview.resetExampleButton.accessibleContext.accessibleName,
            )
            assertEquals(
                preview.resetExampleButton.toolTipText,
                preview.resetExampleButton.accessibleContext.accessibleDescription,
            )
            assertEquals(
                "Editable bracket pair preview",
                preview.previewEditor.contentComponent.accessibleContext.accessibleName,
            )
            assertTrue(splitter.firstComponent is JBScrollPane)
            assertSame(preview, splitter.secondComponent)
            assertNotNull(
                (preview.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH),
            )
            assertNull(
                (preview.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH),
            )
            val previewInsets = preview.border.getBorderInsets(preview)
            assertTrue(previewInsets.left > 0)
            assertEquals(0, previewInsets.top)
            assertEquals(0, previewInsets.bottom)
            assertEquals(0, previewInsets.right)
            val editorScrollPane = preview.previewEditor.scrollPane
            val editorInsets = editorScrollPane.border.getBorderInsets(editorScrollPane)
            assertEquals(0, editorInsets.top)
            assertEquals(0, editorInsets.left)
            assertEquals(0, editorInsets.bottom)
            assertEquals(0, editorInsets.right)
            assertTrue(
                "Preview minimum width is ${preview.minimumSize.width}",
                preview.minimumSize.width <= JBUI.scale(240),
            )
            assertTrue(
                "Preview preferred width is ${preview.preferredSize.width}",
                preview.preferredSize.width <= JBUI.scale(420),
            )
            assertTrue(
                "Settings page minimum width is ${component.minimumSize.width}",
                component.minimumSize.width <= JBUI.scale(600),
            )
            assertTrue(
                "Settings page preferred width is ${component.preferredSize.width}",
                component.preferredSize.width <= JBUI.scale(850),
            )
            assertFalse(configurable.isModified)
        }
    }

    fun testLanguageFamiliesAreDiscoveredAndDisabledByStableMatcherOwnerId() {
        val supported = LanguageBraceMatchers.supportedLanguages()
        assertTrue(supported.isNotEmpty())
        for (candidate in supported) {
            val owner = checkNotNull(Language.findLanguageByID(candidate.id))
            assertEquals(
                candidate.id,
                LanguageBraceMatchers.capabilityOwner(owner)?.id,
            )
            assertTrue(candidate.familyDisplayNames.isNotEmpty())
        }
        val language = supported.first()
        PluginSettings.getInstance().loadState(
            PluginSettings.State(
                disabledLanguageIds = mutableListOf(UNAVAILABLE_LANGUAGE_ID),
            ),
        )

        withConfigurable { configurable, component ->
            val checkBox = component.languageCheckBox(language.id)
            assertTrue(checkBox.isSelected)
            assertTrue(checkBox.toolTipText.contains(language.id))
            assertFalse(configurable.isModified)

            checkBox.doClick()
            assertTrue(configurable.isModified)
            configurable.apply()

            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID, language.id),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
            assertFalse(configurable.isModified)

            checkBox.doClick()
            configurable.apply()
            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
        }
    }

    fun testCustomFileTypeLanguageControlExplainsItsTokenBoundary() {
        val language = SupportedBraceLanguage(
            id = "TEXT",
            displayName = "Custom file types",
            familyDisplayNames = listOf("Plain text"),
            constraintDescription =
                "Custom syntax-table bracket tokens only; raw plain text is not scanned",
        )

        withConfigurable(listOf(language)) { _, component ->
            val checkBox = component.languageCheckBox("TEXT")
            assertEquals("Custom file types", checkBox.text)
            assertTrue(checkBox.toolTipText.contains("raw plain text is not scanned"))
            assertFalse(checkBox.toolTipText.contains("Includes: Plain text"))
            assertEquals(
                checkBox.toolTipText,
                checkBox.accessibleContext.accessibleDescription,
            )
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

    fun testThemeChangeRefreshesOnlyAutomaticPaletteCells() {
        withConfigurable { configurable, component ->
            val palette = component.descendants()
                .filterIsInstance<ColorPaletteTable>()
                .single()
            val customDraftColor = Color(0x12, 0x34, 0x56)
            palette.table.model.setValueAt(customDraftColor, 0, 1)
            val modifiedBeforeThemeChange = configurable.isModified

            val themeColor = Color(0x65, 0x43, 0x21)
            val theme = EditorColorsSchemeImpl(
                EditorColorsManager.getInstance().globalScheme,
            ).apply {
                setAttributes(
                    BracketColorPalette.LEVEL_KEYS[1],
                    TextAttributes().apply { foregroundColor = themeColor },
                )
            }

            ApplicationManager.getApplication().messageBus
                .syncPublisher(EditorColorsManager.TOPIC)
                .globalSchemeChange(theme)

            assertEquals(customDraftColor, palette.color(0, PaletteComponent.BASE))
            assertEquals(customDraftColor, palette.color(0, PaletteComponent.GUIDE))
            assertEquals(themeColor, palette.color(1, PaletteComponent.BASE))
            assertEquals(themeColor, palette.color(1, PaletteComponent.GUIDE))
            assertEquals(modifiedBeforeThemeChange, configurable.isModified)
        }
    }

    fun testLanguageControlsSupportIndividualBulkApplyAndResetWithoutDroppingUnknownIds() {
        val languages = listOf(
            SupportedBraceLanguage("alpha", "Alpha", listOf("Alpha")),
            SupportedBraceLanguage("beta", "Beta", listOf("Beta", "Beta Dialect")),
            SupportedBraceLanguage("gamma", "Gamma", listOf("Gamma")),
        )
        PluginSettings.getInstance().loadState(
            PluginSettings.State(
                disabledLanguageIds = mutableListOf(
                    UNAVAILABLE_LANGUAGE_ID,
                    "beta",
                ),
            ),
        )

        withConfigurable(languages) { configurable, component ->
            val alpha = component.languageCheckBox("alpha")
            val beta = component.languageCheckBox("beta")
            val gamma = component.languageCheckBox("gamma")
            val enableAll = component.button("Enable all")
            val disableAll = component.button("Disable all")

            assertTrue(alpha.isSelected)
            assertFalse(beta.isSelected)
            assertTrue(gamma.isSelected)
            assertTrue(beta.toolTipText.contains("Beta Dialect"))
            assertFalse(configurable.isModified)

            component.checkBox("Enabled").doClick()
            assertTrue(alpha.isEnabled)
            assertTrue(enableAll.isEnabled)

            enableAll.doClick()
            assertTrue(listOf(alpha, beta, gamma).all { it.isSelected })
            assertTrue(configurable.isModified)
            assertFalse(enableAll.isEnabled)
            assertTrue(disableAll.isEnabled)

            disableAll.doClick()
            assertTrue(listOf(alpha, beta, gamma).none { it.isSelected })
            assertTrue(enableAll.isEnabled)
            assertFalse(disableAll.isEnabled)

            alpha.doClick()
            configurable.apply()
            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID, "beta", "gamma"),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
            assertFalse(configurable.isModified)

            PluginSettings.getInstance().replace(
                PluginSettings.getInstance().options.copy(
                    disabledLanguageIds = setOf(UNAVAILABLE_LANGUAGE_ID, "gamma"),
                ),
            )
            configurable.reset()
            assertTrue(alpha.isSelected)
            assertTrue(beta.isSelected)
            assertFalse(gamma.isSelected)
            assertFalse(configurable.isModified)

            enableAll.doClick()
            configurable.apply()
            assertEquals(
                setOf(UNAVAILABLE_LANGUAGE_ID),
                PluginSettings.getInstance().options.disabledLanguageIds,
            )
        }
    }

    fun testDependentRowsAndPaletteColumnsFollowTheirToggles() {
        withConfigurable { _, component ->
            val descendants = component.descendants()
            val palette = descendants.filterIsInstance<ColorPaletteTable>().single()
            val table = palette.table
            val guide = component.checkBox("Active guide")
            val vertical = component.checkBox("Vertical")
            val horizontal = component.checkBox("Horizontal")
            val border = component.checkBox("Pair border")
            val background = component.checkBox("Pair background")
            val advanced = component.checkBox("Component overrides")
            val master = component.checkBox("Enabled")
            val width = component.spinnerWithValue(1)
            val guideOpacity = component.spinnerWithValue(100)
            val backgroundOpacity = component.spinnerWithValue(22)

            assertTrue(table.model.isCellEditable(0, 1))
            assertFalse(table.model.isCellEditable(0, 2))
            assertFalse(table.model.isCellEditable(0, 3))
            assertFalse(table.model.isCellEditable(0, 4))

            advanced.doClick()
            assertTrue(table.model.isCellEditable(0, 2))
            assertFalse(table.model.isCellEditable(0, 3))
            assertFalse(table.model.isCellEditable(0, 4))
            assertFalse(backgroundOpacity.isEnabled)

            border.doClick()
            assertTrue(table.model.isCellEditable(0, 3))
            background.doClick()
            assertTrue(backgroundOpacity.isEnabled)
            assertTrue(table.model.isCellEditable(0, 4))

            vertical.doClick()
            assertTrue(width.isEnabled)
            horizontal.doClick()
            assertFalse(width.isEnabled)
            assertFalse(guideOpacity.isEnabled)
            assertFalse(table.model.isCellEditable(0, 2))
            horizontal.doClick()
            assertTrue(width.isEnabled)
            assertTrue(table.model.isCellEditable(0, 2))

            guide.doClick()
            assertFalse(width.isEnabled)
            assertFalse(guideOpacity.isEnabled)
            assertFalse(table.model.isCellEditable(0, 2))
            assertTrue(table.model.isCellEditable(0, 3))

            border.doClick()
            assertFalse(table.model.isCellEditable(0, 3))

            background.doClick()
            assertFalse(backgroundOpacity.isEnabled)
            assertFalse(table.model.isCellEditable(0, 4))

            master.doClick()
            assertFalse(table.isEnabled)
            assertFalse(
                component.checkBox("Bracket colorization").isEnabled,
            )
            assertFalse(advanced.isEnabled)
            assertFalse(table.model.isCellEditable(0, 1))
        }
    }

    fun testAdvancedColorsRemainStoredWhileTheirToggleIsOff() {
        withConfigurable { configurable, component ->
            val palette = component.descendants()
                .filterIsInstance<ColorPaletteTable>()
                .single()
            val advanced = component.checkBox("Component overrides")
            val customGuide = Color(0x12, 0x6A, 0xD4)

            advanced.doClick()
            palette.table.model.setValueAt(customGuide, 0, 2)
            assertEquals(customGuide, palette.color(0, PaletteComponent.GUIDE))

            advanced.doClick()
            assertFalse(palette.table.model.isCellEditable(0, 2))
            assertEquals(customGuide, palette.color(0, PaletteComponent.GUIDE))
            assertTrue(configurable.isModified)

            configurable.apply()
            val persisted = PluginSettings.getInstance().options
            assertFalse(persisted.useIndependentComponentColors)
            assertEquals(
                BracketColorPalette.colorToStoredValue(customGuide),
                persisted.guideLineColors[0],
            )
            assertFalse(configurable.isModified)

            advanced.doClick()
            assertEquals(customGuide, palette.color(0, PaletteComponent.GUIDE))
            advanced.doClick()
            assertFalse(configurable.isModified)
        }
    }

    fun testValidSpinnerTextIsCommittedBeforeFocusLeavesTheEditor() {
        withConfigurable { configurable, component ->
            val width = component.spinnerWithValue(1)
            val textField = (width.editor as JSpinner.DefaultEditor).textField

            textField.selectAll()
            textField.replaceSelection("4")

            assertEquals(4, (width.value as Number).toInt())
            assertTrue(configurable.isModified)

            configurable.apply()

            assertEquals(4, PluginSettings.getInstance().options.guideLineWidth)
            assertFalse(configurable.isModified)
        }
    }

    fun testPreviewUpdatesDraftWithoutPersistingUntilApply() {
        withConfigurable { configurable, component ->
            val preview = component.descendants()
                .filterIsInstance<BracketSettingsPreview>()
                .single()
            val initialState = PluginSettings.getInstance().options
            assertFalse(configurable.isModified)
            assertEquals(initialState, PluginSettings.getInstance().options)

            val tokenCount = preview.previewEditor.markupModel.allHighlighters
                .tokenHighlighters()
                .size
            assertTrue(tokenCount > 0)
            assertEquals(
                tokenCount + 1,
                preview.previewEditor.markupModel.allHighlighters.size,
            )
            assertEquals(1, preview.previewEditor.markupModel.allHighlighters.countGuide())
            assertEquals(0, preview.previewEditor.markupModel.allHighlighters.countActivePairs())

            component.checkBox("Bracket colorization").doClick()
            assertEquals(1, preview.previewEditor.markupModel.allHighlighters.size)
            assertEquals(initialState, PluginSettings.getInstance().options)
            assertTrue(configurable.isModified)

            component.checkBox("Active guide").doClick()
            assertEquals(0, preview.previewEditor.markupModel.allHighlighters.size)
            component.checkBox("Pair border").doClick()
            component.checkBox("Pair background").doClick()
            assertEquals(2, preview.previewEditor.markupModel.allHighlighters.size)

            component.checkBox("Enabled").doClick()
            assertEquals(0, preview.previewEditor.markupModel.allHighlighters.size)
            assertEquals(initialState, PluginSettings.getInstance().options)

            configurable.apply()
            assertFalse(PluginSettings.getInstance().options.enabled)
            assertFalse(PluginSettings.getInstance().options.colorBracketTokens)
            assertFalse(configurable.isModified)
        }
    }

    fun testPreviewOffersOnlyExamplesWithLanguageBraceMatchers() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            val examples = (0 until preview.exampleSelector.itemCount).map { index ->
                preview.exampleSelector.getItemAt(index)
            }
            val installedFixtureExamples = examples.map(PreviewExample::id).toSet()
            assertTrue(
                installedFixtureExamples.containsAll(setOf("java", "kotlin", "json")),
            )
            assertFalse(editor.isViewer)

            examples.forEach { example ->
                val fileType = example.resolveFileType()
                assertFalse(fileType === UnknownFileType.INSTANCE)
                assertFalse(fileType === PlainTextFileType.INSTANCE)

                preview.exampleSelector.selectedItem = example
                assertEquals(example.source, editor.document.text)
                assertEquals(example.initialCaretOffset, editor.caretModel.offset)
                val pairs = recognizedPairs(preview)
                assertTrue(
                    "${example.displayName} should recognize pairs",
                    pairs.isNotEmpty(),
                )
                assertRecognizedRangesAreValid(editor, pairs)

                val activeIndex = ActiveBracketPairIndex.build(pairs)
                val active = pairs.getOrNull(
                    activeIndex.activePairIndex(editor.caretModel.offset),
                )
                assertNotNull("${example.displayName} should start inside a pair", active)
                waitForPreviewDecoration(preview, "${example.displayName} preview recognition")
                assertEquals(1, editor.markupModel.allHighlighters.countGuide())
                assertEquals(0, editor.markupModel.allHighlighters.countActivePairs())
                val tokenHighlights = editor.markupModel.allHighlighters
                    .tokenHighlighters()
                assertTrue(tokenHighlights.isNotEmpty())
                assertEquals(
                    tokenHighlights.size + 1,
                    editor.markupModel.allHighlighters.size,
                )
            }
        } finally {
            preview.dispose()
        }
        assertTrue(editor.isDisposed)
    }

    fun testPreviewReanalyzesWhenItsMatcherFamilyIsDisabledAndReenabled() {
        val preview = BracketSettingsPreview()
        try {
            preview.update(PluginOptions())
            val fileType = (preview.exampleSelector.selectedItem as PreviewExample)
                .resolveFileType() as LanguageFileType
            val capabilityId = checkNotNull(
                LanguageBraceMatchers.capabilityOwner(fileType.language),
            ).id
            assertTrue(
                preview.previewEditor.markupModel.allHighlighters
                    .tokenHighlighters()
                    .isNotEmpty(),
            )

            preview.update(
                PluginOptions(disabledLanguageIds = setOf(capabilityId)),
            )
            assertTrue(preview.previewEditor.markupModel.allHighlighters.isEmpty())

            preview.update(PluginOptions())
            waitForPreviewDecoration(preview, "reenabled matcher family")
            assertTrue(
                preview.previewEditor.markupModel.allHighlighters
                    .tokenHighlighters()
                    .isNotEmpty(),
            )
        } finally {
            preview.dispose()
        }
    }

    fun testExampleSwitchRecognitionRunsOffTheEdt() {
        val edtCollections = AtomicInteger()
        val backgroundCollections = AtomicInteger()
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
                val delegate = BracketPairAnalyzer(editor, fileType) { capabilityId ->
                    capabilityId !in disabledLanguageIds
                }
                BracketPairProvider { progress ->
                    if (ApplicationManager.getApplication().isDispatchThread) {
                        edtCollections.incrementAndGet()
                    } else {
                        backgroundCollections.incrementAndGet()
                    }
                    delegate.collect(progress)
                }
            },
        )
        try {
            val synchronousCollections = edtCollections.get()

            selectExample(preview, "json")
            waitForPreviewDecoration(preview, "background example switch")

            assertTrue(backgroundCollections.get() > 0)
            assertEquals(synchronousCollections, edtCollections.get())
        } finally {
            preview.dispose()
        }
    }

    fun testPreviewRetriesWhenACompletedSnapshotBecomesStale() {
        val staleCollectionStarted = CountDownLatch(1)
        val allowStaleCollectionToFinish = CountDownLatch(1)
        val collections = AtomicInteger()
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
                val delegate = BracketPairAnalyzer(editor, fileType) { capabilityId ->
                    capabilityId !in disabledLanguageIds
                }
                BracketPairProvider { progress ->
                    if (collections.incrementAndGet() == 2) {
                        staleCollectionStarted.countDown()
                        check(
                            allowStaleCollectionToFinish.await(10, TimeUnit.SECONDS),
                        ) { "Timed out waiting to finish the stale preview collection" }
                    }
                    delegate.collect(progress)
                }
            },
        )
        try {
            assertEquals(1, collections.get())
            selectExample(preview, "json")
            PlatformTestUtil.waitWithEventsDispatching(
                "stale preview collection start",
                { staleCollectionStarted.count == 0L },
                10_000,
            )

            val previousTabSize = preview.previewEditor.settings.getTabSize(null)
            preview.previewEditor.settings.setTabSize(previousTabSize + 1)
            allowStaleCollectionToFinish.countDown()

            PlatformTestUtil.waitWithEventsDispatching(
                "preview retry after stale snapshot rejection",
                {
                    collections.get() >= 3 &&
                        preview.previewEditor.markupModel.allHighlighters
                            .tokenHighlighters()
                            .isNotEmpty() &&
                        !preview.analysisStatusLabel.isVisible
                },
                10_000,
            )
            assertFalse(preview.analysisStatusLabel.text.contains("failed"))
        } finally {
            allowStaleCollectionToFinish.countDown()
            preview.dispose()
        }
    }

    fun testPreviewRefreshesAStaleSnapshotWhenGuideAnalysisIsReenabled() {
        val collections = AtomicInteger()
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
                val delegate = BracketPairAnalyzer(editor, fileType) { capabilityId ->
                    capabilityId !in disabledLanguageIds
                }
                BracketPairProvider { progress ->
                    collections.incrementAndGet()
                    delegate.collect(progress)
                }
            },
        )
        try {
            assertEquals(1, collections.get())
            val guideDisabled = PluginOptions(showActiveGuide = false)
            preview.update(guideDisabled)
            assertEquals(1, collections.get())

            val previousTabSize = preview.previewEditor.settings.getTabSize(null)
            preview.previewEditor.settings.setTabSize(previousTabSize + 1)
            preview.update(guideDisabled.copy(showActiveGuide = true))

            PlatformTestUtil.waitWithEventsDispatching(
                "preview refresh after guide analysis becomes stale",
                {
                    collections.get() == 2 &&
                        preview.previewEditor.markupModel.allHighlighters
                            .countGuide() == 1 &&
                        !preview.analysisStatusLabel.isVisible
                },
                10_000,
            )
            assertEquals(
                1,
                preview.previewEditor.markupModel.allHighlighters.countGuide(),
            )
        } finally {
            preview.dispose()
        }
    }

    fun testLargePreviewLanguageChangeDoesNotCollectOnTheEdt() {
        val edtCollections = AtomicInteger()
        val backgroundCollections = AtomicInteger()
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
                val delegate = BracketPairAnalyzer(editor, fileType) { capabilityId ->
                    capabilityId !in disabledLanguageIds
                }
                BracketPairProvider { progress ->
                    if (editor.document.textLength > 10_000) {
                        if (ApplicationManager.getApplication().isDispatchThread) {
                            edtCollections.incrementAndGet()
                        } else {
                            backgroundCollections.incrementAndGet()
                        }
                    }
                    delegate.collect(progress)
                }
            },
        )
        try {
            selectExample(preview, "java")
            val largeJava = buildString {
                append("class Large { void run() {\n")
                repeat(1_200) { append("call(); // padding\n") }
                append("} }")
            }
            replacePreviewText(preview, largeJava)
            val fileType = (preview.exampleSelector.selectedItem as PreviewExample)
                .resolveFileType() as LanguageFileType
            val capabilityId = checkNotNull(
                LanguageBraceMatchers.capabilityOwner(fileType.language),
            ).id

            preview.update(
                PluginOptions(disabledLanguageIds = setOf(capabilityId)),
            )

            PlatformTestUtil.waitWithEventsDispatching(
                "large language change background completion",
                {
                    backgroundCollections.get() > 0 &&
                        !preview.analysisStatusLabel.isVisible
                },
                10_000,
            )
            assertEquals(0, edtCollections.get())
            assertTrue(preview.previewEditor.markupModel.allHighlighters.isEmpty())
        } finally {
            preview.dispose()
        }
    }

    fun testPreviewEditingUsesTheSelectedLexerAndIgnoresStringBraces() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            selectExample(preview, "java")
            val source = """
                class Edited {
                    String ignored = "}";
                    void run() {
                        call();
                    }
                }
            """.trimIndent()

            replacePreviewText(preview, source)
            editor.caretModel.moveToOffset(source.indexOf("call"))
            selectExample(preview, "json")
            selectExample(preview, "java")

            assertEquals(source, editor.document.text)
            waitForPreviewDecoration(preview, "restored edited Java preview")
            assertTrue(editor.markupModel.allHighlighters.tokenHighlighters().isNotEmpty())
            val ignoredBraceOffset = source.indexOf("\"}\"") + 1
            assertFalse(
                editor.markupModel.allHighlighters.tokenHighlighters().any { highlighter ->
                    highlighter.startOffset == ignoredBraceOffset
                },
            )
            assertEquals(1, editor.markupModel.allHighlighters.countGuide())
        } finally {
            preview.dispose()
        }
    }

    fun testCaretMovementReusesRecognitionAndTokenHighlights() {
        var collections = 0
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, _ ->
                val delegate = BracketPairAnalyzer(editor, fileType)
                BracketPairProvider { progress ->
                    collections++
                    delegate.collect(progress)
                }
            },
        )
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            val pairs = recognizedPairs(preview)
            assertTrue(pairs.isNotEmpty())
            assertEquals(1, collections)
            val activeIndex = ActiveBracketPairIndex.build(pairs)
            val tokenHighlights = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()

            val deepest = pairs.maxBy { it.depth }
            val deepestOffset = ((deepest.openOffset + deepest.openTokenLength) +
                deepest.closeOffset) / 2
            editor.caretModel.moveToOffset(deepestOffset)
            val persistentGuide = editor.markupModel.allHighlighters.single {
                it.getUserData(GUIDE_PAINT_STATE_KEY) != null
            }
            assertEquals(
                pairs.getOrNull(activeIndex.activePairIndex(deepestOffset)),
                editor.markupModel.allHighlighters.activeGuidePair(),
            )
            assertEquals(
                tokenHighlights,
                editor.markupModel.allHighlighters.tokenHighlighters().toSet(),
            )

            val outer = pairs.filter { it.depth == 0 }
                .maxBy { it.closeOffset - it.openOffset }
            val outerOffset = outer.openOffset + outer.openTokenLength
            editor.caretModel.moveToOffset(outerOffset)
            assertSame(
                persistentGuide,
                editor.markupModel.allHighlighters.single {
                    it.getUserData(GUIDE_PAINT_STATE_KEY) != null
                },
            )
            assertEquals(outer, editor.markupModel.allHighlighters.activeGuidePair())
            assertEquals(
                tokenHighlights,
                editor.markupModel.allHighlighters.tokenHighlighters().toSet(),
            )

            editor.caretModel.moveToOffset(0)
            assertNull(editor.markupModel.allHighlighters.activeGuidePair())
            assertEquals(0, editor.markupModel.allHighlighters.countActivePairs())
            assertEquals(
                tokenHighlights,
                editor.markupModel.allHighlighters.tokenHighlighters().toSet(),
            )
            assertEquals(1, collections)
        } finally {
            preview.dispose()
        }
    }

    fun testPreviewKeepsAdjustedDecorationsDuringDebouncedEditing() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            val tokensBeforeEdit = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()
            assertTrue(tokensBeforeEdit.isNotEmpty())
            assertEquals(1, editor.markupModel.allHighlighters.countGuide())
            val insertionOffset = editor.document.text.indexOf("value")

            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(insertionOffset, "x")
            }

            val tokensAfterEdit = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()
            assertTrue(tokensAfterEdit.isNotEmpty())
            assertTrue(tokensAfterEdit.any { highlighter -> highlighter in tokensBeforeEdit })
            assertEquals(1, editor.markupModel.allHighlighters.countGuide())
        } finally {
            preview.dispose()
        }
    }

    fun testEachExamplePreservesItsBufferCaretAndScrollAndResetIsLocal() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            val java = selectExample(preview, "java")
            val editedJava = buildString {
                append("class Edited { void run() {\n")
                repeat(60) { line ->
                    append("call($line); // ")
                    append("horizontal-padding-".repeat(6))
                    append('\n')
                }
                append("} }")
            }
            val javaCaret = editedJava.indexOf("call")
            replacePreviewText(preview, editedJava)
            editor.caretModel.moveToOffset(javaCaret)
            editor.scrollingModel.disableAnimation()
            try {
                editor.scrollingModel.scrollHorizontally(37)
                editor.scrollingModel.scrollVertically(240)
            } finally {
                editor.scrollingModel.enableAnimation()
            }
            val javaHorizontalOffset = editor.scrollingModel.horizontalScrollOffset
            val javaVerticalOffset = editor.scrollingModel.verticalScrollOffset
            assertTrue(javaHorizontalOffset > 0)
            assertTrue(javaVerticalOffset > 0)

            val json = selectExample(preview, "json")
            assertEquals(json.source, editor.document.text)
            assertEquals(json.initialCaretOffset, editor.caretModel.offset)
            val editedJson = """{"edited":[{"value":true}]}"""
            val jsonCaret = editedJson.indexOf("true")
            replacePreviewText(preview, editedJson)
            editor.caretModel.moveToOffset(jsonCaret)

            selectExample(preview, "java")
            assertEquals(editedJava, editor.document.text)
            assertEquals(javaCaret, editor.caretModel.offset)
            assertEquals(
                javaHorizontalOffset,
                editor.scrollingModel.horizontalScrollOffset,
            )
            assertEquals(
                javaVerticalOffset,
                editor.scrollingModel.verticalScrollOffset,
            )

            preview.resetExampleButton.doClick()
            assertEquals(java.source, editor.document.text)
            assertEquals(java.initialCaretOffset, editor.caretModel.offset)

            selectExample(preview, "json")
            assertEquals(editedJson, editor.document.text)
            assertEquals(jsonCaret, editor.caretModel.offset)
        } finally {
            preview.dispose()
        }
    }

    fun testLargeSavedExampleReturnsThroughBackgroundRecognition() {
        val preview = BracketSettingsPreview()
        try {
            selectExample(preview, "java")
            val largeJava = buildString {
                append("class Large { void run() {\n")
                repeat(1_200) { append("call(); // padding\n") }
                append("} }")
            }
            assertTrue(largeJava.length > 10_000)
            replacePreviewText(preview, largeJava)

            selectExample(preview, "json")
            selectExample(preview, "java")

            assertEquals(largeJava, preview.previewEditor.document.text)
            assertTrue(preview.previewEditor.markupModel.allHighlighters.isEmpty())
            assertTrue(preview.analysisStatusLabel.isVisible)
            assertTrue(preview.analysisStatusLabel.text.contains("Analyzing"))
            assertEquals(
                preview.analysisStatusLabel.accessibleContext.accessibleDescription,
                preview.analysisStatusLabel.toolTipText,
            )
            assertTrue(preview.analysisStatusLabel.toolTipText.contains("background"))
            PlatformTestUtil.waitWithEventsDispatching(
                "large preview background recognition",
                {
                    preview.previewEditor.markupModel.allHighlighters
                        .tokenHighlighters()
                        .isNotEmpty()
                },
                10_000,
            )
            assertEquals(
                1,
                preview.previewEditor.markupModel.allHighlighters.countGuide(),
            )
            assertFalse(preview.analysisStatusLabel.isVisible)
            assertNull(preview.analysisStatusLabel.accessibleContext.accessibleDescription)
        } finally {
            preview.dispose()
        }
    }

    fun testLargePreviewFailureReplacesAnalyzingStateWithRecoveryStatus() {
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, disabledLanguageIds ->
                val delegate = BracketPairAnalyzer(editor, fileType) { capabilityId ->
                    capabilityId !in disabledLanguageIds
                }
                BracketPairProvider { progress ->
                    if (editor.document.textLength > 10_000) {
                        throw IllegalStateException("injected preview matcher failure")
                    }
                    delegate.collect(progress)
                }
            },
        )
        try {
            selectExample(preview, "java")
            val largeJava = buildString {
                append("class Large { void run() {\n")
                repeat(1_200) { append("call(); // padding\n") }
                append("} }")
            }

            replacePreviewText(preview, largeJava)

            assertTrue(preview.analysisStatusLabel.text.contains("Analyzing"))
            PlatformTestUtil.waitWithEventsDispatching(
                "large preview failure status",
                { preview.analysisStatusLabel.text.contains("failed") },
                10_000,
            )
            assertFalse(preview.analysisStatusLabel.text.contains("Analyzing"))
            assertEquals(
                preview.analysisStatusLabel.accessibleContext.accessibleDescription,
                preview.analysisStatusLabel.toolTipText,
            )
            assertTrue(preview.analysisStatusLabel.toolTipText.contains("Reset to retry"))
            assertTrue(preview.previewEditor.markupModel.allHighlighters.isEmpty())

            preview.resetExampleButton.doClick()

            waitForPreviewDecoration(preview, "preview recovery after reset")
            assertFalse(preview.analysisStatusLabel.isVisible)
            assertNull(preview.analysisStatusLabel.accessibleContext.accessibleDescription)
            assertTrue(
                preview.previewEditor.markupModel.allHighlighters
                    .tokenHighlighters()
                    .isNotEmpty(),
            )
            assertEquals(1, preview.previewEditor.markupModel.allHighlighters.countGuide())
        } finally {
            preview.dispose()
        }
    }

    fun testOversizedPreviewPausesAnalysisAndExampleSwitchingUntilReset() {
        val preview = BracketSettingsPreview()
        try {
            val selected = preview.exampleSelector.selectedItem as PreviewExample
            val oversized = "x".repeat(100_001)
            replacePreviewText(preview, oversized)

            assertFalse(preview.exampleSelector.isEnabled)
            assertTrue(preview.analysisStatusLabel.isVisible)
            assertTrue(preview.analysisStatusLabel.text.contains("100,000"))
            assertTrue(preview.analysisStatusLabel.text.length < 40)
            assertEquals(
                preview.analysisStatusLabel.accessibleContext.accessibleDescription,
                preview.analysisStatusLabel.toolTipText,
            )
            assertTrue(preview.analysisStatusLabel.toolTipText.contains("Shorten the text"))
            val another = (0 until preview.exampleSelector.itemCount)
                .map(preview.exampleSelector::getItemAt)
                .first { it != selected }

            preview.exampleSelector.selectedItem = another

            assertSame(selected, preview.exampleSelector.selectedItem)
            assertEquals(oversized, preview.previewEditor.document.text)

            replacePreviewText(preview, "x".repeat(100_000))

            assertTrue(preview.exampleSelector.isEnabled)
            assertTrue(preview.analysisStatusLabel.isVisible)
            assertTrue(preview.analysisStatusLabel.text.contains("Analyzing"))

            replacePreviewText(preview, oversized)
            assertFalse(preview.exampleSelector.isEnabled)
            preview.resetExampleButton.doClick()

            assertEquals(selected.source, preview.previewEditor.document.text)
            assertTrue(preview.exampleSelector.isEnabled)
            assertFalse(preview.analysisStatusLabel.isVisible)
            assertNull(preview.analysisStatusLabel.accessibleContext.accessibleDescription)
        } finally {
            preview.dispose()
        }
    }

    fun testDisposedPreviewIgnoresLateControlEvents() {
        val preview = BracketSettingsPreview()
        val document = preview.previewEditor.document
        val edited = "class EditedAfterClose { void run() {} }"
        try {
            replacePreviewText(preview, edited)
            val another = (0 until preview.exampleSelector.itemCount)
                .map(preview.exampleSelector::getItemAt)
                .first { it != preview.exampleSelector.selectedItem }

            preview.dispose()
            preview.resetExampleButton.doClick()
            preview.exampleSelector.selectedItem = another

            assertEquals(edited, document.text)
        } finally {
            preview.dispose()
        }
    }

    fun testDensePreviewBoundsTokenDecorationsToTheVisibleWindow() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            selectExample(preview, "java")
            val denseJava = buildString {
                append("class Dense { void run() {\n")
                repeat(750) { append("call();\n") }
                append("} }")
            }
            replacePreviewText(preview, denseJava)
            editor.caretModel.moveToOffset(denseJava.indexOf("call"))
            selectExample(preview, "json")
            selectExample(preview, "java")
            val pairs = recognizedPairs(preview)
            waitForPreviewDecoration(preview, "dense restored preview")
            val tokenHighlights = editor.markupModel.allHighlighters.tokenHighlighters()

            assertTrue(tokenHighlights.isNotEmpty())
            assertTrue(tokenHighlights.size < pairs.size * 2)
            assertTrue(tokenHighlights.size <= 2_048)
            val recognizedOffsets = pairs.flatMap { pair ->
                listOf(pair.openOffset, pair.closeOffset)
            }.toSet()
            assertTrue(tokenHighlights.all { it.startOffset in recognizedOffsets })
        } finally {
            preview.dispose()
        }
    }

    fun testCompactPaletteEditsCellsAndExplainsDisabledColumns() {
        var disabledComponents = setOf(PaletteComponent.GUIDE)
        var changed: Triple<Int, PaletteComponent, Color>? = null
        val palette = ColorPaletteTable(
            disabledReason = { component ->
                if (component in disabledComponents) "Feature is disabled" else null
            },
            onColorChanged = { level, component, color ->
                changed = Triple(level, component, color)
            },
        )
        val base = Color(0x12, 0x34, 0x56)
        palette.setColor(0, PaletteComponent.BASE, base)

        assertEquals(base, palette.color(0, PaletteComponent.BASE))
        assertTrue(palette.table.model.isCellEditable(0, 1))
        assertFalse(palette.table.model.isCellEditable(0, 2))
        palette.table.model.setValueAt(Color.RED, 0, 1)
        assertEquals(Triple(0, PaletteComponent.BASE, Color.RED), changed)

        val guideCell = palette.table.prepareRenderer(
            palette.table.getCellRenderer(0, 2),
            0,
            2,
        ) as JLabel
        assertTrue(guideCell.isEnabled)
        assertTrue(guideCell.toolTipText.contains("Feature is disabled"))
        assertTrue(guideCell.toolTipText.contains("#000000"))
        assertTrue(
            guideCell.accessibleContext.accessibleDescription.contains(
                "Feature is disabled",
            ),
        )

        disabledComponents = emptySet()
        palette.refreshAvailability()
        assertTrue(palette.table.model.isCellEditable(0, 2))
    }

    fun testDisablingPaletteCancelsItsDeferredColorChooser() {
        var chooserCalls = 0
        val palette = ColorPaletteTable(
            disabledReason = { null },
            onColorChanged = { _, _, _ -> },
            chooseColor = { _, _, _ ->
                chooserCalls++
                Color.RED
            },
        )

        assertTrue(palette.table.editCellAt(0, 1))
        assertTrue(palette.table.isEditing)

        palette.isEnabled = false
        UIUtil.dispatchAllInvocationEvents()

        assertFalse(palette.table.isEditing)
        assertEquals(0, chooserCalls)
    }

    fun testRepeatedPreviewRefreshDoesNotAccumulateMarkupOrEditors() {
        var collections = 0
        val factory = EditorFactory.getInstance()
        val editorsBefore = factory.allEditors.toSet()
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType, _ ->
                val delegate = BracketPairAnalyzer(editor, fileType)
                BracketPairProvider { progress ->
                    collections++
                    delegate.collect(progress)
                }
            },
        )
        val editor = preview.previewEditor
        try {
            preview.update(PluginOptions())
            val tokenHighlights = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()
            repeat(250) { iteration ->
                preview.update(
                    PluginOptions(
                        guideLineWidth = 1 + iteration % 4,
                        guideOpacityPercent = 10 + (iteration % 19) * 5,
                        pairBackgroundOpacityPercent = iteration % 101,
                    ),
                )
                assertEquals(
                    tokenHighlights.size + 1,
                    editor.markupModel.allHighlighters.size,
                )
                assertEquals(
                    tokenHighlights,
                    editor.markupModel.allHighlighters.tokenHighlighters().toSet(),
                )
            }
            assertEquals(
                tokenHighlights.size,
                editor.markupModel.allHighlighters.tokenHighlighters().size,
            )
            assertEquals(1, collections)
            assertEquals(editorsBefore.size + 1, factory.allEditors.toSet().size)
        } finally {
            preview.dispose()
        }
        assertTrue(editor.isDisposed)
        assertEquals(editorsBefore, factory.allEditors.toSet())
    }

    private inline fun withConfigurable(
        block: (PluginConfigurable, Component) -> Unit,
    ) {
        val configurable = PluginConfigurable()
        val component = configurable.createComponent()
        try {
            block(configurable, component)
        } finally {
            configurable.disposeUIResources()
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

    private fun Component.button(text: String): JButton = descendants()
        .filterIsInstance<JButton>()
        .single { it.text == text }

    private fun Component.spinnerWithValue(value: Int): JSpinner = descendants()
        .filterIsInstance<JSpinner>()
        .single { (it.value as Number).toInt() == value }

    private fun Array<RangeHighlighter>.countGuide(): Int = count {
        it.getUserData(GUIDE_PAINT_STATE_KEY) != null
    }

    private fun Array<RangeHighlighter>.countActivePairs(): Int = count {
        it.layer == HighlighterLayer.ELEMENT_UNDER_CARET
    }

    private fun Array<RangeHighlighter>.tokenHighlighters(): List<RangeHighlighter> =
        filter { highlighter ->
            highlighter.textAttributesKey in BracketColorPalette.LEVEL_KEYS
        }

    private fun Array<RangeHighlighter>.activeGuidePair() = singleOrNull { highlighter ->
        highlighter.getUserData(GUIDE_PAINT_STATE_KEY) != null
    }?.getUserData(GUIDE_PAINT_STATE_KEY)?.guide?.pair

    private fun selectExample(
        preview: BracketSettingsPreview,
        id: String,
    ): PreviewExample {
        val example = (0 until preview.exampleSelector.itemCount)
            .map(preview.exampleSelector::getItemAt)
            .single { it.id == id }
        preview.exampleSelector.selectedItem = example
        return example
    }

    private fun replacePreviewText(preview: BracketSettingsPreview, text: String) {
        ApplicationManager.getApplication().runWriteAction {
            preview.previewEditor.document.setText(text)
        }
    }

    private fun waitForPreviewDecoration(
        preview: BracketSettingsPreview,
        message: String,
    ) {
        PlatformTestUtil.waitWithEventsDispatching(
            message,
            {
                preview.previewEditor.markupModel.allHighlighters
                    .tokenHighlighters()
                    .isNotEmpty() &&
                    preview.previewEditor.markupModel.allHighlighters.countGuide() == 1
            },
            10_000,
        )
    }

    private fun recognizedPairs(preview: BracketSettingsPreview): List<BracketPair> {
        val example = preview.exampleSelector.selectedItem as PreviewExample
        return BracketPairAnalyzer(
            preview.previewEditor,
            example.resolveFileType(),
        ).collect(EmptyProgressIndicator())
    }

    private fun assertRecognizedRangesAreValid(
        editor: Editor,
        pairs: List<BracketPair>,
    ) {
        val document = editor.document
        pairs.forEachIndexed { index, pair ->
            assertTrue("Pair $index has a negative opening offset", pair.openOffset >= 0)
            assertTrue("Pair $index has an empty opening token", pair.openTokenLength > 0)
            assertTrue(
                "Pair $index opening token exceeds the preview document",
                pair.openOffset + pair.openTokenLength <= document.textLength,
            )
            assertTrue(
                "Pair $index closes before its opening token",
                pair.closeOffset >= pair.openOffset + pair.openTokenLength,
            )
            assertTrue("Pair $index has an empty closing token", pair.closeTokenLength > 0)
            assertTrue(
                "Pair $index closing token exceeds the preview document",
                pair.closeOffset + pair.closeTokenLength <= document.textLength,
            )
            assertEquals(document.getLineNumber(pair.openOffset), pair.openLine)
            assertEquals(document.getLineNumber(pair.closeOffset), pair.closeLine)
        }
    }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { addAll(it.descendants()) }
        }
    }

    private companion object {
        const val UNAVAILABLE_LANGUAGE_ID = "BRACKET_PAIR_GUIDES_UNAVAILABLE_TEST"
    }
}
