package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.ui.components.settingsDialog
import com.intellij.driver.sdk.ui.components.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BracketGuideVisualTest {
    @Test
    fun showActiveGuideOffAndOnMatchTheirBaselines() {
        check(Runtime.version().feature() == 21) {
            "visualTest must run on Java 21, got ${Runtime.version()}"
        }
        val artifacts = requiredPath("visual.test.artifacts.dir").also(Files::createDirectories)
        val environment = visualEnvironment()
        val baselines = requiredPath("visual.test.baselines.dir").resolve(environment)
        val recordBaseline = System.getProperty("visual.test.record-baseline") == "true"
        val forceBaselineOverwrite =
            System.getProperty("visual.test.force-baseline-overwrite") == "true"
        check(!recordBaseline || System.getenv("CI") != "true") {
            "Baseline recording is forbidden when CI=true"
        }
        if (recordBaseline) baselines.createDirectories()

        val projectRoot = prepareRuntimeProject()
        val context =
            Starter.newContext(
                testName = "bracket-guide-visual",
                testCase =
                TestCase(
                    IdeProductProvider.IC,
                    LocalProjectInfo(projectRoot),
                ).withVersion(IDE_VERSION),
            ).apply {
                val pluginArchive = requiredPath("path.to.build.plugin")
                PluginConfigurator(this).installPluginFromPath(pluginArchive)
                disableStickyLines()
                applyVMOptionsPatch {
                    addSystemProperty("idea.trust.all.projects", true)
                    addSystemProperty("bracket.pair.guides.driver.test", true)
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

        writeStarterPaths(
            artifacts = artifacts,
            testHome = context.paths.testHome,
            logs = context.paths.testHome.resolve("log"),
            reports = context.paths.testHome.resolve("reports"),
            snapshots = context.paths.testHome.resolve("snapshots"),
        )
        val result =
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForProjectOpen(2.minutes)
                val project = singleProject()
                waitForIndicators(project, 5.minutes)
                val bridge = utility<DriverBridge>()
                val hierarchy = service<SwingHierarchyService>()
                assertTrue(bridge.applyDarculaTheme() == THEME)
                assertTrue(
                    bridge.configureEditorAppearance(EDITOR_FONT, EDITOR_FONT_SIZE) ==
                        "$EDITOR_FONT:$EDITOR_FONT_SIZE:1.0",
                )
                assertTrue(
                    bridge.configureIdeFrame(FRAME_X, FRAME_Y, FRAME_WIDTH, FRAME_HEIGHT) ==
                        "$FRAME_X:$FRAME_Y:$FRAME_WIDTH:$FRAME_HEIGHT",
                )
                openFile(SAMPLE_FILE, project)

                val sample = checkNotNull(findFile(SAMPLE_FILE, project)) {
                    "Sample file was not indexed: $SAMPLE_FILE"
                }
                waitForCodeAnalysis(project, sample, 5.minutes)
                val defaultNativeVisuals = bridge.nativeVisualState(SAMPLE_FILE).split(':')
                assertTrue(defaultNativeVisuals.size == 5)
                assertTrue(defaultNativeVisuals[0] == "false")
                assertTrue(defaultNativeVisuals[2] == "true")
                assertTrue(defaultNativeVisuals[3] == "true")
                assertTrue(defaultNativeVisuals[4] == "NEW_UI")
                assertTrue(bridge.setHideNativeIndentGuides(SAMPLE_FILE, true) == "false:false")
                assertTrue(bridge.setEditorIndentGuides(SAMPLE_FILE, true) == "false:true")
                assertTrue(bridge.setHideNativeIndentGuides(SAMPLE_FILE, false) == "true:true")

                ideFrame {
                    resize(FRAME_WIDTH, FRAME_HEIGHT)
                    waitFor(30.seconds, 100.milliseconds, "IDE frame did not reach the pinned size") {
                        component.width == FRAME_WIDTH && component.height == FRAME_HEIGHT
                    }
                    bridge.prepareEditorForCapture(SAMPLE_FILE)
                    assertTrue(bridge.openSettingsForCapture(SAMPLE_FILE))
                    settingsDialog {
                        try {
                            val settingsContent = content { }
                            val integrationTitle = x { byVisibleText("IntelliJ Integration") }
                            val restorationNote = x(
                                "//div[contains(@visible_text, " +
                                    "'Original IntelliJ settings are restored')]",
                            )
                            waitFor(
                                30.seconds,
                                100.milliseconds,
                                "the complete IntelliJ Integration settings group did not become visible",
                            ) {
                                isVerticallyContained(integrationTitle, restorationNote, settingsContent)
                            }
                            waitFor(
                                30.seconds,
                                100.milliseconds,
                                "the Settings dialog did not become the foreground capture window",
                            ) {
                                bridge.raiseSettingsForCapture()
                            }
                            writePng(
                                stableUiScreenshot(this),
                                artifacts.resolve("intellij-integration-settings.png"),
                            )
                        } finally {
                            assertTrue(bridge.closeSettingsAfterCapture())
                        }
                    }
                    waitForNoOpenedDialogs()
                    val editor = codeEditor()
                    editor.setCaretPosition(line = CARET_LINE, column = CARET_COLUMN)
                    waitForCodeAnalysis(project, sample, 5.minutes)
                    assertTrue(
                        bridge.prepareEditorForCapture(SAMPLE_FILE) == "$CARET_LINE:$CARET_COLUMN",
                    )
                    waitFor(
                        30.seconds,
                        100.milliseconds,
                        "IDE frame and editor did not become visible capture targets",
                    ) {
                        component.isShowing() && editor.component.isShowing()
                    }
                    assertTrue(bridge.setShowActiveGuide(true))
                    waitFor(1.minutes, 100.milliseconds, "active guide did not become visible for warm-up") {
                        bridge.activeGuideState(SAMPLE_FILE) == "VISIBLE"
                    }
                    assertTrue(
                        bridge.prepareEditorForCapture(SAMPLE_FILE) == "$CARET_LINE:$CARET_COLUMN",
                    )
                    val nativeIndentGuidesVisible = stableScreenshot(editor)
                    assertTrue(bridge.setHideNativeIndentGuides(SAMPLE_FILE, true) == "false:true")
                    assertTrue(bridge.setEditorIndentGuides(SAMPLE_FILE, false) == "false:false")
                    waitForCodeAnalysis(project, sample, 5.minutes)
                    assertTrue(
                        bridge.prepareEditorForCapture(SAMPLE_FILE) == "$CARET_LINE:$CARET_COLUMN",
                    )
                    val pluginGuideOnly = stableScreenshot(editor)
                    writePng(
                        nativeIndentGuidesVisible,
                        artifacts.resolve("native-indent-guides-visible-actual.png"),
                    )
                    writePng(
                        nativeIndentGuidesVisible,
                        artifacts.resolve("native-guide-default-suppressed-actual.png"),
                    )
                    writePng(
                        pluginGuideOnly,
                        artifacts.resolve("native-indent-guides-hidden-actual.png"),
                    )
                    writePng(
                        pluginGuideOnly,
                        artifacts.resolve("plugin-guide-only-actual.png"),
                    )
                    assertMeaningfulDifference(
                        "native indent guides visible and hidden",
                        nativeIndentGuidesVisible,
                        pluginGuideOnly,
                    )
                    assertTrue(bridge.activeGuideState(SAMPLE_FILE) == "VISIBLE")
                    assertTrue(bridge.setHideNativeIndentGuides(SAMPLE_FILE, false) == "true:false")
                    assertTrue(bridge.setEditorIndentGuides(SAMPLE_FILE, true) == "true:true")
                    waitForCodeAnalysis(project, sample, 5.minutes)
                    writeUiGeometry(
                        artifacts = artifacts,
                        frameWidth = component.width,
                        frameHeight = component.height,
                        editor = editor,
                        environment = environment,
                        theme = bridge.currentTheme(),
                    )
                    artifacts.resolve("ui-hierarchy.html").writeText(
                        hierarchy.getSwingHierarchyAsDOM(component, false),
                    )

                    warmCaptureState(bridge, editor, enabled = false, expectedState = "HIDDEN")
                    warmCaptureState(bridge, editor, enabled = true, expectedState = "VISIBLE")

                    val off = captureState(
                        bridge = bridge,
                        editor = editor,
                        enabled = false,
                        expectedState = "HIDDEN",
                        name = "show-active-guide-off",
                        artifacts = artifacts,
                    )
                    val on = captureState(
                        bridge = bridge,
                        editor = editor,
                        enabled = true,
                        expectedState = "VISIBLE",
                        name = "show-active-guide-on",
                        artifacts = artifacts,
                    )
                    assertMeaningfulDifference("actual OFF and ON", off, on)
                    verifyImages(
                        actuals =
                        listOf(
                            NamedImage("show-active-guide-off", off),
                            NamedImage("show-active-guide-on", on),
                        ),
                        artifacts = artifacts,
                        baselines = baselines,
                        recordBaseline = recordBaseline,
                        forceBaselineOverwrite = forceBaselineOverwrite,
                        environment = environment,
                    )

                    editor.setCaretPosition(
                        line = NATIVE_CONFLICT_CARET_LINE,
                        column = NATIVE_CONFLICT_CARET_COLUMN,
                    )
                    assertTrue(
                        bridge.prepareNativeOverlapForCapture(SAMPLE_FILE) ==
                            "true:true:true:true:NEW_UI",
                    )
                    waitForCodeAnalysis(project, sample, 5.minutes)
                    waitFor(
                        1.minutes,
                        100.milliseconds,
                        "active guide did not become visible for the native overlap capture",
                    ) {
                        bridge.activeGuideState(SAMPLE_FILE) == "VISIBLE"
                    }
                    assertTrue(
                        bridge.prepareEditorForCapture(SAMPLE_FILE) ==
                            "$NATIVE_CONFLICT_CARET_LINE:$NATIVE_CONFLICT_CARET_COLUMN",
                    )
                    val nativeOverlap = stableScreenshot(editor)
                    writePng(
                        nativeOverlap,
                        artifacts.resolve("native-guide-overlap-actual.png"),
                    )
                    assertMeaningfulDifference(
                        "native highlighted overlap and default suppression",
                        nativeOverlap,
                        nativeIndentGuidesVisible,
                    )

                    val nativeStateBeforeReview = bridge.nativeVisualState(SAMPLE_FILE)
                    try {
                        assertTrue(
                            bridge.showNativeGuideConflictNotificationForCapture(SAMPLE_FILE),
                        )
                        val balloon = x {
                            componentWithChild(
                                byJavaClass(NOTIFICATION_BALLOON_CLASS),
                                byAccessibleName(NOTIFICATION_TITLE),
                            )
                        }
                        val notificationTitle = balloon.x {
                            byAccessibleName(NOTIFICATION_TITLE)
                        }
                        val notificationContent = balloon.x {
                            byAccessibleName(NOTIFICATION_CONTENT)
                        }
                        val reviewSettings = balloon.x {
                            and(
                                byType(NOTIFICATION_ACTION_TYPE),
                                byVisibleText(NOTIFICATION_ACTION_TEXT),
                            )
                        }
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "native guide conflict notification did not become fully visible",
                        ) {
                            balloon.present() &&
                                balloon.component.isShowing() &&
                                notificationTitle.present() &&
                                notificationTitle.component.isShowing() &&
                                notificationContent.present() &&
                                notificationContent.component.isShowing() &&
                                reviewSettings.present() &&
                                reviewSettings.component.isShowing()
                        }
                        writePng(
                            stableUiScreenshot(this),
                            artifacts.resolve("native-guide-conflict-notification.png"),
                        )
                        writePng(
                            stableUiScreenshot(balloon),
                            artifacts.resolve("native-guide-conflict-balloon.png"),
                        )

                        reviewSettings.click()
                        settingsDialog {
                            try {
                                val settingsContent = content { }
                                val integrationTitle = x { byVisibleText("IntelliJ Integration") }
                                val unchangedMode = x {
                                    byVisibleText(NATIVE_HIGHLIGHTING_UNCHANGED_TEXT)
                                }
                                val restorationNote = x(
                                    "//div[contains(@visible_text, " +
                                        "'Original IntelliJ settings are restored')]",
                                )
                                waitFor(
                                    30.seconds,
                                    100.milliseconds,
                                    "the notification action did not open the integration settings",
                                ) {
                                    unchangedMode.present() &&
                                        unchangedMode.component.isShowing() &&
                                        isVerticallyContained(
                                            integrationTitle,
                                            restorationNote,
                                            settingsContent,
                                        )
                                }
                                waitFor(
                                    30.seconds,
                                    100.milliseconds,
                                    "the notification settings dialog was not ready for capture",
                                ) {
                                    bridge.raiseSettingsForCapture()
                                }
                                writePng(
                                    stableUiScreenshot(this),
                                    artifacts.resolve("native-guide-conflict-review-settings.png"),
                                )
                            } finally {
                                assertTrue(bridge.closeSettingsAfterCapture())
                            }
                        }
                        waitForNoOpenedDialogs()
                        assertTrue(bridge.nativeVisualState(SAMPLE_FILE) == nativeStateBeforeReview)
                    } finally {
                        runCatching {
                            bridge.expireNativeGuideConflictNotificationAfterCapture()
                        }
                    }
                }
            }

        writeStarterPaths(
            artifacts = artifacts,
            testHome = context.paths.testHome,
            logs = result.runContext.logsDir,
            reports = result.runContext.reportsDir,
            snapshots = result.runContext.snapshotsDir,
        )
    }

    private fun captureState(
        bridge: DriverBridge,
        editor: JEditorUiComponent,
        enabled: Boolean,
        expectedState: String,
        name: String,
        artifacts: Path,
    ): BufferedImage {
        assertTrue(bridge.setShowActiveGuide(enabled) == enabled)
        waitFor(1.minutes, 100.milliseconds, "active guide did not become $expectedState") {
            bridge.activeGuideState(SAMPLE_FILE) == expectedState
        }
        assertTrue(
            bridge.prepareEditorForCapture(SAMPLE_FILE) == "$CARET_LINE:$CARET_COLUMN",
        )
        val actual = stableScreenshot(editor)
        writePng(actual, artifacts.resolve("$name-actual.png"))
        return actual
    }

    private fun warmCaptureState(
        bridge: DriverBridge,
        editor: JEditorUiComponent,
        enabled: Boolean,
        expectedState: String,
    ) {
        assertTrue(bridge.setShowActiveGuide(enabled) == enabled)
        waitFor(1.minutes, 100.milliseconds, "warm-up guide did not become $expectedState") {
            bridge.activeGuideState(SAMPLE_FILE) == expectedState
        }
        assertTrue(
            bridge.prepareEditorForCapture(SAMPLE_FILE) == "$CARET_LINE:$CARET_COLUMN",
        )
        stableScreenshot(editor)
    }

    private fun stableScreenshot(editor: JEditorUiComponent): BufferedImage {
        waitFor(30.seconds, 100.milliseconds, "code editor was not visible for capture") {
            editor.component.isShowing()
        }
        var previous: BufferedImage? = null
        var stable: BufferedImage? = null
        waitFor(30.seconds, 250.milliseconds, "code editor screenshot did not stabilize") {
            val current = cropStableRegion(editor.getScreenshot())
            val unchanged = previous?.let { imagesAreEqual(it, current) } == true
            previous = current
            if (unchanged) stable = current
            unchanged
        }
        return checkNotNull(stable)
    }

    private fun stableUiScreenshot(component: UiComponent): BufferedImage {
        var previous: BufferedImage? = null
        var stable: BufferedImage? = null
        waitFor(30.seconds, 250.milliseconds, "UI screenshot did not stabilize") {
            val current = component.getScreenshot()
            val unchanged = previous?.let { imagesAreEqual(it, current) } == true
            previous = current
            if (unchanged) stable = current
            unchanged
        }
        return checkNotNull(stable)
    }

    private fun isVerticallyContained(first: UiComponent, last: UiComponent, container: UiComponent): Boolean {
        if (!first.present() || !last.present()) return false
        if (!first.component.isShowing() || !last.component.isShowing()) return false
        val containerTop = container.component.getLocationOnScreen().y
        val containerBottom = containerTop + container.component.height
        val firstTop = first.component.getLocationOnScreen().y
        val lastBottom = last.component.getLocationOnScreen().y + last.component.height
        return firstTop >= containerTop && lastBottom <= containerBottom
    }

    private fun verifyImages(
        actuals: List<NamedImage>,
        artifacts: Path,
        baselines: Path,
        recordBaseline: Boolean,
        forceBaselineOverwrite: Boolean,
        environment: String,
    ) {
        writeContactSheet(actuals.map(NamedImage::image), artifacts.resolve("actual.png"))
        val expectedPaths = actuals.map { named -> baselines.resolve("${named.name}.png") }
        if (recordBaseline) {
            val existing = expectedPaths.filter(Path::exists)
            check(forceBaselineOverwrite || existing.isEmpty()) {
                "Refusing to overwrite ${existing.joinToString()}. Add " +
                    "-PforceVisualBaselineOverwrite=true after reviewing the candidate."
            }
            actuals.zip(expectedPaths).forEach { (named, path) -> writePng(named.image, path) }
        }
        val missing = expectedPaths.filterNot(Path::exists)
        if (missing.isNotEmpty()) {
            artifacts.resolve("visual-test-metrics.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "environment": ${jsonString(environment)},
                  "comparisons": [],
                  "error": ${jsonString("missing baselines: ${missing.joinToString()}")}
                }
                """.trimIndent() + "\n",
            )
        }
        check(missing.isEmpty()) {
            "Missing visual baselines ${missing.joinToString()}. Both actual candidates are in " +
                "${artifacts.resolve("actual.png").toAbsolutePath()}. " +
                "Record it explicitly with ./gradlew :plugin:recordVisualTestBaseline"
        }

        val expected =
            actuals.zip(expectedPaths).map { (named, path) ->
                NamedImage(named.name, readPng(path))
            }
        writeContactSheet(expected.map(NamedImage::image), artifacts.resolve("expected.png"))

        val dimensionFailures = mutableListOf<String>()
        val dimensionDiffs =
            actuals.zip(expected).map { (actual, golden) ->
                if (
                    actual.image.width != golden.image.width ||
                    actual.image.height != golden.image.height
                ) {
                    val message =
                        "${actual.name}: expected ${golden.image.width}x${golden.image.height}, " +
                            "actual ${actual.image.width}x${actual.image.height}"
                    dimensionFailures += message
                    writeDimensionMismatch(
                        actual.name,
                        actual.image,
                        artifacts,
                        golden.image,
                        message,
                    )
                    readPng(artifacts.resolve("${actual.name}-diff.png"))
                } else {
                    ImageDiff.compare(golden.image, actual.image).difference
                }
            }
        if (dimensionFailures.isNotEmpty()) {
            writeContactSheet(dimensionDiffs, artifacts.resolve("diff.png"))
            writeAggregateError(
                artifacts = artifacts,
                environment = environment,
                message = dimensionFailures.joinToString("; "),
            )
            throw AssertionError(dimensionFailures.joinToString(prefix = "Image dimensions differ: "))
        }
        assertMeaningfulDifference("expected OFF and ON", expected[0].image, expected[1].image)

        val comparisons =
            actuals.zip(expected).map { (actual, golden) ->
                writePng(golden.image, artifacts.resolve("${actual.name}-expected.png"))
                val result = ImageDiff.compare(golden.image, actual.image)
                writePng(result.difference, artifacts.resolve("${actual.name}-diff.png"))
                artifacts.resolve("${actual.name}-metrics.json").writeText(result.metrics.toJson(environment))
                NamedComparison(actual.name, result)
            }
        writeContactSheet(
            comparisons.map { comparison -> comparison.result.difference },
            artifacts.resolve("diff.png"),
        )
        writeAggregateMetrics(artifacts, environment, comparisons)
        val failures = comparisons.filterNot { comparison -> comparison.result.metrics.isIdentical }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(prefix = "Visual regressions: ") { comparison ->
                "${comparison.name}=${comparison.result.metrics.changedPixels} changed pixels"
            } + ". See ${artifacts.toAbsolutePath()}",
        )
    }

    private fun writeAggregateMetrics(artifacts: Path, environment: String, comparisons: List<NamedComparison>) {
        val rows =
            comparisons.joinToString(",\n") { comparison ->
                val metrics = comparison.result.metrics
                """
                {
                  "name": ${jsonString(comparison.name)},
                  "width": ${metrics.width},
                  "height": ${metrics.height},
                  "changedPixels": ${metrics.changedPixels},
                  "totalPixels": ${metrics.totalPixels},
                  "maximumChannelDifference": ${metrics.maximumChannelDifference},
                  "meanAbsoluteChannelDifference": ${metrics.meanAbsoluteChannelDifference}
                }
                """.trimIndent().prependIndent("    ")
            }
        artifacts.resolve("visual-test-metrics.json").writeText(
            """
            {
              "schemaVersion": 1,
              "environment": ${jsonString(environment)},
              "comparisons": [
            $rows
              ]
            }
            """.trimIndent() + "\n",
        )
    }

    private fun writeAggregateError(artifacts: Path, environment: String, message: String) {
        artifacts.resolve("visual-test-metrics.json").writeText(
            """
            {
              "schemaVersion": 1,
              "environment": ${jsonString(environment)},
              "comparisons": [],
              "error": ${jsonString(message)}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun writeContactSheet(images: List<BufferedImage>, target: Path) {
        require(images.isNotEmpty())
        val width = images.sumOf(BufferedImage::getWidth)
        val height = images.maxOf(BufferedImage::getHeight)
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        sheet.createGraphics().use { graphics ->
            var x = 0
            for (image in images) {
                graphics.drawImage(image, x, 0, null)
                x += image.width
            }
        }
        writePng(sheet, target)
    }

    private fun assertMeaningfulDifference(description: String, first: BufferedImage, second: BufferedImage) {
        val metrics = ImageDiff.compare(first, second).metrics
        assertTrue(
            metrics.changedPixels >= MINIMUM_STATE_DIFFERENCE_PIXELS,
            "$description changed only ${metrics.changedPixels} pixels; the test did not observe a guide",
        )
    }

    private fun cropStableRegion(screenshot: BufferedImage): BufferedImage {
        require(screenshot.width >= CROP_WIDTH && screenshot.height >= CROP_HEIGHT) {
            "Code editor is too small for the pinned crop: ${screenshot.width}x${screenshot.height}"
        }
        // The crop keeps the nested guide and source text while excluding the scrollbar.
        // The production-side test bridge disables the blinking caret and intention bulb.
        val cropped = screenshot.getSubimage(0, 0, CROP_WIDTH, CROP_HEIGHT)
        return BufferedImage(CROP_WIDTH, CROP_HEIGHT, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().use { graphics -> graphics.drawImage(cropped, 0, 0, null) }
        }
    }

    private inline fun <T : java.awt.Graphics> T.use(action: (T) -> Unit) {
        try {
            action(this)
        } finally {
            dispose()
        }
    }

    private fun readPng(path: Path): BufferedImage = checkNotNull(ImageIO.read(path.toFile())) {
        "Could not decode PNG: $path"
    }

    private fun writeUiGeometry(
        artifacts: Path,
        frameWidth: Int,
        frameHeight: Int,
        editor: JEditorUiComponent,
        environment: String,
        theme: String,
    ) {
        val editorComponent = editor.component
        artifacts.resolve("ui-geometry.json").writeText(
            """
            {
              "environment": ${jsonString(environment)},
              "theme": ${jsonString(theme)},
              "testRuntime": ${jsonString(Runtime.version().toString())},
              "root": {
                "role": "ideFrame",
                "width": $frameWidth,
                "height": $frameHeight,
                "children": [
                  {
                    "role": "codeEditor",
                    "x": ${editorComponent.x},
                    "y": ${editorComponent.y},
                    "width": ${editorComponent.width},
                    "height": ${editorComponent.height},
                    "visible": ${editorComponent.isVisible()},
                    "showing": ${editorComponent.isShowing()}
                  }
                ]
              }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun visualEnvironment(): String {
        val explicit = System.getenv("VISUAL_TEST_ENVIRONMENT")
        val os = System.getProperty("os.name").lowercase()
        val architecture = System.getProperty("os.arch").lowercase()
        val actualPlatform =
            when {
                os.contains("mac") && architecture in MACOS_ARCHITECTURES -> MACOS_PLATFORM
                os.contains("linux") && architecture in LINUX_ARCHITECTURES -> LINUX_PLATFORM
                else -> error("Unsupported visual environment $os/$architecture")
            }
        if (explicit != null) {
            require(explicit in SUPPORTED_ENVIRONMENTS) {
                "Unsupported VISUAL_TEST_ENVIRONMENT: $explicit"
            }
            val expectedPlatform =
                when (explicit) {
                    MACOS_ENVIRONMENT -> MACOS_PLATFORM
                    LINUX_ENVIRONMENT -> LINUX_PLATFORM
                    else -> error("Unreachable visual environment: $explicit")
                }
            require(expectedPlatform == actualPlatform) {
                "VISUAL_TEST_ENVIRONMENT $explicit does not match actual platform $os/$architecture"
            }
            return explicit
        }
        if (actualPlatform == MACOS_PLATFORM) {
            return MACOS_ENVIRONMENT
        }
        error(
            "Unsupported visual environment $os/$architecture. " +
                "Linux must run in pinned Xvfb with " +
                "VISUAL_TEST_ENVIRONMENT=$LINUX_ENVIRONMENT",
        )
    }

    private fun writeDimensionMismatch(
        name: String,
        actual: BufferedImage,
        artifacts: Path,
        expected: BufferedImage,
        message: String?,
    ) {
        val difference = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = difference.createGraphics()
        try {
            graphics.color = Color.MAGENTA
            graphics.fillRect(0, 0, actual.width, actual.height)
        } finally {
            graphics.dispose()
        }
        writePng(difference, artifacts.resolve("$name-diff.png"))
        artifacts.resolve("$name-metrics.json").writeText(
            """
            {
              "error": ${jsonString(message ?: "image dimensions differ")},
              "expectedWidth": ${expected.width},
              "expectedHeight": ${expected.height},
              "actualWidth": ${actual.width},
              "actualHeight": ${actual.height}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun writeStarterPaths(
        artifacts: Path,
        testHome: Path,
        logs: Path? = null,
        reports: Path? = null,
        snapshots: Path? = null,
    ) {
        artifacts.resolve("starter-paths.json").writeText(
            """
            {
              "testHome": ${jsonString(testHome.toAbsolutePath().toString())},
              "logs": ${jsonString(logs?.toAbsolutePath()?.toString())},
              "reports": ${jsonString(reports?.toAbsolutePath()?.toString())},
              "snapshots": ${jsonString(snapshots?.toAbsolutePath()?.toString())}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun imagesAreEqual(first: BufferedImage, second: BufferedImage): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) return false
            }
        }
        return true
    }

    private fun writePng(image: BufferedImage, target: Path) {
        target.parent?.createDirectories()
        check(ImageIO.write(image, "png", target.toFile())) { "No PNG writer for $target" }
    }

    private fun requiredPath(property: String): Path =
        Path(checkNotNull(System.getProperty(property)) { "Missing system property $property" })

    private fun prepareRuntimeProject(): Path {
        val projectRoot = requiredPath("visual.test.project.dir")
        val fixture = Path("src/visualTest/testData/guide-project/src/Sample.java").absolute().normalize()
        val target = projectRoot.resolve(SAMPLE_FILE)
        target.parent.createDirectories()
        Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
        return projectRoot
    }

    private fun jsonString(value: String?): String = value?.let { raw ->
        "\"" +
            raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") +
            "\""
    } ?: "null"

    private companion object {
        const val IDE_VERSION = "2024.2.6"
        const val SAMPLE_FILE = "src/Sample.java"
        const val FRAME_X = 100
        const val FRAME_Y = 100
        const val FRAME_WIDTH = 1280
        const val FRAME_HEIGHT = 900

        // Driver line numbers are one-based: this is source line 7 (`total += inner`).
        const val CARET_LINE = 7
        const val CARET_COLUMN = 20
        const val NATIVE_CONFLICT_CARET_LINE = 6
        const val NATIVE_CONFLICT_CARET_COLUMN = 62
        const val EDITOR_FONT = "JetBrains Mono"
        const val EDITOR_FONT_SIZE = 14
        const val CROP_WIDTH = 220
        const val CROP_HEIGHT = 240
        const val MINIMUM_STATE_DIFFERENCE_PIXELS = 20L
        const val THEME = "Darcula"
        const val NOTIFICATION_TITLE = "IntelliJ guide highlighting may overlap"
        const val NOTIFICATION_CONTENT =
            "IntelliJ highlighting may draw another line beside Bracket Pair Guides. " +
                "Review the integration settings if this is unintended."
        const val NOTIFICATION_ACTION_TEXT = "Review settings"
        const val NOTIFICATION_BALLOON_CLASS = "com.intellij.ui.BalloonImpl\$MyComponent"
        const val NOTIFICATION_ACTION_TYPE = "com.intellij.ui.components.labels.LinkLabel"
        const val NATIVE_HIGHLIGHTING_UNCHANGED_TEXT = "Leave IntelliJ highlighting unchanged"
        const val MACOS_ENVIRONMENT = "ideaIC-2024.2.6/macos-aarch64-darcula-scale1"
        const val LINUX_ENVIRONMENT = "ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1"
        const val MACOS_PLATFORM = "macos-aarch64"
        const val LINUX_PLATFORM = "linux-x64"
        val MACOS_ARCHITECTURES = setOf("aarch64", "arm64")
        val LINUX_ARCHITECTURES = setOf("amd64", "x86_64")
        val SUPPORTED_ENVIRONMENTS = setOf(MACOS_ENVIRONMENT, LINUX_ENVIRONMENT)
    }

    private data class NamedImage(val name: String, val image: BufferedImage)

    private data class NamedComparison(val name: String, val result: ImageDiffResult)
}

@Remote(
    "com.sijunyang.bracketpairguides.testing.BracketGuideDriverBridge",
    plugin = "com.sijunyang.bracketpairguides",
)
private interface DriverBridge {
    fun applyDarculaTheme(): String

    fun currentTheme(): String

    fun configureIdeFrame(x: Int, y: Int, width: Int, height: Int): String

    fun setShowActiveGuide(enabled: Boolean): Boolean

    fun configureEditorAppearance(fontName: String, fontSize: Int): String

    fun prepareEditorForCapture(filePathSuffix: String): String

    fun openSettingsForCapture(filePathSuffix: String): Boolean

    fun raiseSettingsForCapture(): Boolean

    fun closeSettingsAfterCapture(): Boolean

    fun prepareNativeOverlapForCapture(filePathSuffix: String): String

    fun showNativeGuideConflictNotificationForCapture(filePathSuffix: String): Boolean

    fun expireNativeGuideConflictNotificationAfterCapture(): Boolean

    fun activeGuideState(filePathSuffix: String): String

    fun nativeVisualState(filePathSuffix: String): String

    fun setHideNativeIndentGuides(filePathSuffix: String, hidden: Boolean): String

    fun setEditorIndentGuides(filePathSuffix: String, shown: Boolean): String
}
