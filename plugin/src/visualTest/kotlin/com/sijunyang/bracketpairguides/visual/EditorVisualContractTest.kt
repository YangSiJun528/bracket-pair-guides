package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.ui.components.checkBox
import com.intellij.driver.sdk.ui.components.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Eleven distinct visual contracts plus repair, each compared with independently reviewed pixels. */
class EditorVisualContractTest {
    @Test
    fun geometryRepairAndDisableMatchReviewedBaselines() {
        check(System.getProperty("os.name").lowercase().contains("linux"))
        check(Runtime.version().feature() == 21)
        check(System.getenv("VISUAL_TEST_ENVIRONMENT") == ENVIRONMENT)
        val candidateOnly = System.getProperty("visual.test.capture-candidates") == "true"
        check(!candidateOnly || System.getenv("CI") != "true")
        val artifacts = required("visual.test.artifacts.dir").also(Files::createDirectories)
        val project = required("visual.test.project.dir")
        Files.createDirectories(project.resolve("src"))
        Files.copy(Path.of("src/visualTest/testData/Contract.java"), project.resolve("src/Contract.java"), StandardCopyOption.REPLACE_EXISTING)
        val context = Starter.newContext(
            "editor-contract",
            TestCase(
                IdeProductProvider.IC.copy(version = "2024.2.6", getInstaller = {
                    PinnedInstaller()
                }),
                LocalProjectInfo(project),
            ),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(required("path.to.build.plugin"))
            disableStickyLines()
            val options = checkNotNull(ide.patchedVMOptionsFile)
            applyVMOptionsPatch {
                withEnv("IDEA_VM_OPTIONS", options.toString())
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("ide.experimental.ui", true)
                addSystemProperty("ide.native.launcher", true)
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
                addSystemProperty("sun.java2d.uiScale", "1")
                addSystemProperty("ide.ui.scale", "1")
                addSystemProperty("awt.useSystemAAFontSettings", "on")
                addSystemProperty("swing.aatext", true)
                addSystemProperty("user.language", "en")
                addSystemProperty("user.country", "US")
                addSystemProperty("user.timezone", "UTC")
            }
        }
        val captures = linkedMapOf<String, BufferedImage>()
        val execution = context.runIdeWithDriver()
        val outcome = execution.useDriverAndCloseIde {
            waitForProjectOpen(2.minutes)
            val currentProject = singleProject()
            openFile("src/Contract.java", currentProject)
            waitForCodeAnalysis(
                currentProject,
                checkNotNull(findFile("src/Contract.java", currentProject)),
                5.minutes,
            )
            val bridge = utility<EditorContractRemote>()
            val nativeBefore = bridge.configure()
            val setupDaemonBefore = bridge.daemonDiagnostics()
            try {
                // One-file highlighting can finish while asynchronous JDK roots are still scanning.
                // The standard Driver wait observes background indicators and stable smart mode.
                waitForIndicators(currentProject, 5.minutes)
                waitForCodeAnalysis(
                    currentProject,
                    checkNotNull(findFile("src/Contract.java", currentProject)),
                    5.minutes,
                )
                waitFor(5.minutes, 100.milliseconds, "Project setup did not reach committed smart analysis") {
                    bridge.daemonDiagnostics().startsWith("ready=true;")
                }
            } finally {
                Files.writeString(artifacts.resolve("setup-daemon-observed.txt"),
                    "beforeProjectReadiness:\n$setupDaemonBefore\nafterProjectReadiness:\n${bridge.daemonDiagnostics()}\n")
            }
            val rootUi = this.ui
            ideFrame {
                val editor = codeEditor()
                fun capture(name: String) {
                    var previous: BufferedImage? = null
                    var stable: BufferedImage? = null
                    val initialDaemon = bridge.daemonDiagnostics()
                    var beforeDaemon = initialDaemon
                    var afterDaemon = initialDaemon
                    fun ready(state: String): Boolean = state.startsWith("ready=true;")
                    fun stamp(state: String): String = state.substringAfter(";stamp=").substringBefore(';')
                    try {
                        waitFor(30.seconds, 250.milliseconds, "Editor pixels did not stabilize: $name") {
                            beforeDaemon = bridge.daemonDiagnostics()
                            if (!ready(beforeDaemon)) {
                                previous = null
                                false
                            } else {
                                val raw = editor.getScreenshot()
                                check(raw.width >= 220 && raw.height >= 240)
                                val current = raw.getSubimage(0, 1, 220, 239)
                                afterDaemon = bridge.daemonDiagnostics()
                                if (!ready(afterDaemon) || stamp(beforeDaemon) != stamp(afterDaemon)) {
                                    previous = null
                                    false
                                } else {
                                    val same = previous?.let { equalPixels(it, current) } == true
                                    previous = current
                                    if (same) stable = current
                                    same
                                }
                            }
                        }
                    } finally {
                        Files.writeString(artifacts.resolve("$name-daemon-observed.txt"),
                            "initial:\n$initialDaemon\nbeforeScreenshot:\n$beforeDaemon\n" +
                                "afterScreenshot:\n$afterDaemon\nstableImageAccepted=${stable != null}\n" +
                                "editorMarkup:\n${bridge.markupDiagnostics()}\n")
                    }
                    val image = checkNotNull(stable)
                    ImageIO.write(image, "png", artifacts.resolve("$name-actual.png").toFile())
                    captures[name] = image
                }
                val scenarios = listOf(
                    "horizontal-only", "vertical-only", "pair-border-only", "pair-background-only",
                    "all-components", "bracket-colorization-off", "plugin-disabled",
                    "native-visuals-unmanaged", "native-highlight-suppressed", "default-palette", "custom-palette",
                )
                for (scenario in scenarios) {
                    val advisoryBefore = bridge.advisoryState().substringBefore(':').toInt()
                    bridge.scenario(scenario)
                    waitFor(1.minutes, 100.milliseconds, "Visual contract markup not ready: $scenario") {
                        val state = bridge.state().split(':')
                        val tokens = state[6].toInt()
                        when (scenario) {
                            "plugin-disabled" -> state[1].toInt() == 0 && tokens == 0 && state[9].toInt() == 0
                            "pair-border-only", "pair-background-only" -> tokens > 0 && state[9].toInt() == 2 && state[1].toInt() == 0
                            "bracket-colorization-off" -> state[1].toInt() > 0 && tokens == 0 && state[9].toInt() == 2
                            "horizontal-only", "vertical-only" -> state[1].toInt() > 0 && tokens > 0 && state[9].toInt() == 0
                            "all-components", "default-palette", "custom-palette" -> state[1].toInt() > 0 && tokens > 0 && state[9].toInt() == 2
                            else -> state[1].toInt() > 0 && tokens > 0
                        }
                    }
                    val state = bridge.state().split(':')
                    val native = state.subList(3, 6).joinToString(":")
                    assertEquals(
                        if (scenario in
                            listOf("plugin-disabled", "native-visuals-unmanaged")
                        ) {
                            nativeBefore
                        } else {
                            "false:true:true"
                        },
                        native,
                    )
                    if (scenario == "native-visuals-unmanaged") {
                        try {
                            waitFor(1.minutes, 100.milliseconds, "Real painted guide did not deliver its native conflict advisory") {
                                val advisory = bridge.advisoryState().split(':')
                                advisory[0].toInt() > advisoryBefore && advisory[1] == "true" && advisory[2] == "false"
                            }
                        } catch (failure: Throwable) {
                            Files.writeString(artifacts.resolve("native-advisory-failure.txt"),
                                "beforeCount=$advisoryBefore\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                                    "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n")
                            throw failure
                        }
                        Files.writeString(artifacts.resolve("native-advisory-observed.txt"),
                            "beforeCount=$advisoryBefore\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                                "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n")
                    }
                    capture(scenario)
                    bridge.dismissAdvisory()
                }
                assertTrue(!equalPixels(checkNotNull(captures["default-palette"]), checkNotNull(captures["custom-palette"])),
                    "Changing only palette colors produced identical rendered components")

                // Exercise the registered real configurable, draft binding and Apply transaction.
                bridge.scenario("native-highlight-suppressed")
                waitFor(1.minutes, 100.milliseconds, "Managed native state not ready before opening Settings") {
                    bridge.settingsState() == "true:false:true:true" && bridge.state().split(':')[1].toInt() > 0
                }
                val advisoryBeforeSettings = bridge.advisoryState().substringBefore(':').toInt()
                bridge.openSettings()
                val settings = rootUi.settingsDialog()
                settings.apply {
                    val manage = checkBox {
                        and(byClass("JBCheckBox"), byAccessibleName(MANAGE_NATIVE_LABEL))
                    }
                    waitFor(30.seconds, 100.milliseconds, "Registered integration checkbox not ready") {
                        manage.present() && manage.isVisible() && manage.isEnabled() && manage.isSelected()
                    }
                    waitFor(30.seconds, 100.milliseconds, "Settings focus did not hide guides and retain token colors") {
                        val state = bridge.state().split(':')
                        state[7] == "false" && state[1].toInt() == 0 && state[6].toInt() > 0 && state[9].toInt() == 0
                    }
                    manage.click()
                    waitFor(30.seconds, 100.milliseconds, "Integration checkbox draft did not change") { !manage.isSelected() }
                    assertEquals("true:false:true:true", bridge.settingsState(), "A draft toggle must not commit before Apply")
                    val apply = x { byAccessibleName("Apply") }
                    try {
                        waitFor(30.seconds, 100.milliseconds, "Settings Apply not enabled after an actual checkbox click") { apply.isEnabled() }
                    } catch (failure: Throwable) {
                        Files.writeString(artifacts.resolve("settings-draft-failure.txt"),
                            "selected=${manage.isSelected()}\napplyEnabled=${apply.isEnabled()}\n" +
                                "settings=${bridge.settingsState()}\nstate=${bridge.state()}\n")
                        throw failure
                    }
                    apply.click()
                    waitFor(30.seconds, 100.milliseconds, "Actual Settings Apply did not commit native restoration") {
                        !apply.isEnabled() && bridge.settingsState() == "false:$nativeBefore"
                    }
                    x { byAccessibleName("Cancel") }.click()
                    waitFor(30.seconds, 100.milliseconds, "Settings dialog did not close") { notPresent() }
                }
                Files.writeString(artifacts.resolve("settings-focus-before.txt"), bridge.focusDiagnostics())
                bridge.focusEditor(true)
                try {
                    waitFor(1.minutes, 100.milliseconds, "Applied integration change did not restore guides on focus alone") {
                        val state = bridge.state().split(':')
                        state[7] == "true" && state[1].toInt() > 0 && state[6].toInt() > 0 &&
                            bridge.settingsState() == "false:$nativeBefore"
                    }
                } catch (failure: Throwable) {
                    Files.writeString(artifacts.resolve("settings-focus-failure.txt"),
                        "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nadvisory=${bridge.advisoryState()}\n" +
                            bridge.focusDiagnostics())
                    throw failure
                }
                Files.writeString(artifacts.resolve("settings-focus-observed.txt"),
                    "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nadvisory=${bridge.advisoryState()}\n" +
                        bridge.focusDiagnostics())
                try {
                    waitFor(1.minutes, 100.milliseconds, "Settings-restored native visuals did not deliver a new visible advisory episode") {
                        val advisory = bridge.advisoryState().split(':')
                        advisory[0].toInt() > advisoryBeforeSettings && advisory[1] == "true" && advisory[2] == "false"
                    }
                } catch (failure: Throwable) {
                    Files.writeString(artifacts.resolve("settings-advisory-failure.txt"),
                        "beforeCount=$advisoryBeforeSettings\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                            "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n")
                    throw failure
                }
                bridge.dismissAdvisory()
                capture("settings-applied-unmanaged")
                assertTrue(equalPixels(checkNotNull(captures["native-visuals-unmanaged"]),
                    checkNotNull(captures.remove("settings-applied-unmanaged"))),
                    "Actual Settings Apply and focus restoration changed unmanaged pixels without a caret move")

                bridge.scenario("all-components")
                bridge.focusEditor(false)
                waitFor(30.seconds, 100.milliseconds, "Focus loss retained active guide markup") {
                    val state = bridge.state().split(':')
                    state[7] == "false" && state[1].toInt() == 0 && state[6].toInt() > 0 && state[9].toInt() == 0
                }
                bridge.focusEditor(true)
                waitFor(30.seconds, 100.milliseconds, "Focus restoration did not restore guide") {
                    val state = bridge.state().split(':')
                    state[7] == "true" && state[1].toInt() > 0
                }
                bridge.showEditor(false)
                waitFor(30.seconds, 100.milliseconds, "Hidden editor retained plugin markup") {
                    val state = bridge.state().split(':')
                    state[8] == "false" && state[1].toInt() == 0 && state[6].toInt() == 0 && state[9].toInt() == 0
                }
                bridge.showEditor(true)
                bridge.focusEditor(true)
                waitFor(1.minutes, 100.milliseconds, "Visible editor did not resume calculation and presentation") {
                    val state = bridge.state().split(':')
                    state[8] == "true" && state[7] == "true" && state[1].toInt() > 0 && state[6].toInt() > 0
                }
                Files.writeString(artifacts.resolve("caret-cycle-before.txt"),
                    "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nnativeBraceCount=${bridge.nativeBraceCount()}\n" +
                        bridge.focusDiagnostics() + "\n" + bridge.markupDiagnostics())
                bridge.caretCycle()
                try {
                    waitFor(1.minutes, 100.milliseconds, "Caret A/B/A did not restore markup with managed native brace decorations absent") {
                        bridge.state().split(':')[1].toInt() > 0 &&
                            bridge.settingsState() == "true:false:true:true" && bridge.nativeBraceCount() == 0
                    }
                } catch (failure: Throwable) {
                    Files.writeString(artifacts.resolve("caret-cycle-failure.txt"),
                        "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nnativeBraceCount=${bridge.nativeBraceCount()}\n" +
                            bridge.focusDiagnostics() + "\n" + bridge.markupDiagnostics())
                    throw failure
                }
                capture("all-components-after-caret-cycle")
                Files.writeString(artifacts.resolve("caret-cycle-observed.txt"),
                    "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nnativeBraceCount=${bridge.nativeBraceCount()}\n" +
                        bridge.focusDiagnostics() + "\n" + bridge.markupDiagnostics())
                assertTrue(
                    equalPixels(
                        checkNotNull(captures["all-components"]),
                        checkNotNull(captures.remove("all-components-after-caret-cycle")),
                    ),
                    "Caret A/B/A changed settled guide geometry",
                )
                val changedStamp = bridge.insertIndent()
                waitFor(1.minutes, 100.milliseconds, "Edited guide was not repaired") {
                    val state = bridge.state().split(':')
                    state[0].toLong() == changedStamp && state[1].toInt() > 0
                }
                capture("edited-geometry")
                assertTrue(!equalPixels(checkNotNull(captures["all-components"]), checkNotNull(captures["edited-geometry"])), "Closing indentation edit did not change guide geometry")
                assertEquals(nativeBefore, bridge.disable(), "Native settings ownership must be released")
                waitFor(30.seconds, 100.milliseconds, "Disable retained plugin markup") {
                    val state = bridge.state().split(':')
                    state[1].toInt() == 0 && state[6].toInt() == 0 && state[9].toInt() == 0
                }
            }
        }
        outcome.failureError?.let { throw it }
        assertEquals(12, captures.size)
        if (!candidateOnly) {
            val baselines = required("visual.test.baselines.dir").resolve(ENVIRONMENT)
            val failures = captures.filter { (name, actual) ->
                val file = baselines.resolve("$name.png")
                !Files.isRegularFile(file) || !equalPixels(ImageIO.read(file.toFile()), actual)
            }.keys
            assertTrue(failures.isEmpty(), "Missing/mismatched reviewed baseline: $failures; actual images: $artifacts")
        }
    }

    private fun required(name: String): Path = Path.of(checkNotNull(System.getProperty(name)) { "Missing $name" })

    private class PinnedInstaller : IdeInstaller {
        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> = withContext(Dispatchers.IO) {
            val platform = Path.of(checkNotNull(System.getProperty("visual.test.ide.path"))).toRealPath()
            val wrapper = Files.createTempDirectory(Path.of("build"), "driver-distribution-")
            Files.createSymbolicLink(wrapper.resolve("idea"), platform)
            val installed = IdeDistributionFactory.installIDE(wrapper.toFile(), ideInfo.executableFileName)
            check(installed.productCode == "IC" && installed.build == "242.26775.15")
            check(Files.isSameFile(installed.installationPath, platform))
            installed.build to installed
        }
    }

    companion object {
        private const val MANAGE_NATIVE_LABEL = "Adjust IntelliJ guide rendering while Bracket Pair Guides is enabled"
        private const val ENVIRONMENT = "ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1"
        private fun equalPixels(left: BufferedImage, right: BufferedImage): Boolean =
            left.width == right.width && left.height == right.height &&
                left.getRGB(0, 0, left.width, left.height, null, 0, left.width)
                    .contentEquals(right.getRGB(0, 0, right.width, right.height, null, 0, right.width))
    }
}

@Remote("com.sijunyang.bracketpairguides.testing.EditorContractBridge", plugin = "com.sijunyang.bracketpairguides")
internal interface EditorContractRemote {
    fun configure(): String
    fun scenario(name: String): String
    fun openSettings()
    fun settingsState(): String
    fun advisoryState(): String
    fun dismissAdvisory()
    fun caretCycle(): String
    fun focusEditor(focused: Boolean): String
    fun focusDiagnostics(): String
    fun showEditor(visible: Boolean): String
    fun disable(): String
    fun insertIndent(): Long
    fun state(): String
    fun nativeBraceCount(): Int
    fun markupDiagnostics(): String
    fun daemonDiagnostics(): String
}

