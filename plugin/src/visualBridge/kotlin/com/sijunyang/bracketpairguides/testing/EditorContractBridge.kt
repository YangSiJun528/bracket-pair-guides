package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.ui.BalloonImpl
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.WindowManager
import com.sijunyang.bracketpairguides.plugin.GuidePlugin
import com.sijunyang.bracketpairguides.ui.preferences.BracketGuidePreferences
import com.sijunyang.bracketpairguides.ui.preferences.IntelliJIntegrationPreferences
import com.sijunyang.bracketpairguides.ui.settings.GuideUiSettings
import java.util.concurrent.atomic.AtomicReference

/** Test-only primitive transport; all preference changes use the production settings command. */
@Suppress("unused")
object EditorContractBridge {
    private var observingNotifications = false
    private var advisoryCount = 0
    private var advisory: Notification? = null

    @JvmStatic
    @Suppress("UnstableApiUsage")
    fun configure(): String = edt {
        if (!observingNotifications) {
            val project = checkNotNull(editor().project)
            project.messageBus.connect(project).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Bracket Pair Guides Native Visual Conflict") {
                        advisoryCount++
                        advisory = notification
                    }
                }
            })
            observingNotifications = true
        }
        GuideUiSettings.apply(BracketGuidePreferences(enabled = false))
        val appearance = LafManager.getInstance()
        appearance.autodetect = false
        appearance.currentUIThemeLookAndFeel = checkNotNull(appearance.installedThemes.firstOrNull { it.name == "Darcula" })
        appearance.updateUI()
        EditorColorsManager.getInstance().globalScheme.apply {
            editorFontName = "JetBrains Mono"
            editorFontSize = 14
            lineSpacing = 1.0f
        }
        CodeVisionSettings.getInstance().codeVisionEnabled = false
        EditorSettingsExternalizable.getInstance().apply {
            isShowIntentionBulb = false
            isIndentGuidesShown = true
        }
        CodeInsightSettings.getInstance().apply { HIGHLIGHT_BRACES = true; HIGHLIGHT_SCOPE = true }
        checkNotNull(WindowManager.getInstance().findVisibleFrame()).apply {
            setBounds(100, 100, 1280, 900)
            isAlwaysOnTop = true
        }
        nativeState()
    }

    @JvmStatic
    fun scenario(name: String): String = edt {
        GuideUiSettings.apply(BracketGuidePreferences(enabled = false))
        CodeInsightSettings.getInstance().apply { HIGHLIGHT_BRACES = true; HIGHLIGHT_SCOPE = true }
        EditorSettingsExternalizable.getInstance().isIndentGuidesShown = true
        val editor = editor()
        editor.caretModel.moveToLogicalPosition(if (name.startsWith("native-")) LogicalPosition(2, 20) else LogicalPosition(3, 15))
        (editor as EditorEx).setCaretEnabled(false)
        editor.setCaretVisible(false)
        editor.scrollingModel.scrollVertically(0)
        editor.scrollingModel.scrollHorizontally(0)
        editor.settings.isCaretRowShown = false
        editor.settings.isLineNumbersShown = false
        editor.contentComponent.requestFocusInWindow()
        val all = BracketGuidePreferences(showActivePairBorder = true, showActivePairBackground = true)
        val preferences = when (name) {
            "horizontal-only" -> all.copy(showVerticalGuide = false, showActivePairBorder = false, showActivePairBackground = false)
            "vertical-only" -> all.copy(showHorizontalGuides = false, showActivePairBorder = false, showActivePairBackground = false)
            "pair-border-only" -> all.copy(showActiveGuide = false, showActivePairBackground = false)
            "pair-background-only" -> all.copy(showActiveGuide = false, showActivePairBorder = false)
            "all-components", "native-highlight-suppressed" -> all
            "bracket-colorization-off" -> all.copy(colorBracketTokens = false)
            "plugin-disabled" -> all.copy(enabled = false)
            "native-visuals-unmanaged" -> all.copy(intelliJIntegration = IntelliJIntegrationPreferences(manageNativeVisuals = false))
            "default-palette" -> all.copy(guideLineWidth = 3, pairBackgroundOpacityPercent = 45)
            "custom-palette" -> all.copy(
                guideLineWidth = 3, pairBackgroundOpacityPercent = 45,
                useIndependentComponentColors = true,
                levelBaseColors = listOf(0xFF5555, 0x55FF55, 0x5599FF, 0xFFFF55, 0xFF55FF, 0x55FFFF),
                guideLineColors = List(6) { 0x55FFFF },
                pairBorderColors = List(6) { 0xFF55FF },
                pairBackgroundColors = List(6) { 0xFFFF55 },
            )
            else -> error("Unknown visual contract: $name")
        }
        val plugin = ApplicationManager.getApplication().getService(GuidePlugin::class.java)
        plugin.applyPreferences(preferences)
        plugin.request(editor)
        stateOnEdt(editor)
    }

    /** Opens the registered, real configurable; Driver alone activates its controls and Apply. */
    @JvmStatic
    fun openSettings() {
        ApplicationManager.getApplication().invokeLater({
            ShowSettingsUtil.getInstance().showSettingsDialog(editor().project, "Bracket Pair Guides")
        }, ModalityState.nonModal())
    }

    @JvmStatic
    fun settingsState(): String = edt {
        "${GuideUiSettings.current().intelliJIntegration.manageNativeVisuals}:${nativeState()}"
    }

    /** Public notification-bus and actual balloon lifecycle observations, never proof-state getters. */
    @JvmStatic
    fun advisoryState(): String = edt {
        val balloon = advisory?.balloon
        // The pinned Driver SDK exposes visibility on BalloonImpl, not the Balloon interface.
        val componentShowing = (balloon as? BalloonImpl)?.component?.isShowing == true
        val visible = (balloon as? BalloonImpl)?.isVisible == true && componentShowing
        "$advisoryCount:$visible:${advisory?.isExpired == true}:" +
            "${balloon?.wasFadedIn() == true}:${balloon?.isDisposed == true}:${balloon != null}:" +
            "$componentShowing:${balloon?.javaClass?.name}"
    }

    @JvmStatic
    fun dismissAdvisory() = edt { advisory?.hideBalloon(); Unit }

    @JvmStatic
    fun caretCycle(): String = edt {
        val editor = editor()
        val plugin = ApplicationManager.getApplication().getService(GuidePlugin::class.java)
        listOf(LogicalPosition(3, 15), LogicalPosition(6, 20), LogicalPosition(3, 15)).forEach {
            editor.caretModel.moveToLogicalPosition(it)
            plugin.request(editor)
        }
        stateOnEdt(editor)
    }

    @JvmStatic
    fun focusEditor(focused: Boolean): String = edt {
        val editor = editor()
        if (focused) editor.contentComponent.requestFocusInWindow()
        else checkNotNull(WindowManager.getInstance().findVisibleFrame()).rootPane.apply {
            isFocusable = true
            requestFocusInWindow()
        }
        stateOnEdt(editor)
    }

    @JvmStatic
    fun showEditor(visible: Boolean): String = edt {
        editor().component.isVisible = visible
        stateOnEdt(editor())
    }

    @JvmStatic
    fun disable(): String = edt {
        GuideUiSettings.apply(GuideUiSettings.current().copy(enabled = false))
        nativeState()
    }

    @JvmStatic
    fun insertIndent(): Long = edt {
        val editor = editor()
        WriteCommandAction.runWriteCommandAction(editor.project) {
            editor.document.insertString(editor.document.getLineStartOffset(4), "  ")
            check(editor.markupModel.allHighlighters.none {
                it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true
            }) { "Affected old guide remained inside the edit callback" }
        }
        editor.document.modificationStamp
    }

    @JvmStatic
    fun state(): String = edt { stateOnEdt(editor()) }

    private fun stateOnEdt(editor: Editor): String {
        val rendered = editor.markupModel.allHighlighters.count {
            it.customRenderer?.javaClass?.name?.startsWith("com.sijunyang.bracketpairguides.") == true
        }
        val tokens = editor.markupModel.allHighlighters.count {
            it.textAttributesKey?.externalName?.startsWith("BRACKET_PAIR_GUIDES_BRACKET_DEPTH_") == true
        }
        val pairs = editor.markupModel.allHighlighters.count {
            val attributes = it.getTextAttributes(editor.colorsScheme)
            it.layer == HighlighterLayer.ELEMENT_UNDER_CARET && it.endOffset - it.startOffset == 1 &&
                (attributes?.effectType == EffectType.BOXED || attributes?.backgroundColor != null)
        }
        return "${editor.document.modificationStamp}:$rendered:${GuideUiSettings.current().enabled}:${nativeState()}:$tokens:${editor.contentComponent.isFocusOwner}:${editor.contentComponent.isShowing}:$pairs"
    }

    private fun nativeState(): String {
        val insight = CodeInsightSettings.getInstance()
        return "${insight.HIGHLIGHT_BRACES}:${insight.HIGHLIGHT_SCOPE}:${EditorSettingsExternalizable.getInstance().isIndentGuidesShown}"
    }

    private fun editor(): Editor = checkNotNull(EditorFactory.getInstance().allEditors.firstOrNull {
        FileDocumentManager.getInstance().getFile(it.document)?.name == "Contract.java" && !it.isDisposed
    })

    private fun <T> edt(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val answer = AtomicReference<Result<T>>()
        application.invokeAndWait({ answer.set(runCatching(action)) }, ModalityState.any())
        return answer.get().getOrThrow()
    }
}
