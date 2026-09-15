package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.utility
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Opens the actual visual-test fixture, then leaves control with the user. */
internal class ManualQaLauncher {
    @Test
    fun openForManualQa() {
        val outputRoot = Path(checkNotNull(System.getProperty("manual.qa.root"))).createDirectories()
        val output = Files.createTempDirectory(outputRoot, "session-")
        val context = visualIdeContext(
            testName = "bracket-guide-manual-${output.fileName}",
            projectRoot = output.resolve("project"),
            pluginArchive = Path(checkNotNull(System.getProperty("path.to.build.plugin"))),
        )
        output.resolve(
            "sandbox.txt",
        ).writeText("Project: ${context.resolvedProjectHome}\nIDE data: ${context.paths.testHome}\n")
        val run = context.runIdeWithDriver(runTimeout = 12.hours)
        try {
            with(run.driver) {
                openVisualFixture()
                val bridge = utility<DriverBridge>()
                bridge.resetVisualScenario(SAMPLE_FILE, CARET_LINE, CARET_COLUMN, true, muteNotifications = false)
                waitFor(30.seconds, 100.milliseconds, "Manual QA fixture reset was not applied") {
                    bridge.isVisualScenarioReset(SAMPLE_FILE, CARET_LINE, CARET_COLUMN, true)
                }
                bridge.prepareEditorForApply(SAMPLE_FILE)
                val preferences = bridge.apply(
                    PreferenceSnapshot(
                        manageNativeVisuals = false,
                        nativeHighlightMode = SUPPRESS_MATCHED_BRACE_AND_CURRENT_SCOPE,
                    ),
                )
                waitFor(1.minutes, 100.milliseconds, "Manual QA did not render plugin guides and bracket colors") {
                    val state = bridge.visualScenarioState(SAMPLE_FILE).split(':')
                    bridge.currentVisualPreferences() == preferences &&
                        state.size == 14 && state.take(5).all { it == "true" } &&
                        (state[7].toIntOrNull() ?: 0) > 0 &&
                        state[8] == CARET_LINE.toString() && state[9] == CARET_COLUMN.toString()
                }
                bridge.prepareEditorForCapture(SAMPLE_FILE)
                bridge.waitForEditorFocus()
                ideFrame {
                    check(ImageIO.write(codeEditor().getScreenshot(), "png", output.resolve("editor.png").toFile()))
                    check(ImageIO.write(getScreenshot(), "png", output.resolve("window.png").toFile()))
                }
                bridge.prepareEditorForApply(SAMPLE_FILE)
                val state = bridge.visualScenarioState(SAMPLE_FILE)
                output.resolve(
                    "ready.txt",
                ).writeText(
                    "Native settings ON; plugin guide and bracket colors present.\nState: $state\nNotifications are not muted.\n",
                )
                println("MANUAL QA READY: $output")
                println("Plugin rendering verified: $state")
                println("Close the IDE when finished. No visual scenarios or baseline comparisons are running.")
            }
            // Event-driven lifetime: wait for the user's IDE exit, not a timing-only readiness delay.
            runBlocking { run.startResult.await() }
        } catch (failure: Throwable) {
            if (run.process?.isAlive == true) run.closeIdeAndWait()
            throw failure
        } finally {
            run.driver.close()
        }
    }
}
