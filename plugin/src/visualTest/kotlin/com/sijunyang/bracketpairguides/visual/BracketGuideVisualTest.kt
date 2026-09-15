package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.ui.components.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.checkBox
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.ui.components.settingsDialog
import com.intellij.driver.sdk.ui.components.showSettings
import com.intellij.driver.sdk.ui.components.textField
import com.intellij.driver.sdk.ui.components.tree
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BracketGuideVisualTest {
    @Test
    fun coreVisualScenariosMatchExactBaselinesInOneIdeSession() {
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

        val captures = linkedMapOf<String, BufferedImage>()
        val contractFailures = mutableListOf<String>()
        val context = visualIdeContext(
            testName = "bracket-guide-visual",
            projectRoot = requiredPath("visual.test.project.dir"),
            pluginArchive = requiredPath("path.to.build.plugin"),
        )

        writeStarterPaths(
            artifacts = artifacts,
            testHome = context.paths.testHome,
            logs = context.paths.testHome.resolve("log"),
            reports = context.paths.testHome.resolve("reports"),
            snapshots = context.paths.testHome.resolve("snapshots"),
        )
        val result =
            context.runIdeWithDriver().useDriverAndCloseIde {
                val project = openVisualFixture()
                val sample = checkNotNull(findFile(SAMPLE_FILE, project))
                val bridge = utility<DriverBridge>()
                val hierarchy = service<SwingHierarchyService>()
                val rootUi = this.ui
                ideFrame {
                    val editor = codeEditor()

                    fun resetScenario(spec: ScenarioSpec) {
                        bridge.resetVisualScenario(
                            SAMPLE_FILE,
                            spec.caretLine,
                            spec.caretColumn,
                            spec.initialIndentGuidesShown,
                            muteNotifications = true,
                        )
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "visual scenario reset did not become observable; " +
                                bridge.visualScenarioState(SAMPLE_FILE),
                        ) {
                            bridge.isVisualScenarioReset(
                                SAMPLE_FILE,
                                spec.caretLine,
                                spec.caretColumn,
                                spec.initialIndentGuidesShown,
                            )
                        }
                    }

                    fun applyAndWait(scenario: String, expectedNative: NativeState? = null) {
                        val spec = SCENARIO_SPECS.getValue(scenario)
                        bridge.prepareEditorForApply(SAMPLE_FILE)
                        val expectedPreferences = bridge.apply(spec.preferences)
                        waitForCodeAnalysis(project, sample, 5.minutes)
                        waitFor(
                            1.minutes,
                            100.milliseconds,
                            "$scenario did not reach its observable presentation state; " +
                                bridge.visualScenarioState(SAMPLE_FILE),
                        ) {
                            bridge.currentVisualPreferences() == expectedPreferences &&
                                visualStateIsReady(
                                    bridge.visualScenarioState(SAMPLE_FILE),
                                    expectedNative?.let { native -> spec.copy(native = native) } ?: spec,
                                )
                        }
                    }

                    fun screenshot(spec: ScenarioSpec, rawCapture: Path? = null): BufferedImage {
                        assertTrue(
                            bridge.prepareEditorForCapture(SAMPLE_FILE) ==
                                "${spec.caretLine}:${spec.caretColumn}:false:0:0:0",
                        )
                        bridge.waitForEditorFocus()
                        return stableScreenshot(editor, rawCapture)
                    }

                    fun recordScenario(scenario: String, warmState: String? = null): BufferedImage {
                        val spec = SCENARIO_SPECS.getValue(scenario)
                        resetScenario(spec)
                        warmState?.let(::applyAndWait)
                        applyAndWait(scenario)
                        val actual = screenshot(spec, artifacts.resolve("$scenario-editor.png"))
                        writePng(actual, artifacts.resolve("$scenario-actual.png"))
                        captures[scenario] = actual
                        return actual
                    }

                    RENDERING_SCENARIOS.forEach { scenario -> recordScenario(scenario) }

                    val pluginDisabledSpec = SCENARIO_SPECS.getValue(PLUGIN_DISABLED)
                    resetScenario(pluginDisabledSpec)
                    applyAndWait(ALL_COMPONENTS, CURRENT_SCOPE_SUPPRESSED_INDENT_ENABLED)
                    val enabledBeforeDisable = screenshot(SCENARIO_SPECS.getValue(ALL_COMPONENTS))
                    applyAndWait(PLUGIN_DISABLED)
                    val pluginDisabled =
                        screenshot(pluginDisabledSpec, artifacts.resolve("$PLUGIN_DISABLED-editor.png"))
                    writePng(pluginDisabled, artifacts.resolve("$PLUGIN_DISABLED-actual.png"))
                    captures[PLUGIN_DISABLED] = pluginDisabled
                    applyAndWait(ALL_COMPONENTS, CURRENT_SCOPE_SUPPRESSED_INDENT_ENABLED)
                    val reenabled = screenshot(SCENARIO_SPECS.getValue(ALL_COMPONENTS))
                    if (!ExactImageComparison.matches(enabledBeforeDisable, reenabled)) {
                        contractFailures +=
                            "re-enabling the plugin did not reproduce the pre-disable all-components capture"
                    }

                    recordScenario(NATIVE_VISUALS_UNMANAGED)
                    recordScenario(NATIVE_HIGHLIGHT_SUPPRESSED)
                    val unmanagedSpec = SCENARIO_SPECS.getValue(NATIVE_VISUALS_UNMANAGED)
                    showSettings()
                    rootUi.settingsDialog().apply {
                        val searchField = textField("//div[@class='TextFieldWithProcessing']")
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "Settings search field was not ready",
                        ) {
                            searchField.isVisible() && searchField.isEnabled()
                        }
                        searchField.text = "Bracket Pair Guides"

                        val categories = tree("//div[@accessiblename='Settings categories']")
                        var pluginSettingsRow = -1
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "Bracket Pair Guides settings category was not found",
                        ) {
                            pluginSettingsRow =
                                categories.collectExpandedPaths()
                                    .singleOrNull {
                                        it.path.lastOrNull() == "Bracket Pair Guides"
                                    }?.row ?: -1
                            pluginSettingsRow >= 0
                        }
                        // Native macOS window focus is not guaranteed in a background test.
                        // Select the real Swing control on the EDT, as the search field does.
                        driver.withContext(OnDispatcher.EDT) {
                            cast(categories.component, SettingsTree::class).setSelectionRow(pluginSettingsRow)
                        }
                        waitFor(30.seconds, 100.milliseconds, "Bracket Pair Guides settings were not selected") {
                            categories.collectSelectedPaths().singleOrNull()?.path?.lastOrNull() ==
                                "Bracket Pair Guides"
                        }

                        val manageNativeVisuals = checkBox {
                            and(
                                byClass("JBCheckBox"),
                                byAccessibleName(MANAGE_NATIVE_VISUALS_LABEL),
                            )
                        }
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "IntelliJ Integration control was not ready",
                        ) {
                            manageNativeVisuals.present() &&
                                manageNativeVisuals.isVisible() &&
                                manageNativeVisuals.isEnabled() &&
                                manageNativeVisuals.isSelected()
                        }
                        manageNativeVisuals.activateSettingsControl()
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "IntelliJ Integration control was not cleared",
                        ) {
                            !manageNativeVisuals.isSelected()
                        }

                        val applyButton = x { byAccessibleName("Apply") }
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "Settings Apply button was not enabled",
                        ) {
                            applyButton.isEnabled()
                        }
                        applyButton.activateSettingsControl()
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "Settings Apply did not finish",
                        ) {
                            !applyButton.isEnabled()
                        }
                        x { byAccessibleName("Cancel") }.activateSettingsControl()
                        waitFor(
                            30.seconds,
                            100.milliseconds,
                            "Settings dialog did not close",
                        ) {
                            notPresent()
                        }
                    }

                    waitForExactStableScreenshot(
                        editor = editor,
                        expected = captures.getValue(NATIVE_VISUALS_UNMANAGED),
                        failureMessage =
                        "Settings Apply did not restore native visuals without editor interaction",
                    )
                    if (!visualStateIsReady(bridge.visualScenarioState(SAMPLE_FILE), unmanagedSpec)) {
                        contractFailures +=
                            "Settings Apply restored pixels but not the unmanaged editor state"
                    }

                    recordScenario(DEFAULT_PALETTE)
                    recordScenario(CUSTOM_PALETTE, warmState = DEFAULT_PALETTE)
                    if (
                        ExactImageComparison.matches(
                            captures.getValue(DEFAULT_PALETTE),
                            captures.getValue(CUSTOM_PALETTE),
                        )
                    ) {
                        contractFailures += "the default and custom palette captures are identical"
                    }

                    check(captures.keys.toList() == VISUAL_SCENARIOS) {
                        "Unexpected visual scenario order: ${captures.keys}"
                    }
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
                }
            }

        writeStarterPaths(
            artifacts = artifacts,
            testHome = context.paths.testHome,
            logs = result.runContext.logsDir,
            reports = result.runContext.reportsDir,
            snapshots = result.runContext.snapshotsDir,
        )
        verifyImages(
            actuals = VISUAL_SCENARIOS.map { name -> NamedImage(name, captures.getValue(name)) },
            artifacts = artifacts,
            baselines = baselines,
            recordBaseline = recordBaseline,
            forceBaselineOverwrite = forceBaselineOverwrite,
            contractFailures = contractFailures,
        )
    }

    /**
     * Dispatch real mouse events on the EDT without depending on OS window focus.
     * AbstractButton.doClick alone misses IntelliJ's AWT-based Settings dirty tracking.
     */
    private fun UiComponent.activateSettingsControl() {
        driver.withContext(OnDispatcher.EDT) {
            val button = cast(component, SettingsButton::class)
            for (eventId in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                val event = new(
                    SettingsMouseEvent::class,
                    component,
                    eventId,
                    System.currentTimeMillis(),
                    0,
                    component.width / 2,
                    component.height / 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                )
                button.dispatchEvent(event)
            }
        }
    }

    private fun stableScreenshot(editor: JEditorUiComponent, rawCapture: Path?): BufferedImage {
        waitFor(30.seconds, 100.milliseconds, "code editor was not visible for capture") {
            editor.component.isShowing()
        }
        var previous: BufferedImage? = null
        var stable: BufferedImage? = null
        var stableRaw: BufferedImage? = null
        waitFor(30.seconds, 250.milliseconds, "code editor screenshot did not stabilize") {
            val raw = editor.getScreenshot()
            val current = EditorScreenshotRegion.crop(raw)
            val unchanged = previous?.let { ExactImageComparison.matches(it, current) } == true
            previous = current
            if (unchanged) {
                stable = current
                stableRaw = raw
            }
            unchanged
        }
        if (rawCapture != null) writePng(checkNotNull(stableRaw), rawCapture)
        return checkNotNull(stable)
    }

    /** Waits for an exact editor result without focusing, repainting, or otherwise touching it. */
    private fun waitForExactStableScreenshot(
        editor: JEditorUiComponent,
        expected: BufferedImage,
        failureMessage: String,
    ) {
        var previousMatch: BufferedImage? = null
        waitFor(1.minutes, 250.milliseconds, failureMessage) {
            val current = EditorScreenshotRegion.crop(editor.getScreenshot())
            val matchesExpected = ExactImageComparison.matches(expected, current)
            val stable = matchesExpected &&
                previousMatch?.let { previous ->
                    ExactImageComparison.matches(previous, current)
                } == true
            previousMatch = if (matchesExpected) current else null
            stable
        }
    }

    private fun visualStateIsReady(state: String, spec: ScenarioSpec): Boolean {
        val fields = state.split(':')
        if (fields.size != VISUAL_STATE_FIELD_COUNT) return false
        val native = spec.native
        if (
            fields[0].toBooleanStrictOrNull() != native.matchedBrace ||
            fields[1].toBooleanStrictOrNull() != native.currentScope ||
            fields[2].toBooleanStrictOrNull() != native.globalIndent ||
            fields[3].toBooleanStrictOrNull() != native.editorIndent ||
            fields[4].toBooleanStrictOrNull() != spec.expectsGuide
        ) {
            return false
        }
        val pairBorders = fields[5].toIntOrNull() ?: return false
        val pairBackgrounds = fields[6].toIntOrNull() ?: return false
        val tokenDecorations = fields[7].toIntOrNull() ?: return false
        if (spec.verifyPairComponents) {
            val expectedBorders = if (spec.expectsPairBorder) EXPECTED_PAIR_DECORATIONS else 0
            val expectedBackgrounds = if (spec.expectsPairBackground) EXPECTED_PAIR_DECORATIONS else 0
            if (pairBorders != expectedBorders || pairBackgrounds != expectedBackgrounds) {
                return false
            }
        }
        if (spec.expectsTokens != (tokenDecorations > 0)) return false
        return fields[8].toIntOrNull() == spec.caretLine &&
            fields[9].toIntOrNull() == spec.caretColumn &&
            fields[10].toBooleanStrictOrNull() == false &&
            fields[11].toIntOrNull() == 0 &&
            fields[12].toIntOrNull() == 0 &&
            fields[13].toIntOrNull() == 0
    }

    private fun verifyImages(
        actuals: List<NamedImage>,
        artifacts: Path,
        baselines: Path,
        recordBaseline: Boolean,
        forceBaselineOverwrite: Boolean,
        contractFailures: List<String>,
    ) {
        if (recordBaseline) {
            actuals.forEach { actual ->
                val baseline = baselines.resolve("${actual.name}.png")
                if (forceBaselineOverwrite || !baseline.exists()) {
                    writePng(actual.image, baseline)
                }
            }
        }

        val failures = contractFailures.toMutableList()
        val expectedBaselineFiles = actuals.map { actual -> "${actual.name}.png" }.toSet()
        val actualBaselineFiles = mutableSetOf<String>()
        if (baselines.exists()) {
            Files.newDirectoryStream(baselines, "*.png").use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) }
                    .mapTo(actualBaselineFiles) { path -> path.fileName.toString() }
            }
        }
        val missingBaselineFiles = (expectedBaselineFiles - actualBaselineFiles).sorted()
        val unexpectedBaselineFiles = (actualBaselineFiles - expectedBaselineFiles).sorted()
        if (missingBaselineFiles.isNotEmpty() || unexpectedBaselineFiles.isNotEmpty()) {
            failures +=
                buildString {
                    append("baseline directory must contain exactly the scenario PNGs")
                    if (missingBaselineFiles.isNotEmpty()) {
                        append("; missing: ${missingBaselineFiles.joinToString()}")
                    }
                    if (unexpectedBaselineFiles.isNotEmpty()) {
                        append("; unexpected: ${unexpectedBaselineFiles.joinToString()}")
                    }
                }
        }
        actuals.forEach { actual ->
            val baseline = baselines.resolve("${actual.name}.png")
            if (!baseline.exists()) {
                failures +=
                    "${actual.name}: committed baseline is missing; record it explicitly with " +
                    "./gradlew :plugin:recordVisualTestBaseline"
                return@forEach
            }
            val expected = readPng(baseline)
            if (!ExactImageComparison.matches(expected, actual.image)) {
                Files.copy(
                    baseline,
                    artifacts.resolve("${actual.name}-baseline.png"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                failures += "${actual.name}: current capture differs from the exact baseline"
            }
        }
        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                separator = "\n - ",
                prefix = "Visual contract failures:\n - ",
                postfix = "\nSee ${artifacts.toAbsolutePath()}",
            ),
        )
    }

    private fun readPng(path: Path): BufferedImage = checkNotNull(ImageIO.read(path.toFile())) {
        "Could not decode PNG: $path"
    }

    private fun writePng(image: BufferedImage, target: Path) {
        target.parent?.createDirectories()
        check(ImageIO.write(image, "png", target.toFile())) { "No PNG writer for $target" }
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
              "crop": {
                "x": ${EditorScreenshotRegion.X},
                "y": ${EditorScreenshotRegion.Y},
                "width": ${EditorScreenshotRegion.WIDTH},
                "height": ${EditorScreenshotRegion.HEIGHT}
              },
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
        if (actualPlatform == MACOS_PLATFORM) return MACOS_ENVIRONMENT
        error(
            "Unsupported visual environment $os/$architecture. Linux must run in pinned Xvfb with " +
                "VISUAL_TEST_ENVIRONMENT=$LINUX_ENVIRONMENT",
        )
    }

    private fun requiredPath(property: String): Path =
        Path(checkNotNull(System.getProperty(property)) { "Missing system property $property" })

    private fun jsonString(value: String?): String = value?.let { raw ->
        "\"" +
            raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") +
            "\""
    } ?: "null"

    private data class NamedImage(val name: String, val image: BufferedImage)

    private data class NativeState(
        val matchedBrace: Boolean,
        val currentScope: Boolean,
        val globalIndent: Boolean,
        val editorIndent: Boolean,
    )

    private data class ScenarioSpec(
        val name: String,
        val preferences: PreferenceSnapshot,
        val native: NativeState,
        val caretLine: Int = CARET_LINE,
        val caretColumn: Int = CARET_COLUMN,
        val verifyPairComponents: Boolean = true,
        val initialIndentGuidesShown: Boolean = false,
    ) {
        val expectsGuide: Boolean
            get() =
                preferences.enabled &&
                    preferences.showActiveGuide &&
                    (preferences.showVerticalGuide || preferences.showHorizontalGuides)

        val expectsPairBorder: Boolean
            get() = preferences.enabled && preferences.showActivePairBorder

        val expectsPairBackground: Boolean
            get() =
                preferences.enabled &&
                    preferences.showActivePairBackground &&
                    preferences.pairBackgroundOpacityPercent > 0

        val expectsTokens: Boolean
            get() = preferences.enabled && preferences.colorBracketTokens
    }

    private companion object {
        const val VISUAL_STATE_FIELD_COUNT = 14
        const val EXPECTED_PAIR_DECORATIONS = 2
        const val MANAGE_NATIVE_VISUALS_LABEL =
            "Adjust IntelliJ guide rendering while Bracket Pair Guides is enabled"

        const val CUSTOM_BRACKET_COLORS = "65535,16732120,8191744,16747008,10980346,16726832"
        const val CUSTOM_GUIDE_COLORS = "16726832,16726832,16726832,16726832,16726832,16726832"
        const val CUSTOM_BORDER_COLORS = "3331915,3331915,3331915,3331915,3331915,3331915"
        const val CUSTOM_BACKGROUND_COLORS = "689407,689407,689407,689407,689407,689407"

        const val HORIZONTAL_ONLY = "horizontal-only"
        const val VERTICAL_ONLY = "vertical-only"
        const val PAIR_BORDER_ONLY = "pair-border-only"
        const val PAIR_BACKGROUND_ONLY = "pair-background-only"
        const val ALL_COMPONENTS = "all-components"
        const val BRACKET_COLORIZATION_OFF = "bracket-colorization-off"
        const val PLUGIN_DISABLED = "plugin-disabled"
        const val NATIVE_VISUALS_UNMANAGED = "native-visuals-unmanaged"
        const val NATIVE_HIGHLIGHT_SUPPRESSED = "native-highlight-suppressed"
        const val DEFAULT_PALETTE = "default-palette"
        const val CUSTOM_PALETTE = "custom-palette"

        val ALL_NATIVE_ENABLED =
            NativeState(
                matchedBrace = true,
                currentScope = true,
                globalIndent = true,
                editorIndent = true,
            )
        val CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED =
            NativeState(
                matchedBrace = true,
                currentScope = false,
                globalIndent = false,
                editorIndent = false,
            )
        val CURRENT_SCOPE_SUPPRESSED_INDENT_ENABLED =
            NativeState(
                matchedBrace = true,
                currentScope = false,
                globalIndent = true,
                editorIndent = true,
            )
        val MATCHED_BRACE_SUPPRESSED =
            NativeState(
                matchedBrace = false,
                currentScope = true,
                globalIndent = true,
                editorIndent = true,
            )
        val RENDERING_BASE =
            PreferenceSnapshot(
                nativeHighlightMode = SUPPRESS_CURRENT_SCOPE_ONLY,
                showVerticalGuide = false,
                showHorizontalGuides = false,
            )
        val ALL_COMPONENT_PREFERENCES =
            RENDERING_BASE.copy(
                showVerticalGuide = true,
                showHorizontalGuides = true,
                showActivePairBorder = true,
                showActivePairBackground = true,
            )
        val NATIVE_RENDERING_BASE =
            PreferenceSnapshot(
                nativeHighlightMode = SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE,
            )
        val PALETTE_PREFERENCES =
            ALL_COMPONENT_PREFERENCES.copy(
                guideLineWidth = 3,
                pairBackgroundOpacityPercent = 45,
            )

        val SCENARIO_SPECS_IN_ORDER =
            listOf(
                ScenarioSpec(
                    HORIZONTAL_ONLY,
                    RENDERING_BASE.copy(showHorizontalGuides = true),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    VERTICAL_ONLY,
                    RENDERING_BASE.copy(showVerticalGuide = true),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    PAIR_BORDER_ONLY,
                    RENDERING_BASE.copy(
                        showActivePairBorder = true,
                    ),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    PAIR_BACKGROUND_ONLY,
                    RENDERING_BASE.copy(
                        showActivePairBackground = true,
                    ),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    ALL_COMPONENTS,
                    ALL_COMPONENT_PREFERENCES,
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    BRACKET_COLORIZATION_OFF,
                    ALL_COMPONENT_PREFERENCES.copy(colorBracketTokens = false),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    PLUGIN_DISABLED,
                    ALL_COMPONENT_PREFERENCES.copy(enabled = false),
                    ALL_NATIVE_ENABLED,
                    initialIndentGuidesShown = true,
                ),
                ScenarioSpec(
                    NATIVE_VISUALS_UNMANAGED,
                    NATIVE_RENDERING_BASE.copy(manageNativeVisuals = false),
                    ALL_NATIVE_ENABLED,
                    caretLine = NATIVE_CARET_LINE,
                    caretColumn = NATIVE_CARET_COLUMN,
                    verifyPairComponents = false,
                    initialIndentGuidesShown = true,
                ),
                ScenarioSpec(
                    NATIVE_HIGHLIGHT_SUPPRESSED,
                    NATIVE_RENDERING_BASE,
                    MATCHED_BRACE_SUPPRESSED,
                    caretLine = NATIVE_CARET_LINE,
                    caretColumn = NATIVE_CARET_COLUMN,
                    verifyPairComponents = false,
                    initialIndentGuidesShown = true,
                ),
                ScenarioSpec(
                    DEFAULT_PALETTE,
                    PALETTE_PREFERENCES,
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
                ScenarioSpec(
                    CUSTOM_PALETTE,
                    PALETTE_PREFERENCES.copy(
                        useIndependentComponentColors = true,
                        levelBaseColors = CUSTOM_BRACKET_COLORS,
                        guideLineColors = CUSTOM_GUIDE_COLORS,
                        pairBorderColors = CUSTOM_BORDER_COLORS,
                        pairBackgroundColors = CUSTOM_BACKGROUND_COLORS,
                    ),
                    CURRENT_SCOPE_SUPPRESSED_INDENT_DISABLED,
                ),
            )
        val SCENARIO_SPECS = SCENARIO_SPECS_IN_ORDER.associateBy(ScenarioSpec::name)

        val RENDERING_SCENARIOS =
            listOf(
                HORIZONTAL_ONLY,
                VERTICAL_ONLY,
                PAIR_BORDER_ONLY,
                PAIR_BACKGROUND_ONLY,
                ALL_COMPONENTS,
                BRACKET_COLORIZATION_OFF,
            )
        val VISUAL_SCENARIOS =
            RENDERING_SCENARIOS +
                listOf(
                    PLUGIN_DISABLED,
                    NATIVE_VISUALS_UNMANAGED,
                    NATIVE_HIGHLIGHT_SUPPRESSED,
                    DEFAULT_PALETTE,
                    CUSTOM_PALETTE,
                )

        const val MACOS_ENVIRONMENT = "ideaIC-2024.2.6/macos-aarch64-darcula-scale1"
        const val LINUX_ENVIRONMENT = "ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1"
        const val MACOS_PLATFORM = "macos-aarch64"
        const val LINUX_PLATFORM = "linux-x64"
        val MACOS_ARCHITECTURES = setOf("aarch64", "arm64")
        val LINUX_ARCHITECTURES = setOf("amd64", "x86_64")
        val SUPPORTED_ENVIRONMENTS = setOf(MACOS_ENVIRONMENT, LINUX_ENVIRONMENT)
    }
}

@Remote("javax.swing.JTree")
private interface SettingsTree {
    fun setSelectionRow(row: Int)
}

@Remote("javax.swing.AbstractButton")
private interface SettingsButton {
    fun dispatchEvent(event: SettingsMouseEvent)
}

@Remote("java.awt.event.MouseEvent")
private interface SettingsMouseEvent
