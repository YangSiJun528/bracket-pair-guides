package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.ui.LafManager
import com.intellij.notification.Notification
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.NewUI
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.events.BracketGuideSettingsController
import com.sijunyang.bracketpairguides.editor.highlighting.NativeGuideConflictBalloon
import com.sijunyang.bracketpairguides.editor.highlighting.NativeGuideConflictNotification
import com.sijunyang.bracketpairguides.preferences.NativeHighlightMode
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings
import com.sijunyang.bracketpairguides.settings.ui.BracketGuideSettingsPage
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window

/**
 * Stable, dependency-free JMX boundary for out-of-process Driver tests.
 *
 * Keep every public method limited to primitive and String values. The Driver
 * API is deliberately absent from the production plugin classpath.
 */
@Suppress("unused") // Loaded reflectively by the out-of-process IntelliJ Driver.
object BracketGuideDriverBridge {
    private var nativeConflictNotificationForCapture: Notification? = null

    @JvmStatic
    fun setShowActiveGuide(enabled: Boolean): Boolean = driverTestOnEdt {
        val current = BracketGuideSettings.getInstance().options
        BracketGuideSettingsController.getInstance().applySettings(
            current.copy(showActiveGuide = enabled),
        )
        BracketGuideSettings.getInstance().options.showActiveGuide
    }

    @JvmStatic
    @Suppress("UnstableApiUsage") // The visual runtime is pinned; verifier covers the supported IDE range.
    fun applyDarculaTheme(): String = driverTestOnEdt {
        val manager = LafManager.getInstance()
        manager.autodetect = false
        val darcula = manager.installedThemes.firstOrNull { theme ->
            theme.name == DARCULA_THEME
        }
        checkNotNull(darcula) {
            "The pinned IDE does not provide the $DARCULA_THEME theme"
        }
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

    @JvmStatic
    fun prepareEditorForCapture(filePathSuffix: String): String = driverTestOnEdt {
        val frame = checkNotNull(WindowManager.getInstance().findVisibleFrame()) {
            "No visible IDE frame"
        }
        frame.extendedState = Frame.NORMAL
        frame.toFront()
        frame.requestFocus()
        val editor = checkNotNull(editorForFile(filePathSuffix) as? EditorEx) {
            "No extended editor found for $filePathSuffix"
        }
        editor.settings.isShowIntentionBulb = false
        editor.setCaretEnabled(false)
        editor.setCaretVisible(false)
        editor.contentComponent.requestFocusInWindow()
        editor.contentComponent.repaint()
        editor.contentComponent.paintImmediately(editor.contentComponent.visibleRect)
        Toolkit.getDefaultToolkit().sync()
        val position = editor.caretModel.logicalPosition
        "${position.line + 1}:${position.column + 1}"
    }

    @JvmStatic
    fun openSettingsForCapture(filePathSuffix: String): Boolean = driverTestOnEdt {
        val project = checkNotNull(editorForFile(filePathSuffix)?.project?.takeUnless { it.isDisposed }) {
            "No open project found for $filePathSuffix"
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    BracketGuideSettingsPage::class.java,
                )
            }
        }
        true
    }

    @JvmStatic
    fun raiseSettingsForCapture(): Boolean = driverTestOnEdt(ModalityState.any()) {
        val window = settingsWindow() ?: return@driverTestOnEdt false
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        window.isShowing && window.isAlwaysOnTop
    }

    @JvmStatic
    fun closeSettingsAfterCapture(): Boolean = driverTestOnEdt(ModalityState.any()) {
        val window = settingsWindow() ?: return@driverTestOnEdt false
        val settingsDialog = DialogWrapper.findInstance(window) ?: return@driverTestOnEdt false
        window.isAlwaysOnTop = false
        settingsDialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        true
    }

    @JvmStatic
    fun prepareNativeOverlapForCapture(filePathSuffix: String): String = driverTestOnEdt {
        val editor = checkNotNull(editorForFile(filePathSuffix)) {
            "No editor found for $filePathSuffix"
        }
        // The visual test publishes the real balloon explicitly after the
        // overlap image is stable. Prevent the one-shot detector from racing
        // that deterministic UI capture.
        NativeGuideConflictNotification.getInstance().loadState(
            NativeGuideConflictNotification.NotificationState(published = true),
        )
        val current = BracketGuideSettings.getInstance().options
        BracketGuideSettingsController.getInstance().applySettings(
            current.copy(
                intelliJIntegration =
                current.intelliJIntegration.copy(
                    manageNativeVisuals = true,
                    nativeHighlightMode = NativeHighlightMode.LEAVE_INTELLIJ_HIGHLIGHTING_UNCHANGED,
                    hideNativeIndentGuides = false,
                ),
            ),
        )
        CodeInsightSettings.getInstance().apply {
            HIGHLIGHT_BRACES = true
            HIGHLIGHT_SCOPE = true
        }
        EditorSettingsExternalizable.getInstance().isIndentGuidesShown = true
        editor.settings.isIndentGuidesShown = true
        editor.project?.takeUnless { it.isDisposed }?.let { project ->
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
        nativeVisualState(filePathSuffix)
    }

    @JvmStatic
    fun showNativeGuideConflictNotificationForCapture(filePathSuffix: String): Boolean = driverTestOnEdt {
        val project = checkNotNull(editorForFile(filePathSuffix)?.project?.takeUnless { it.isDisposed }) {
            "No open project found for $filePathSuffix"
        }
        nativeConflictNotificationForCapture?.expire()
        NativeGuideConflictBalloon.create(project).also { notification ->
            nativeConflictNotificationForCapture = notification
            notification.notify(project)
        }
        true
    }

    @JvmStatic
    fun expireNativeGuideConflictNotificationAfterCapture(): Boolean = driverTestOnEdt(ModalityState.any()) {
        val notification = nativeConflictNotificationForCapture ?: return@driverTestOnEdt false
        nativeConflictNotificationForCapture = null
        notification.expire()
        true
    }

    @JvmStatic
    fun activeGuideState(filePathSuffix: String): String = driverTestOnEdt {
        val editor = editorForFile(filePathSuffix) ?: return@driverTestOnEdt NO_EDITOR
        val session = EditorGuideSessions.get(editor) ?: return@driverTestOnEdt NO_SESSION
        if (session.isActiveGuideVisible) VISIBLE else HIDDEN
    }

    @JvmStatic
    fun nativeVisualState(filePathSuffix: String): String = driverTestOnEdt {
        val editor = checkNotNull(editorForFile(filePathSuffix)) {
            "No editor found for $filePathSuffix"
        }
        val codeInsight = CodeInsightSettings.getInstance()
        val globalIndent = EditorSettingsExternalizable.getInstance().isIndentGuidesShown
        listOf(
            codeInsight.HIGHLIGHT_BRACES,
            codeInsight.HIGHLIGHT_SCOPE,
            globalIndent,
            editor.settings.isIndentGuidesShown,
            if (NewUI.isEnabled()) NEW_UI else CLASSIC_UI,
        ).joinToString(":")
    }

    @JvmStatic
    fun setHideNativeIndentGuides(filePathSuffix: String, hidden: Boolean): String = driverTestOnEdt {
        val current = BracketGuideSettings.getInstance().options
        BracketGuideSettingsController.getInstance().applySettings(
            current.copy(
                intelliJIntegration =
                current.intelliJIntegration.copy(
                    hideNativeIndentGuides = hidden,
                ),
            ),
        )
        nativeIndentState(filePathSuffix)
    }

    @JvmStatic
    fun setEditorIndentGuides(filePathSuffix: String, shown: Boolean): String = driverTestOnEdt {
        val editor = checkNotNull(editorForFile(filePathSuffix)) {
            "No editor found for $filePathSuffix"
        }
        editor.settings.isIndentGuidesShown = shown
        editor.project?.takeUnless { it.isDisposed }?.let { project ->
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
        nativeIndentState(filePathSuffix)
    }

    private fun nativeIndentState(filePathSuffix: String): String {
        val editor = checkNotNull(editorForFile(filePathSuffix)) {
            "No editor found for $filePathSuffix"
        }
        return "${EditorSettingsExternalizable.getInstance().isIndentGuidesShown}:" +
            editor.settings.isIndentGuidesShown
    }

    private fun editorForFile(filePathSuffix: String) =
        EditorFactory.getInstance().allEditors.firstOrNull { candidate ->
            if (candidate.isDisposed) return@firstOrNull false
            val file = FileDocumentManager.getInstance().getFile(candidate.document)
            file?.path?.replace('\\', '/')?.endsWith(filePathSuffix.replace('\\', '/')) == true
        }

    private fun settingsWindow(): Window? = Window.getWindows().firstOrNull { window ->
        window.isShowing && DialogWrapper.findInstance(window)?.title == SETTINGS_TITLE
    }

    private fun <T> driverTestOnEdt(modalityState: ModalityState = ModalityState.nonModal(), action: () -> T): T {
        check(System.getProperty(DRIVER_TEST_PROPERTY) == "true") {
            "$DRIVER_TEST_PROPERTY must be true; this API is reserved for visual tests"
        }
        return onEdt(modalityState, action)
    }

    private fun <T> onEdt(modalityState: ModalityState, action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        var result: Result<T>? = null
        application.invokeAndWait(
            { result = runCatching(action) },
            modalityState,
        )
        return checkNotNull(result).getOrThrow()
    }

    private const val NO_EDITOR = "NO_EDITOR"
    private const val NO_SESSION = "NO_SESSION"
    private const val VISIBLE = "VISIBLE"
    private const val HIDDEN = "HIDDEN"
    private const val DARCULA_THEME = "Darcula"
    private const val NEW_UI = "NEW_UI"
    private const val CLASSIC_UI = "CLASSIC_UI"
    private const val SETTINGS_TITLE = "Settings"
    private const val DRIVER_TEST_PROPERTY = "bracket.pair.guides.driver.test"
}
