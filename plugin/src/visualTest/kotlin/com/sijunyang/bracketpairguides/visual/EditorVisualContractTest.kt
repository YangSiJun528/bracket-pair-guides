package com.sijunyang.bracketpairguides.visual

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
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
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
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
        check(Runtime.version().feature() == 25)
        check(System.getenv("VISUAL_TEST_ENVIRONMENT") == ENVIRONMENT)
        val candidateOnly = System.getProperty("visual.test.capture-candidates") == "true"
        check(!candidateOnly || System.getenv("CI") != "true")
        val artifacts = required("visual.test.artifacts.dir").also(Files::createDirectories)
        val project = required("visual.test.project.dir")
        Files.createDirectories(project.resolve("src"))
        Files.copy(Path.of("src/visualTest/testData/Contract.java"), project.resolve("src/Contract.java"), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(project.resolve("src/TabContract.java"),
            Files.readString(Path.of("src/visualTest/testData/Contract.java")).replace("class Contract", "class TabContract"))
        // A fixed source root makes highlighting independent of first-run project auto-setup.
        Files.createDirectories(project.resolve(".idea"))
        Files.writeString(project.resolve(".idea/modules.xml"), """
            <project version="4"><component name="ProjectModuleManager"><modules>
              <module fileurl="file://${'$'}PROJECT_DIR${'$'}/fixture.iml" filepath="${'$'}PROJECT_DIR${'$'}/fixture.iml" />
            </modules></component></project>
        """.trimIndent())
        Files.writeString(project.resolve("fixture.iml"), """
            <module type="JAVA_MODULE" version="4"><component name="NewModuleRootManager" inherit-compiler-output="true">
              <content url="file://${'$'}MODULE_DIR${'$'}"><sourceFolder url="file://${'$'}MODULE_DIR${'$'}/src" isTestSource="false" /></content>
              <orderEntry type="sourceFolder" forTests="false" />
            </component></module>
        """.trimIndent())
        val context = Starter.newContext(
            "editor-contract",
            TestCase(
                IdeInfo(productCode = "IU", platformPrefix = "idea", executableFileName = "idea",
                    fullName = "IntelliJ IDEA", version = "2026.2.3", getInstaller = {
                    PinnedInstaller()
                }),
                LocalProjectInfo(project),
            ),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(required("path.to.build.plugin"))
            disableStickyLines()
            // Use a prepared profile; first-session auto-trial activation changes the plugin set.
            removeMigrateConfigAndCreateStubFile()
            disableUltimateModule()
            disableAutoSetupJavaProject()
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
            openFile("src/Contract.java", currentProject, waitForCodeAnalysis = false)
            val bridge = utility<EditorContractRemote>()
            val setupDaemonBefore = bridge.daemonDiagnostics()
            Files.writeString(artifacts.resolve("setup-daemon-before.txt"), setupDaemonBefore)
            try {
                // One-file highlighting can finish while asynchronous JDK roots are still scanning.
                // The standard Driver wait observes background indicators and stable smart mode.
                waitForIndicators(currentProject, 5.minutes)
                openFile("src/Contract.java", currentProject, waitForCodeAnalysis = false)
                waitFor("Project setup did not reach committed smart analysis", 5.minutes, 100.milliseconds) {
                    bridge.daemonDiagnostics().startsWith("ready=true;")
                }
            } finally {
                Files.writeString(artifacts.resolve("setup-daemon-observed.txt"),
                    "beforeProjectReadiness:\n$setupDaemonBefore\nafterProjectReadiness:\n${bridge.daemonDiagnostics()}\n")
            }
            val nativeBefore = bridge.configure()
            ideFrame {
                val editor = codeEditor()
                val screen = Robot()
                fun capture(name: String) {
                    var previous: BufferedImage? = null
                    var stable: BufferedImage? = null
                    val initialDaemon = bridge.daemonDiagnostics()
                    var beforeDaemon = initialDaemon
                    var afterDaemon = initialDaemon
                    fun ready(state: String): Boolean = state.startsWith("ready=true;")
                    fun stamp(state: String): String = state.substringAfter(";stamp=").substringBefore(';')
                    try {
                        waitFor("Editor pixels did not stabilize: $name", 30.seconds, 250.milliseconds) {
                            beforeDaemon = bridge.daemonDiagnostics()
                            if (!ready(beforeDaemon)) {
                                previous = null
                                false
                            } else {
                                val component = editor.component
                                val raw = screen.createScreenCapture(Rectangle(component.getLocationOnScreen(),
                                    Dimension(component.width, component.height)))
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
                    waitFor("Visual contract markup not ready: $scenario", 1.minutes, 100.milliseconds) {
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
                            waitFor("Real painted guide did not deliver its native conflict advisory", 1.minutes, 100.milliseconds) {
                                val advisory = bridge.advisoryState().split(':')
                                advisory[0].toInt() > advisoryBefore && advisory[1] == "true" && advisory[2] == "false"
                            }
                        } catch (failure: Throwable) {
                            Files.writeString(artifacts.resolve("native-advisory-failure.txt"),
                                "beforeCount=$advisoryBefore\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                                    "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n${bridge.markupDiagnostics()}")
                            throw failure
                        }
                        Files.writeString(artifacts.resolve("native-advisory-observed.txt"),
                            "beforeCount=$advisoryBefore\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                                "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n${bridge.markupDiagnostics()}")
                    }
                    capture(scenario)
                    bridge.dismissAdvisory()
                }
                assertTrue(!equalPixels(checkNotNull(captures["default-palette"]), checkNotNull(captures["custom-palette"])),
                    "Changing only palette colors produced identical rendered components")

                // Exercise the registered real configurable, draft binding and Apply transaction.
                bridge.scenario("native-highlight-suppressed")
                waitFor("Managed native state not ready before opening Settings", 1.minutes, 100.milliseconds) {
                    bridge.settingsState() == "true:false:true:true" && bridge.state().split(':')[1].toInt() > 0
                }
                val advisoryBeforeSettings = bridge.advisoryState().substringBefore(':').toInt()
                bridge.openSettings()
                val settings = settingsDialog()
                settings.apply {
                    val manage = checkBox {
                        and(byClass("JBCheckBox"), byAccessibleName(MANAGE_NATIVE_LABEL))
                    }
                    waitFor("Registered integration checkbox not ready", 30.seconds, 100.milliseconds) {
                        manage.present() && manage.component.isShowing() && manage.isEnabled() && manage.isSelected()
                    }
                    waitFor("Settings focus did not hide guides and retain token colors", 30.seconds, 100.milliseconds) {
                        val state = bridge.state().split(':')
                        state[7] == "false" && state[1].toInt() == 0 && state[6].toInt() > 0 && state[9].toInt() == 0
                    }
                    manage.click()
                    waitFor("Integration checkbox draft did not change", 30.seconds, 100.milliseconds) { !manage.isSelected() }
                    assertEquals("true:false:true:true", bridge.settingsState(), "A draft toggle must not commit before Apply")
                    val apply = x { byAccessibleName("Apply") }
                    try {
                        waitFor("Settings Apply not enabled after an actual checkbox click", 30.seconds, 100.milliseconds) { apply.isEnabled() }
                    } catch (failure: Throwable) {
                        Files.writeString(artifacts.resolve("settings-draft-failure.txt"),
                            "selected=${manage.isSelected()}\napplyEnabled=${apply.isEnabled()}\n" +
                                "settings=${bridge.settingsState()}\nstate=${bridge.state()}\n")
                        throw failure
                    }
                    apply.click()
                    waitFor("Actual Settings Apply did not commit native restoration", 30.seconds, 100.milliseconds) {
                        !apply.isEnabled() && bridge.settingsState() == "false:$nativeBefore"
                    }
                    x { byAccessibleName("Cancel") }.click()
                    waitFor("Settings dialog did not close", 30.seconds, 100.milliseconds) { notPresent() }
                }
                Files.writeString(artifacts.resolve("settings-focus-before.txt"), bridge.focusDiagnostics())
                bridge.focusEditor(true)
                try {
                    waitFor("Applied integration change did not restore guides on focus alone", 1.minutes, 100.milliseconds) {
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
                    waitFor("Settings-restored native visuals did not deliver a new visible advisory episode", 1.minutes, 100.milliseconds) {
                        val advisory = bridge.advisoryState().split(':')
                        advisory[0].toInt() > advisoryBeforeSettings && advisory[1] == "true" && advisory[2] == "false"
                    }
                } catch (failure: Throwable) {
                    Files.writeString(artifacts.resolve("settings-advisory-failure.txt"),
                        "beforeCount=$advisoryBeforeSettings\nstate=${bridge.state()}\nsettings=${bridge.settingsState()}\n" +
                            "advisory(count:visible:expired:fadedIn:disposed:present:componentShowing:type)=${bridge.advisoryState()}\n${bridge.markupDiagnostics()}")
                    throw failure
                }
                bridge.dismissAdvisory()
                capture("settings-applied-unmanaged")
                assertTrue(equalPixels(checkNotNull(captures["native-visuals-unmanaged"]),
                    checkNotNull(captures.remove("settings-applied-unmanaged"))),
                    "Actual Settings Apply and focus restoration changed unmanaged pixels without a caret move")

                bridge.scenario("all-components")
                bridge.focusEditor(false)
                waitFor("Focus loss retained active guide markup", 30.seconds, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[7] == "false" && state[1].toInt() == 0 && state[6].toInt() > 0 && state[9].toInt() == 0
                }
                bridge.focusEditor(true)
                waitFor("Focus restoration did not restore guide", 30.seconds, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[7] == "true" && state[1].toInt() > 0
                }
                bridge.showEditor(false)
                waitFor("Hidden editor retained plugin markup", 30.seconds, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[8] == "false" && state[1].toInt() == 0 && state[6].toInt() == 0 && state[9].toInt() == 0
                }
                bridge.showEditor(true)
                bridge.focusEditor(true)
                waitFor("Visible editor did not resume calculation and presentation", 1.minutes, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[8] == "true" && state[7] == "true" && state[1].toInt() > 0 && state[6].toInt() > 0
                }
                Files.writeString(artifacts.resolve("caret-cycle-before.txt"),
                    "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nnativeBraceCount=${bridge.nativeBraceCount()}\n" +
                        bridge.focusDiagnostics() + "\n" + bridge.markupDiagnostics())
                try {
                    bridge.caretCycle()
                    waitFor("Caret A/B/A did not restore markup with managed native brace decorations absent", 1.minutes, 100.milliseconds) {
                        bridge.state().split(':')[1].toInt() > 0 &&
                            bridge.settingsState() == "true:false:true:true" && bridge.nativeBraceCount() == 0
                    }
                } catch (failure: Throwable) {
                    Files.writeString(artifacts.resolve("caret-cycle-failure.txt"),
                        "state=${bridge.state()}\nsettings=${bridge.settingsState()}\nnativeBraceCount=${bridge.nativeBraceCount()}\n" +
                            bridge.focusDiagnostics() + "\n" + bridge.markupDiagnostics())
                    throw failure
                } finally {
                    Files.writeString(artifacts.resolve("caret-cycle-synchronous.txt"), bridge.caretCycleDiagnostics())
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
                // Hidden tabs release their result; observe exact token restoration after fresh analysis.
                // Selection identity and hidden markup cleanup are still checked in the selection EDT turn.
                val tabObservations = mutableListOf<String>()
                fun awaitSelectedTab() {
                    waitFor("Selected tab did not restore its exact token markup", 30.seconds, 100.milliseconds) {
                        bridge.selectedTabReady()
                    }
                    tabObservations += "after-analysis:\n" + bridge.tabSwitchDiagnostics()
                }
                try {
                    tabObservations += bridge.prepareOtherTab()
                    waitFor("Second tab did not warm normal token markup", 1.minutes, 100.milliseconds) {
                        bridge.otherTabReady()
                    }
                    tabObservations += bridge.returnToOriginalTab()
                    awaitSelectedTab()
                    tabObservations += bridge.switchToOtherTab()
                    awaitSelectedTab()
                    tabObservations += bridge.returnToOriginalTab()
                    awaitSelectedTab()
                    tabObservations += bridge.closeOtherTab()
                } finally {
                    Files.writeString(artifacts.resolve("tab-switch-observed.txt"),
                        tabObservations.joinToString("\n\n") + "\nlast/current:\n" + bridge.tabSwitchDiagnostics())
                }
                bridge.focusEditor(true)
                waitFor("Tab return did not restore focused active presentation", 1.minutes, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[7] == "true" && state[8] == "true" && state[1].toInt() > 0 &&
                        state[6].toInt() > 0 && state[9].toInt() == 2 && bridge.nativeBraceCount() == 0
                }
                capture("all-components-after-tab-cycle")
                assertTrue(equalPixels(checkNotNull(captures["all-components"]),
                    checkNotNull(captures.remove("all-components-after-tab-cycle"))),
                    "Real tab A/B/A changed settled all-components pixels")

                try {
                    bridge.indentationCycle()
                } finally {
                    Files.writeString(artifacts.resolve("indentation-cycle-synchronous.txt"), bridge.indentationDiagnostics())
                }
                waitFor("Body indentation round-trip did not restore active markup and native readiness", 1.minutes, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[7] == "true" && state[8] == "true" && state[1].toInt() > 0 &&
                        state[6].toInt() > 0 && state[9].toInt() == 2 && bridge.nativeBraceCount() == 0
                }
                capture("all-components-after-indentation-cycle")
                assertTrue(equalPixels(checkNotNull(captures["all-components"]),
                    checkNotNull(captures.remove("all-components-after-indentation-cycle"))),
                    "Actual Tab/Shift+Tab round-trip changed original all-components pixels")

                Files.writeString(artifacts.resolve("edit-start.txt"), java.time.Instant.now().toString())
                val changedStamp = bridge.insertIndent()
                waitFor("Edited guide was not repaired", 1.minutes, 100.milliseconds) {
                    val state = bridge.state().split(':')
                    state[0].toLong() == changedStamp && state[1].toInt() > 0
                }
                capture("edited-geometry")
                assertTrue(!equalPixels(checkNotNull(captures["all-components"]), checkNotNull(captures["edited-geometry"])), "Closing indentation edit did not change guide geometry")
                assertEquals(nativeBefore, bridge.disable(), "Native settings ownership must be released")
                waitFor("Disable retained plugin markup", 30.seconds, 100.milliseconds) {
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
            val installed = DefaultIdeDistributionFactory.installIDE(wrapper, ideInfo.executableFileName)
            check(installed.productCode == "IU" && installed.build == "262.10968.63")
            check(Files.isSameFile(installed.installationPath, platform))
            installed.build to installed
        }
    }

    companion object {
        private const val MANAGE_NATIVE_LABEL = "Adjust IntelliJ guide rendering while Bracket Pair Guides is enabled"
        private const val ENVIRONMENT = "idea-2026.2.3/linux-x64-xvfb96-darcula-scale1"
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
    fun caretCycleDiagnostics(): String
    fun focusEditor(focused: Boolean): String
    fun focusDiagnostics(): String
    fun showEditor(visible: Boolean): String
    fun prepareOtherTab(): String
    fun otherTabReady(): Boolean
    fun returnToOriginalTab(): String
    fun switchToOtherTab(): String
    fun selectedTabReady(): Boolean
    fun tabSwitchDiagnostics(): String
    fun closeOtherTab(): String
    fun disable(): String
    fun indentationCycle(): String
    fun indentationDiagnostics(): String
    fun insertIndent(): Long
    fun state(): String
    fun nativeBraceCount(): Int
    fun markupDiagnostics(): String
    fun daemonDiagnostics(): String
}

