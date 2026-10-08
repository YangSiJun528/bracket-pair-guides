package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.findFile
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.codeEditor
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForCodeAnalysis
import com.intellij.driver.sdk.waitForProjectOpen
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
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
        Files.copy(Path.of("src/visualTest/testData/Contract.java"), project.resolve("src/Contract.java"))
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
            ideFrame {
                val editor = codeEditor()
                fun capture(name: String) {
                    var previous: BufferedImage? = null
                    var stable: BufferedImage? = null
                    waitFor(30.seconds, 250.milliseconds, "Editor pixels did not stabilize: $name") {
                        val raw = editor.getScreenshot()
                        check(raw.width >= 220 && raw.height >= 240)
                        val current = raw.getSubimage(0, 1, 220, 239)
                        val same = previous?.let { equalPixels(it, current) } == true
                        previous = current
                        if (same) stable = current
                        same
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
                    bridge.scenario(scenario)
                    waitFor(1.minutes, 100.milliseconds, "Visual contract markup not ready: $scenario") {
                        val state = bridge.state().split(':')
                        val tokens = state[6].toInt()
                        when (scenario) {
                            "plugin-disabled" -> state[1].toInt() == 0 && tokens == 0
                            "default-palette" -> tokens > 0
                            "bracket-colorization-off" -> state[1].toInt() > 0 && tokens == 0
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
                    capture(scenario)
                }
                bridge.scenario("all-components")
                bridge.focusEditor(false)
                waitFor(30.seconds, 100.milliseconds, "Focus loss retained active guide markup") {
                    val state = bridge.state().split(':')
                    state[7] == "false" && state[1].toInt() == 0 && state[6].toInt() > 0
                }
                bridge.focusEditor(true)
                waitFor(30.seconds, 100.milliseconds, "Focus restoration did not restore guide") {
                    val state = bridge.state().split(':')
                    state[7] == "true" && state[1].toInt() > 0
                }
                bridge.showEditor(false)
                waitFor(30.seconds, 100.milliseconds, "Hidden editor retained plugin markup") {
                    val state = bridge.state().split(':')
                    state[8] == "false" && state[1].toInt() == 0 && state[6].toInt() == 0
                }
                bridge.showEditor(true)
                bridge.focusEditor(true)
                waitFor(1.minutes, 100.milliseconds, "Visible editor did not resume calculation and presentation") {
                    val state = bridge.state().split(':')
                    state[8] == "true" && state[7] == "true" && state[1].toInt() > 0 && state[6].toInt() > 0
                }
                bridge.caretCycle()
                waitFor(1.minutes, 100.milliseconds, "Caret A/B/A did not restore markup") {
                    bridge.state().split(':')[1].toInt() > 0
                }
                capture("all-components-after-caret-cycle")
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
                assertEquals(nativeBefore, bridge.disable(), "Native settings ownership must be released")
                waitFor(30.seconds, 100.milliseconds, "Disable retained plugin markup") {
                    val state = bridge.state().split(':')
                    state[1].toInt() == 0 && state[6].toInt() == 0
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
        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
            val platform = Path.of(checkNotNull(System.getProperty("visual.test.ide.path"))).toRealPath()
            val wrapper = Files.createTempDirectory(Path.of("build"), "driver-distribution-")
            Files.createSymbolicLink(wrapper.resolve("idea"), platform)
            val installed = IdeDistributionFactory.installIDE(wrapper.toFile(), ideInfo.executableFileName)
            check(installed.productCode == "IC" && installed.build == "242.26775.15")
            check(Files.isSameFile(installed.installationPath, platform))
            return installed.build to installed
        }
    }

    companion object {
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
    fun caretCycle(): String
    fun focusEditor(focused: Boolean): String
    fun showEditor(visible: Boolean): String
    fun disable(): String
    fun insertIndent(): Long
    fun state(): String
}
