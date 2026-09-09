package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.WindowManager
import com.sijunyang.bracketpairguides.editor.events.BracketGuideSettingsController
import com.sijunyang.bracketpairguides.editor.highlighting.NativeGuideConflictNotification
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.presentation.BracketGuideDrawing
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import java.awt.Frame
import java.awt.Toolkit

/**
 * Stable, dependency-free JMX boundary for out-of-process Driver tests.
 *
 * Public methods transport only primitive and String values. The Driver owns scenario definitions;
 * this bridge reconstructs a complete preference snapshot and commits it through the same
 * [BracketGuideSettingsController.applySettings] boundary as the Settings UI.
 */
@Suppress("unused") // Loaded reflectively by the out-of-process IntelliJ Driver.
object BracketGuideDriverBridge {
    @JvmStatic
    @Suppress("UnstableApiUsage") // The visual runtime is pinned; verifier covers supported IDEs.
    fun applyDarculaTheme(): String = driverTestOnEdt {
        val manager = LafManager.getInstance()
        manager.autodetect = false
        val darcula = manager.installedThemes.firstOrNull { theme -> theme.name == DARCULA_THEME }
        checkNotNull(darcula) { "The pinned IDE does not provide the $DARCULA_THEME theme" }
        manager.currentUIThemeLookAndFeel = darcula
        manager.updateUI()
        checkNotNull(manager.currentUIThemeLookAndFeel).name
    }

    @JvmStatic
    @Suppress("UnstableApiUsage") // Paired with applyDarculaTheme in the pinned visual runtime.
    fun currentTheme(): String = driverTestOnEdt {
        checkNotNull(LafManager.getInstance().currentUIThemeLookAndFeel).name
    }

    @JvmStatic
    fun configureIdeFrame(x: Int, y: Int, width: Int, height: Int): String = driverTestOnEdt {
        require(width > 0 && height > 0)
        val frame = checkNotNull(WindowManager.getInstance().findVisibleFrame()) {
            "No visible IDE frame"
        }
        frame.setBounds(x, y, width, height)
        "${frame.x}:${frame.y}:${frame.width}:${frame.height}"
    }

    @JvmStatic
    fun configureEditorAppearance(fontName: String, fontSize: Int): String = driverTestOnEdt {
        require(fontName.isNotBlank())
        require(fontSize in 8..72)
        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.editorFontName = fontName
        scheme.editorFontSize = fontSize
        scheme.lineSpacing = 1.0f
        CodeVisionSettings.getInstance().codeVisionEnabled = false
        EditorSettingsExternalizable.getInstance().isShowIntentionBulb = false
        "${scheme.editorFontName}:${scheme.editorFontSize}:${scheme.lineSpacing}"
    }

    /**
     * Releases any native-visual ownership, restores the native fixture, and resets all mutable
     * editor state that could leak from a preceding scenario.
     */
    @JvmStatic
    fun resetVisualScenario(filePathSuffix: String, caretLine: Int, caretColumn: Int): String = driverTestOnEdt {
        require(caretLine > 0 && caretColumn > 0)
        val editor = requiredEditor(filePathSuffix)

        BracketGuideSettingsController.getInstance().applySettings(RESET_PREFERENCES)
        CodeInsightSettings.getInstance().apply {
            HIGHLIGHT_BRACES = true
            HIGHLIGHT_SCOPE = true
        }
        EditorSettingsExternalizable.getInstance().isIndentGuidesShown = true
        EditorFactory.getInstance().refreshAllEditors()

        // Prevent the one-shot #30 advisory from obscuring editor-only scenario captures.
        NativeGuideConflictNotification.getInstance().loadState(
            NativeGuideConflictNotification.NotificationState(published = true),
        )

        editor.caretModel.removeSecondaryCarets()
        editor.selectionModel.removeSelection()
        editor.foldingModel.runBatchFoldingOperation {
            editor.foldingModel.allFoldRegions.forEach { region -> region.isExpanded = true }
        }
        positionCaret(editor, caretLine, caretColumn)
        visualScenarioState(filePathSuffix)
    }

    @JvmStatic
    fun isVisualScenarioReset(filePathSuffix: String, caretLine: Int, caretColumn: Int): Boolean = driverTestOnEdt {
        val editor = requiredEditor(filePathSuffix)
        BracketGuideSettings.getInstance().options == RESET_PREFERENCES &&
            nativeVisuals(editor) == NativeVisuals.ALL_ENABLED &&
            editor.caretModel.logicalPosition == logicalPosition(editor, caretLine, caretColumn) &&
            !editor.selectionModel.hasSelection() &&
            editor.foldingModel.allFoldRegions.none { region -> !region.isExpanded } &&
            editor.scrollingModel.horizontalScrollOffset == 0 &&
            editor.scrollingModel.verticalScrollOffset == 0
    }

    /** Constructs and atomically applies one complete visual-test preference snapshot. */
    @JvmStatic
    @Suppress("LongParameterList")
    fun applyVisualPreferences(
        enabled: Boolean,
        manageNativeVisuals: Boolean,
        nativeHighlightMode: String,
        hideNativeIndentGuides: Boolean,
        colorBracketTokens: Boolean,
        showActiveGuide: Boolean,
        showVerticalGuide: Boolean,
        showHorizontalGuides: Boolean,
        guideLineWidth: Int,
        guideOpacityPercent: Int,
        showActivePairBorder: Boolean,
        showActivePairBackground: Boolean,
        pairBackgroundOpacityPercent: Int,
        useIndependentComponentColors: Boolean,
        levelBaseColors: String,
        guideLineColors: String,
        pairBorderColors: String,
        pairBackgroundColors: String,
    ): String = driverTestOnEdt {
        val preferences =
            BracketGuidePreferences(
                enabled = enabled,
                disabledLanguageIds = emptySet(),
                intelliJIntegration =
                IntelliJIntegrationPreferences(
                    manageNativeVisuals = manageNativeVisuals,
                    nativeHighlightMode = NativeHighlightMode.valueOf(nativeHighlightMode),
                    hideNativeIndentGuides = hideNativeIndentGuides,
                ),
                colorBracketTokens = colorBracketTokens,
                showActiveGuide = showActiveGuide,
                showVerticalGuide = showVerticalGuide,
                showHorizontalGuides = showHorizontalGuides,
                guideLineWidth = guideLineWidth,
                guideOpacityPercent = guideOpacityPercent,
                showActivePairBorder = showActivePairBorder,
                showActivePairBackground = showActivePairBackground,
                pairBackgroundOpacityPercent = pairBackgroundOpacityPercent,
                useIndependentComponentColors = useIndependentComponentColors,
                levelBaseColors = parseColors(levelBaseColors),
                guideLineColors = parseColors(guideLineColors),
                pairBorderColors = parseColors(pairBorderColors),
                pairBackgroundColors = parseColors(pairBackgroundColors),
            )
        BracketGuideSettingsController.getInstance().applySettings(preferences)
        preferencesState(BracketGuideSettings.getInstance().options)
    }

    @JvmStatic
    fun currentVisualPreferences(): String = driverTestOnEdt {
        preferencesState(BracketGuideSettings.getInstance().options)
    }

    /** Re-emits the caret event after a native setting changes so IntelliJ refreshes its emphasis. */
    @JvmStatic
    fun retriggerCaretForVisualState(filePathSuffix: String, caretLine: Int, caretColumn: Int): String =
        driverTestOnEdt {
            val editor = requiredEditor(filePathSuffix)
            positionCaret(editor, caretLine, caretColumn)
            visualScenarioState(filePathSuffix)
        }

    /** Makes caret and paint state deterministic immediately before Driver takes a screenshot. */
    @JvmStatic
    fun prepareEditorForCapture(filePathSuffix: String): String = driverTestOnEdt {
        val frame = checkNotNull(WindowManager.getInstance().findVisibleFrame()) {
            "No visible IDE frame"
        }
        frame.extendedState = Frame.NORMAL
        frame.toFront()
        frame.requestFocus()
        val editor = requiredEditor(filePathSuffix)
        editor.settings.isShowIntentionBulb = false
        editor.setCaretEnabled(false)
        editor.setCaretVisible(false)
        editor.contentComponent.requestFocusInWindow()
        repaint(editor)
        val position = editor.caretModel.logicalPosition
        listOf(
            position.line + 1,
            position.column + 1,
            editor.selectionModel.hasSelection(),
            editor.foldingModel.allFoldRegions.count { region -> !region.isExpanded },
            editor.scrollingModel.horizontalScrollOffset,
            editor.scrollingModel.verticalScrollOffset,
        ).joinToString(":")
    }

    /** Native values and rendered markup, exposed as primitive text for bounded Driver polling. */
    @JvmStatic
    fun visualScenarioState(filePathSuffix: String): String = driverTestOnEdt {
        val editor = requiredEditor(filePathSuffix)
        val position = editor.caretModel.logicalPosition
        val native = nativeVisuals(editor)
        val presentation = presentationState(editor)
        listOf(
            native.matchedBrace,
            native.currentScope,
            native.globalIndent,
            native.editorIndent,
            presentation.guideVisible,
            presentation.pairBorderCount,
            presentation.pairBackgroundCount,
            presentation.tokenDecorationCount,
            position.line + 1,
            position.column + 1,
            editor.selectionModel.hasSelection(),
            editor.foldingModel.allFoldRegions.count { region -> !region.isExpanded },
            editor.scrollingModel.horizontalScrollOffset,
            editor.scrollingModel.verticalScrollOffset,
        ).joinToString(":")
    }

    private fun presentationState(editor: EditorEx): PresentationState {
        val highlighters = editor.markupModel.allHighlighters
        val guideVisible = highlighters.any { highlighter ->
            highlighter.isValid && highlighter.customRenderer is BracketGuideDrawing
        }
        val pairBorderCount = highlighters.count { highlighter ->
            if (!highlighter.isValid || highlighter.layer != HighlighterLayer.ELEMENT_UNDER_CARET) {
                return@count false
            }
            val attributes = highlighter.textAttributes ?: return@count false
            attributes.effectType == EffectType.BOXED && attributes.effectColor != null
        }
        val pairBackgroundCount = highlighters.count { highlighter ->
            if (!highlighter.isValid || highlighter.layer != HighlighterLayer.ELEMENT_UNDER_CARET) {
                return@count false
            }
            highlighter.textAttributes?.backgroundColor != null
        }
        val tokenDecorationCount = highlighters.count { highlighter ->
            highlighter.isValid &&
                highlighter.textAttributesKey?.externalName?.startsWith(TOKEN_COLOR_KEY_PREFIX) == true
        }
        return PresentationState(
            guideVisible,
            pairBorderCount,
            pairBackgroundCount,
            tokenDecorationCount,
        )
    }

    private fun nativeVisuals(editor: EditorEx): NativeVisuals {
        val codeInsight = CodeInsightSettings.getInstance()
        return NativeVisuals(
            matchedBrace = codeInsight.HIGHLIGHT_BRACES,
            currentScope = codeInsight.HIGHLIGHT_SCOPE,
            globalIndent = EditorSettingsExternalizable.getInstance().isIndentGuidesShown,
            editorIndent = editor.settings.isIndentGuidesShown,
        )
    }

    private fun parseColors(value: String): List<Int> = value.split(',').map { color ->
        color.toInt().also { parsed ->
            require(parsed in 0..0x00FF_FFFF) { "Stored colors must be 24-bit RGB values" }
        }
    }

    private fun preferencesState(preferences: BracketGuidePreferences): String = listOf(
        preferences.enabled,
        preferences.intelliJIntegration.manageNativeVisuals,
        preferences.intelliJIntegration.nativeHighlightMode.name,
        preferences.intelliJIntegration.hideNativeIndentGuides,
        preferences.colorBracketTokens,
        preferences.showActiveGuide,
        preferences.showVerticalGuide,
        preferences.showHorizontalGuides,
        preferences.guideLineWidth,
        preferences.guideOpacityPercent,
        preferences.showActivePairBorder,
        preferences.showActivePairBackground,
        preferences.pairBackgroundOpacityPercent,
        preferences.useIndependentComponentColors,
        preferences.levelBaseColors.joinToString(","),
        preferences.guideLineColors.joinToString(","),
        preferences.pairBorderColors.joinToString(","),
        preferences.pairBackgroundColors.joinToString(","),
    ).joinToString("|")

    private fun logicalPosition(editor: EditorEx, oneBasedLine: Int, oneBasedColumn: Int): LogicalPosition {
        val line = (oneBasedLine - 1).coerceIn(0, editor.document.lineCount - 1)
        val lineLength = editor.document.getLineEndOffset(line) - editor.document.getLineStartOffset(line)
        return LogicalPosition(line, (oneBasedColumn - 1).coerceIn(0, lineLength))
    }

    private fun positionCaret(editor: EditorEx, caretLine: Int, caretColumn: Int) {
        editor.setCaretEnabled(true)
        editor.setCaretVisible(true)
        editor.caretModel.moveToLogicalPosition(
            logicalPosition(editor, AWAY_CARET_LINE, AWAY_CARET_COLUMN),
        )
        editor.caretModel.moveToLogicalPosition(logicalPosition(editor, caretLine, caretColumn))
        editor.scrollingModel.scrollHorizontally(0)
        editor.scrollingModel.scrollVertically(0)
        repaint(editor)
    }

    private fun repaint(editor: EditorEx) {
        editor.contentComponent.repaint()
        editor.contentComponent.paintImmediately(editor.contentComponent.visibleRect)
        Toolkit.getDefaultToolkit().sync()
    }

    private fun requiredEditor(filePathSuffix: String): EditorEx = checkNotNull(
        EditorFactory.getInstance().allEditors.firstOrNull { candidate ->
            if (candidate.isDisposed) return@firstOrNull false
            val file = FileDocumentManager.getInstance().getFile(candidate.document)
            file?.path?.replace('\\', '/')?.endsWith(filePathSuffix.replace('\\', '/')) == true
        } as? EditorEx,
    ) { "No extended editor found for $filePathSuffix" }

    private fun <T> driverTestOnEdt(modalityState: ModalityState = ModalityState.nonModal(), action: () -> T): T {
        check(System.getProperty(DRIVER_TEST_PROPERTY) == "true") {
            "$DRIVER_TEST_PROPERTY must be true; this API is reserved for visual tests"
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        var result: Result<T>? = null
        application.invokeAndWait({ result = runCatching(action) }, modalityState)
        return checkNotNull(result).getOrThrow()
    }

    private data class NativeVisuals(
        val matchedBrace: Boolean,
        val currentScope: Boolean,
        val globalIndent: Boolean,
        val editorIndent: Boolean,
    ) {
        companion object {
            val ALL_ENABLED = NativeVisuals(true, true, true, true)
        }
    }

    private data class PresentationState(
        val guideVisible: Boolean,
        val pairBorderCount: Int,
        val pairBackgroundCount: Int,
        val tokenDecorationCount: Int,
    )

    private const val DARCULA_THEME = "Darcula"
    private const val DRIVER_TEST_PROPERTY = "bracket.pair.guides.driver.test"
    private const val TOKEN_COLOR_KEY_PREFIX = "BRACKET_PAIR_GUIDES_BRACKET_DEPTH_"
    private const val AWAY_CARET_LINE = 2
    private const val AWAY_CARET_COLUMN = 1
    private val RESET_PREFERENCES = BracketGuidePreferences(enabled = false)
}
