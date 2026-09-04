package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.WindowManager
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import com.sijunyang.bracketpairguides.editor.events.BracketGuideSettingsController
import com.sijunyang.bracketpairguides.settings.BracketGuideSettings

/**
 * Stable, dependency-free JMX boundary for out-of-process Driver tests.
 *
 * Keep every public method limited to primitive and String values. The Driver
 * API is deliberately absent from the production plugin classpath.
 */
@Suppress("unused") // Loaded reflectively by the out-of-process IntelliJ Driver.
object BracketGuideDriverBridge {
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
        val editor = checkNotNull(editorForFile(filePathSuffix) as? EditorEx) {
            "No extended editor found for $filePathSuffix"
        }
        editor.settings.isShowIntentionBulb = false
        editor.setCaretEnabled(false)
        editor.setCaretVisible(false)
        editor.contentComponent.repaint()
        val position = editor.caretModel.logicalPosition
        "${position.line + 1}:${position.column + 1}"
    }

    @JvmStatic
    fun activeGuideState(filePathSuffix: String): String = driverTestOnEdt {
        val editor = editorForFile(filePathSuffix) ?: return@driverTestOnEdt NO_EDITOR
        val session = EditorGuideSessions.get(editor) ?: return@driverTestOnEdt NO_SESSION
        if (session.isActiveGuideVisible) VISIBLE else HIDDEN
    }

    private fun editorForFile(filePathSuffix: String) =
        EditorFactory.getInstance().allEditors.firstOrNull { candidate ->
            if (candidate.isDisposed) return@firstOrNull false
            val file = FileDocumentManager.getInstance().getFile(candidate.document)
            file?.path?.replace('\\', '/')?.endsWith(filePathSuffix.replace('\\', '/')) == true
        }

    private fun <T> driverTestOnEdt(action: () -> T): T {
        check(System.getProperty(DRIVER_TEST_PROPERTY) == "true") {
            "$DRIVER_TEST_PROPERTY must be true; this API is reserved for visual tests"
        }
        return onEdt(action)
    }

    private fun <T> onEdt(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        var result: Result<T>? = null
        application.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private const val NO_EDITOR = "NO_EDITOR"
    private const val NO_SESSION = "NO_SESSION"
    private const val VISIBLE = "VISIBLE"
    private const val HIDDEN = "HIDDEN"
    private const val DARCULA_THEME = "Darcula"
    private const val DRIVER_TEST_PROPERTY = "bracket.pair.guides.driver.test"
}
