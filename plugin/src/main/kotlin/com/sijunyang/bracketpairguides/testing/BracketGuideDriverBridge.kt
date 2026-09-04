package com.sijunyang.bracketpairguides.testing

import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
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
public object BracketGuideDriverBridge {
    @JvmStatic
    public fun setShowActiveGuide(enabled: Boolean): Boolean = driverTestOnEdt {
        val current = BracketGuideSettings.getInstance().options
        BracketGuideSettingsController.getInstance().applySettings(
            current.copy(showActiveGuide = enabled),
        )
        BracketGuideSettings.getInstance().options.showActiveGuide
    }

    @JvmStatic
    public fun isShowActiveGuideEnabled(): Boolean = driverTestOnEdt {
        BracketGuideSettings.getInstance().options.showActiveGuide
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    public fun applyDarculaTheme(): String = driverTestOnEdt {
        val manager = LafManager.getInstance()
        manager.autodetect = false
        val darcula = manager.installedLookAndFeels.firstOrNull { lookAndFeel ->
            lookAndFeel.name == DARCULA_THEME
        }
        checkNotNull(darcula) {
            "The pinned IDE does not provide the $DARCULA_THEME theme"
        }
        manager.currentLookAndFeel = darcula
        manager.updateUI()
        manager.currentLookAndFeel.name
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    public fun currentTheme(): String = driverTestOnEdt {
        LafManager.getInstance().currentLookAndFeel.name
    }

    @JvmStatic
    public fun configureIdeFrame(x: Int, y: Int, width: Int, height: Int): String = driverTestOnEdt {
        require(width > 0 && height > 0)
        val frame = checkNotNull(WindowManager.getInstance().findVisibleFrame()) {
            "No visible IDE frame"
        }
        frame.setBounds(x, y, width, height)
        "${frame.x}:${frame.y}:${frame.width}:${frame.height}"
    }

    @JvmStatic
    public fun configureEditorAppearance(fontName: String, fontSize: Int): String = driverTestOnEdt {
        require(fontName.isNotBlank())
        require(fontSize in 8..72)
        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.editorFontName = fontName
        scheme.editorFontSize = fontSize
        scheme.lineSpacing = 1.0f
        CodeVisionSettings.getInstance().codeVisionEnabled = false
        "${scheme.editorFontName}:${scheme.editorFontSize}:${scheme.lineSpacing}"
    }

    @JvmStatic
    public fun activeGuideState(filePathSuffix: String): String = driverTestOnEdt {
        val editor =
            EditorFactory.getInstance().allEditors.firstOrNull { candidate ->
                if (candidate.isDisposed) return@firstOrNull false
                val file = FileDocumentManager.getInstance().getFile(candidate.document)
                file?.path?.replace('\\', '/')?.endsWith(filePathSuffix.replace('\\', '/')) == true
            } ?: return@driverTestOnEdt NO_EDITOR
        val session = EditorGuideSessions.get(editor) ?: return@driverTestOnEdt NO_SESSION
        if (session.isActiveGuideVisible) VISIBLE else HIDDEN
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
