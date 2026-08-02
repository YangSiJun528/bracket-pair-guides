package com.sijunyang.bracketpairguides.settings

import com.sijunyang.bracketpairguides.analyzer.BracketPairAnalyzer
import com.sijunyang.bracketpairguides.analyzer.BracketPairProvider
import com.sijunyang.bracketpairguides.renderer.ActiveBracketPairIndex
import com.sijunyang.bracketpairguides.renderer.GuideLineHighlightingPass
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColorPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JSpinner

class PluginConfigurableTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        PluginSettings.getInstance().loadState(PluginSettings.State())
    }

    fun testUsesCompactPaletteAndBoundedSidebarLayout() {
        withConfigurable { configurable, component ->
            val descendants = component.descendants()
            val checkboxNames = descendants.filterIsInstance<JBCheckBox>()
                .mapNotNull { it.text }
                .toSet()

            assertTrue("Show pair border" in checkboxNames)
            assertTrue("Show pair background" in checkboxNames)
            assertTrue("Customize component colors separately" in checkboxNames)
            assertEquals(0, descendants.count { it is ColorPanel })

            val palette = descendants.filterIsInstance<ColorPaletteTable>().single()
            assertEquals(BracketColorPalette.COLOR_COUNT, palette.table.rowCount)
            assertEquals(5, palette.table.columnCount)
            assertTrue(palette.preferredSize.width <= JBUI.scale(420))
            assertTrue(palette.minimumSize.width <= palette.preferredSize.width)
            assertEquals(
                palette.table.rowHeight * BracketColorPalette.COLOR_COUNT +
                    palette.table.tableHeader.preferredSize.height + JBUI.scale(2),
                palette.preferredSize.height,
            )

            val comments = descendants.filterIsInstance<JEditorPane>()
                .filter { it.text.contains("inherit") || it.text.contains("repeat") }
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
            assertTrue(splitter.firstComponent is JBScrollPane)
            assertSame(preview, splitter.secondComponent)
            assertNull(
                (preview.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH),
            )
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

    fun testDependentRowsAndPaletteColumnsFollowTheirToggles() {
        withConfigurable { _, component ->
            val descendants = component.descendants()
            val palette = descendants.filterIsInstance<ColorPaletteTable>().single()
            val table = palette.table
            val guide = component.checkBox("Show active pair guide")
            val vertical = component.checkBox("Vertical segment")
            val horizontal = component.checkBox("Opening and closing segments")
            val border = component.checkBox("Show pair border")
            val background = component.checkBox("Show pair background")
            val advanced = component.checkBox("Customize component colors separately")
            val master = component.checkBox("Enable bracket pair guides")
            val width = component.spinnerWithValue(1)
            val guideOpacity = component.spinnerWithValue(100)
            val backgroundOpacity = component.spinnerWithValue(22)
            val borderStyle = descendants.filterIsInstance<JComboBox<*>>().single { combo ->
                combo.itemCount > 0 && combo.getItemAt(0) is PairBorderStyle
            }

            assertTrue(table.model.isCellEditable(0, 1))
            assertFalse(table.model.isCellEditable(0, 2))
            assertFalse(table.model.isCellEditable(0, 3))
            assertFalse(table.model.isCellEditable(0, 4))

            advanced.doClick()
            assertTrue(table.model.isCellEditable(0, 2))
            assertTrue(table.model.isCellEditable(0, 3))
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
            assertFalse(borderStyle.isEnabled)
            assertFalse(table.model.isCellEditable(0, 3))

            background.doClick()
            assertFalse(backgroundOpacity.isEnabled)
            assertFalse(table.model.isCellEditable(0, 4))

            master.doClick()
            assertFalse(table.isEnabled)
            assertFalse(
                component.checkBox("Color matching bracket tokens by nesting level").isEnabled,
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
            val advanced = component.checkBox("Customize component colors separately")
            val customGuide = Color(0x12, 0x6A, 0xD4)

            advanced.doClick()
            palette.table.model.setValueAt(customGuide, 0, 2)
            assertEquals(customGuide, palette.color(0, PaletteComponent.GUIDE))

            advanced.doClick()
            assertFalse(palette.table.model.isCellEditable(0, 2))
            assertEquals(customGuide, palette.color(0, PaletteComponent.GUIDE))
            assertTrue(configurable.isModified)

            configurable.apply()
            val persisted = PluginSettings.getInstance().state
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

    fun testPreviewUpdatesDraftWithoutPersistingUntilApply() {
        withConfigurable { configurable, component ->
            val preview = component.descendants()
                .filterIsInstance<BracketSettingsPreview>()
                .single()
            val initialState = PluginSettings.getInstance().state.deepCopy()
            val selectedExample = preview.exampleSelector.selectedItem as PreviewExample

            replacePreviewText(preview, preview.previewEditor.document.text + "\n")
            preview.previewEditor.caretModel.moveToOffset(
                selectedExample.initialCaretOffset,
            )
            preview.recognizeNowForTest()
            assertFalse(configurable.isModified)
            assertEquals(initialState, PluginSettings.getInstance().state)

            val pairCount = preview.recognizedPairs.size
            assertTrue(pairCount > 0)
            assertEquals(
                pairCount * 2 + 3,
                preview.previewEditor.markupModel.allHighlighters.size,
            )
            assertEquals(1, preview.previewEditor.markupModel.allHighlighters.countGuide())
            assertEquals(2, preview.previewEditor.markupModel.allHighlighters.countActivePairs())

            component.checkBox("Color matching bracket tokens by nesting level").doClick()
            assertEquals(3, preview.previewEditor.markupModel.allHighlighters.size)
            assertEquals(initialState, PluginSettings.getInstance().state)
            assertTrue(configurable.isModified)

            component.checkBox("Show active pair guide").doClick()
            assertEquals(2, preview.previewEditor.markupModel.allHighlighters.size)
            component.checkBox("Show pair border").doClick()
            component.checkBox("Show pair background").doClick()
            assertEquals(0, preview.previewEditor.markupModel.allHighlighters.size)

            component.checkBox("Enable bracket pair guides").doClick()
            assertEquals(initialState, PluginSettings.getInstance().state)

            configurable.apply()
            assertFalse(PluginSettings.getInstance().state.enabled)
            assertFalse(PluginSettings.getInstance().state.colorBracketTokens)
            assertFalse(configurable.isModified)
        }
    }

    fun testPreviewOffersFiveLexerBackedEditableExamples() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginSettings.State())
            val examples = (0 until preview.exampleSelector.itemCount).map { index ->
                preview.exampleSelector.getItemAt(index)
            }
            assertEquals(
                listOf("java", "kotlin", "json", "xml", "markdown"),
                examples.map(PreviewExample::id),
            )
            assertFalse(editor.isViewer)

            examples.forEach { example ->
                val fileType = example.resolveFileType()
                assertFalse(fileType === UnknownFileType.INSTANCE)
                assertFalse(fileType === PlainTextFileType.INSTANCE)

                preview.exampleSelector.selectedItem = example
                assertEquals(example.source, editor.document.text)
                assertEquals(example.initialCaretOffset, editor.caretModel.offset)
                assertTrue(
                    "${example.displayName} should recognize pairs",
                    preview.recognizedPairs.isNotEmpty(),
                )
                assertRecognizedRangesAreValid(preview)

                val activeIndex = ActiveBracketPairIndex.build(preview.recognizedPairs)
                val active = preview.recognizedPairs.getOrNull(
                    activeIndex.activePairIndex(editor.caretModel.offset),
                )
                assertNotNull("${example.displayName} should start inside a pair", active)
                assertEquals(1, editor.markupModel.allHighlighters.countGuide())
                assertEquals(2, editor.markupModel.allHighlighters.countActivePairs())
                assertEquals(
                    preview.recognizedPairs.size * 2 + 3,
                    editor.markupModel.allHighlighters.size,
                )
            }
        } finally {
            preview.dispose()
        }
        assertTrue(editor.isDisposed)
    }

    fun testPreviewEditingUsesTheSelectedLexerAndIgnoresStringBraces() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginSettings.State())
            selectExample(preview, "java")
            val initialRuns = preview.analysisRunCount
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
            preview.recognizeNowForTest()

            assertEquals(initialRuns + 1, preview.analysisRunCount)
            assertEquals(source, editor.document.text)
            assertTrue(preview.recognizedPairs.isNotEmpty())
            val ignoredBraceOffset = source.indexOf("\"}\"") + 1
            assertFalse(
                preview.recognizedPairs.any { pair ->
                    pair.openOffset == ignoredBraceOffset ||
                        pair.closeOffset == ignoredBraceOffset
                },
            )
            assertRecognizedRangesAreValid(preview)
            assertEquals(
                preview.recognizedPairs.size * 2 + 3,
                editor.markupModel.allHighlighters.size,
            )
        } finally {
            preview.dispose()
        }
    }

    fun testCaretMovementReusesRecognitionAndTokenHighlights() {
        var collections = 0
        val preview = BracketSettingsPreview(
            PreviewPairProviderFactory { editor, fileType ->
                val delegate = BracketPairAnalyzer(editor, fileType)
                BracketPairProvider { progress ->
                    collections++
                    delegate.collect(progress)
                }
            },
        )
        val editor = preview.previewEditor
        try {
            preview.update(PluginSettings.State())
            val pairs = preview.recognizedPairs
            assertTrue(pairs.isNotEmpty())
            assertEquals(1, collections)
            val analyses = preview.analysisRunCount
            val activeIndex = ActiveBracketPairIndex.build(pairs)
            val tokenHighlights = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()

            val deepest = pairs.maxBy { it.depth }
            val deepestOffset = ((deepest.openOffset + deepest.openTokenLength) +
                deepest.closeOffset) / 2
            editor.caretModel.moveToOffset(deepestOffset)
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
            assertEquals(analyses, preview.analysisRunCount)
            assertEquals(1, collections)
        } finally {
            preview.dispose()
        }
    }

    fun testEachExamplePreservesItsTemporaryBufferAndResetAffectsOnlyCurrentExample() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            val java = selectExample(preview, "java")
            val editedJava = "class Edited { void run() { call(); } }"
            val javaCaret = editedJava.indexOf("call")
            replacePreviewText(preview, editedJava)
            editor.caretModel.moveToOffset(javaCaret)

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
            val analysesBeforeReturn = preview.analysisRunCount
            selectExample(preview, "java")

            assertEquals(largeJava, preview.previewEditor.document.text)
            assertEquals(analysesBeforeReturn, preview.analysisRunCount)
            assertTrue(preview.recognizedPairs.isEmpty())
        } finally {
            preview.dispose()
        }
    }

    fun testDensePreviewCapsTokenHighlightersOnTheEdt() {
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginSettings.State())
            selectExample(preview, "java")
            val denseJava = buildString {
                append("class Dense { void run() {\n")
                repeat(750) { append("call();\n") }
                append("} }")
            }
            replacePreviewText(preview, denseJava)
            editor.caretModel.moveToOffset(denseJava.indexOf("call"))
            preview.recognizeNowForTest()

            assertTrue(
                preview.recognizedPairs.size >
                    PreviewDecorationController.MAX_TOKEN_PAIR_HIGHLIGHTS,
            )
            assertEquals(
                PreviewDecorationController.MAX_TOKEN_PAIR_HIGHLIGHTS * 2,
                editor.markupModel.allHighlighters.tokenHighlighters().size,
            )
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

    fun testRepeatedPreviewRefreshDoesNotAccumulateMarkupOrEditors() {
        val factory = EditorFactory.getInstance()
        val editorsBefore = factory.allEditors.toSet()
        val preview = BracketSettingsPreview()
        val editor = preview.previewEditor
        try {
            preview.update(PluginSettings.State())
            val pairCount = preview.recognizedPairs.size
            val analyses = preview.analysisRunCount
            val tokenHighlights = editor.markupModel.allHighlighters
                .tokenHighlighters()
                .toSet()
            repeat(250) { iteration ->
                preview.update(
                    PluginSettings.State(
                        guideLineWidth = 1 + iteration % 4,
                        guideOpacityPercent = 10 + (iteration % 19) * 5,
                        pairBackgroundOpacityPercent = iteration % 101,
                    ),
                )
                assertEquals(
                    pairCount * 2 + 3,
                    editor.markupModel.allHighlighters.size,
                )
                assertEquals(
                    tokenHighlights,
                    editor.markupModel.allHighlighters.tokenHighlighters().toSet(),
                )
            }
            assertEquals(pairCount * 2, editor.markupModel.allHighlighters.tokenHighlighters().size)
            assertEquals(analyses, preview.analysisRunCount)
            assertEquals(editorsBefore.size + 1, factory.allEditors.toSet().size)
        } finally {
            preview.dispose()
        }
        val runsAfterDispose = preview.analysisRunCount
        preview.recognizeNowForTest()
        assertEquals(runsAfterDispose, preview.analysisRunCount)
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

    private fun Component.checkBox(text: String): JBCheckBox = descendants()
        .filterIsInstance<JBCheckBox>()
        .single { it.text == text }

    private fun Component.spinnerWithValue(value: Int): JSpinner = descendants()
        .filterIsInstance<JSpinner>()
        .single { (it.value as Number).toInt() == value }

    private fun Array<RangeHighlighter>.countGuide(): Int = count {
        it.getUserData(GuideLineHighlightingPass.GUIDE_KEY) != null
    }

    private fun Array<RangeHighlighter>.countActivePairs(): Int = count {
        it.getUserData(GuideLineHighlightingPass.ACTIVE_PAIR_HIGHLIGHT_KEY) == true
    }

    private fun Array<RangeHighlighter>.tokenHighlighters(): List<RangeHighlighter> =
        filter { highlighter ->
            highlighter.getUserData(GuideLineHighlightingPass.OWNED_HIGHLIGHTER_KEY) == true &&
                highlighter.getUserData(GuideLineHighlightingPass.GUIDE_KEY) == null &&
                highlighter.getUserData(
                    GuideLineHighlightingPass.ACTIVE_PAIR_HIGHLIGHT_KEY,
                ) != true
        }

    private fun Array<RangeHighlighter>.activeGuidePair() = singleOrNull { highlighter ->
        highlighter.getUserData(GuideLineHighlightingPass.GUIDE_KEY) != null
    }?.getUserData(GuideLineHighlightingPass.GUIDE_KEY)?.pair

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

    private fun assertRecognizedRangesAreValid(preview: BracketSettingsPreview) {
        val document = preview.previewEditor.document
        preview.recognizedPairs.forEachIndexed { index, pair ->
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

    private fun PluginSettings.State.deepCopy(): PluginSettings.State = copy(
        levelBaseColors = levelBaseColors.toMutableList(),
        guideLineColors = guideLineColors.toMutableList(),
        pairBorderColors = pairBorderColors.toMutableList(),
        pairBackgroundColors = pairBackgroundColors.toMutableList(),
    )

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { addAll(it.descendants()) }
        }
    }
}
