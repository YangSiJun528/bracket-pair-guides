package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val IDE_VERSION = "2024.2.6"
internal const val SAMPLE_FILE = "src/Sample.java"
internal const val FRAME_X = 100
internal const val FRAME_Y = 100
internal const val FRAME_WIDTH = 1280
internal const val FRAME_HEIGHT = 900
internal const val CARET_LINE = 7
internal const val CARET_COLUMN = 20
internal const val NATIVE_CARET_LINE = 6
internal const val NATIVE_CARET_COLUMN = 62
internal const val EDITOR_FONT = "JetBrains Mono"
internal const val EDITOR_FONT_SIZE = 14
internal const val THEME = "Darcula"
internal const val SUPPRESS_CURRENT_SCOPE_ONLY = "SUPPRESS_CURRENT_SCOPE_ONLY"
internal const val DEFAULT_COLORS = "16766720,14315734,1548287,52346,16739179,13404211"
internal const val SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE = "SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE"

/** The single project, IDE and rendering setup used by visual tests and manual QA. */
internal fun visualIdeContext(testName: String, projectRoot: Path, pluginArchive: Path): IDETestContext {
    check(Runtime.version().feature() == 21) { "The visual fixture requires Java 21" }
    val fixture = Path("src/visualTest/testData/guide-project/src/Sample.java").absolute().normalize()
    val target = projectRoot.resolve(SAMPLE_FILE)
    target.parent.createDirectories()
    Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
    return Starter.newContext(
        testName = testName,
        testCase = TestCase(IdeProductProvider.IC, LocalProjectInfo(projectRoot)).withVersion(IDE_VERSION),
    ).apply {
        PluginConfigurator(this).installPluginFromPath(pluginArchive)
        disableStickyLines()
        applyVMOptionsPatch {
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("bracket.pair.guides.driver.test", true)
            addSystemProperty("ide.experimental.ui", true)
            addSystemProperty("ide.native.launcher", true)
            addSystemProperty("ide.show.tips.on.startup.default.value", false)
            addSystemProperty("ide.mac.message.dialogs.as.sheets", false)
            addSystemProperty("ide.mac.file.chooser.native", false)
            addSystemProperty("sun.java2d.uiScale", "1")
            addSystemProperty("ide.ui.scale", "1")
            addSystemProperty("awt.useSystemAAFontSettings", "on")
            addSystemProperty("swing.aatext", true)
            addSystemProperty("user.language", "en")
            addSystemProperty("user.country", "US")
            addSystemProperty("user.timezone", "UTC")
        }
    }
}

internal fun Driver.openVisualFixture() = run {
    waitForProjectOpen(2.minutes)
    val project = singleProject()
    waitForIndicators(project, 5.minutes)
    val bridge = utility<DriverBridge>()
    check(bridge.applyDarculaTheme() == THEME)
    check(bridge.configureEditorAppearance(EDITOR_FONT, EDITOR_FONT_SIZE) == "$EDITOR_FONT:$EDITOR_FONT_SIZE:1.0")
    check(
        bridge.configureIdeFrame(FRAME_X, FRAME_Y, FRAME_WIDTH, FRAME_HEIGHT) ==
            "$FRAME_X:$FRAME_Y:$FRAME_WIDTH:$FRAME_HEIGHT",
    )
    openFile(SAMPLE_FILE, project)
    val sample = checkNotNull(findFile(SAMPLE_FILE, project)) { "Sample file was not indexed: $SAMPLE_FILE" }
    waitForCodeAnalysis(project, sample, 5.minutes)
    ideFrame {
        resize(FRAME_WIDTH, FRAME_HEIGHT)
        waitFor(30.seconds, 100.milliseconds, "IDE frame did not reach the pinned size") {
            component.width == FRAME_WIDTH && component.height == FRAME_HEIGHT
        }
        val editor = codeEditor()
        waitFor(30.seconds, 100.milliseconds, "code editor was not visible") {
            component.isShowing() && editor.component.isShowing()
        }
    }
    project
}

internal data class PreferenceSnapshot(
    val enabled: Boolean = true,
    val manageNativeVisuals: Boolean = true,
    val nativeHighlightMode: String = SUPPRESS_CURRENT_SCOPE_ONLY,
    val colorBracketTokens: Boolean = true,
    val showActiveGuide: Boolean = true,
    val showVerticalGuide: Boolean = true,
    val showHorizontalGuides: Boolean = true,
    val guideLineWidth: Int = 1,
    val guideOpacityPercent: Int = 100,
    val showActivePairBorder: Boolean = false,
    val showActivePairBackground: Boolean = false,
    val pairBackgroundOpacityPercent: Int = 22,
    val useIndependentComponentColors: Boolean = false,
    val levelBaseColors: String = DEFAULT_COLORS,
    val guideLineColors: String = DEFAULT_COLORS,
    val pairBorderColors: String = DEFAULT_COLORS,
    val pairBackgroundColors: String = DEFAULT_COLORS,
)

internal fun DriverBridge.waitForEditorFocus() {
    waitFor(30.seconds, 100.milliseconds, "The QA IDE must be foreground before capturing screen pixels") {
        isEditorWindowFocused(SAMPLE_FILE)
    }
}

internal fun DriverBridge.apply(preferences: PreferenceSnapshot): String = applyVisualPreferences(
    enabled = preferences.enabled,
    manageNativeVisuals = preferences.manageNativeVisuals,
    nativeHighlightMode = preferences.nativeHighlightMode,
    colorBracketTokens = preferences.colorBracketTokens,
    showActiveGuide = preferences.showActiveGuide,
    showVerticalGuide = preferences.showVerticalGuide,
    showHorizontalGuides = preferences.showHorizontalGuides,
    guideLineWidth = preferences.guideLineWidth,
    guideOpacityPercent = preferences.guideOpacityPercent,
    showActivePairBorder = preferences.showActivePairBorder,
    showActivePairBackground = preferences.showActivePairBackground,
    pairBackgroundOpacityPercent = preferences.pairBackgroundOpacityPercent,
    useIndependentComponentColors = preferences.useIndependentComponentColors,
    levelBaseColors = preferences.levelBaseColors,
    guideLineColors = preferences.guideLineColors,
    pairBorderColors = preferences.pairBorderColors,
    pairBackgroundColors = preferences.pairBackgroundColors,
)

@Remote(
    "com.sijunyang.bracketpairguides.testing.BracketGuideDriverBridge",
    plugin = "com.sijunyang.bracketpairguides",
)
internal interface DriverBridge {
    fun applyDarculaTheme(): String

    fun currentTheme(): String

    fun configureIdeFrame(x: Int, y: Int, width: Int, height: Int): String

    fun configureEditorAppearance(fontName: String, fontSize: Int): String

    fun resetVisualScenario(
        filePathSuffix: String,
        caretLine: Int,
        caretColumn: Int,
        initialIndentGuidesShown: Boolean,
        muteNotifications: Boolean,
    ): String

    fun isVisualScenarioReset(
        filePathSuffix: String,
        caretLine: Int,
        caretColumn: Int,
        initialIndentGuidesShown: Boolean,
    ): Boolean

    @Suppress("LongParameterList")
    fun applyVisualPreferences(
        enabled: Boolean,
        manageNativeVisuals: Boolean,
        nativeHighlightMode: String,
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
    ): String

    fun currentVisualPreferences(): String

    fun prepareEditorForApply(filePathSuffix: String): String

    fun prepareEditorForCapture(filePathSuffix: String): String

    fun isEditorWindowFocused(filePathSuffix: String): Boolean

    fun visualScenarioState(filePathSuffix: String): String
}
