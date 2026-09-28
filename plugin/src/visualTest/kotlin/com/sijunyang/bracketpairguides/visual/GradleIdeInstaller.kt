package com.sijunyang.bracketpairguides.visual

import com.intellij.ide.starter.ide.IdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/** Reuses Gradle's extracted IDE without Starter downloading or copying it. */
internal class GradleIdeInstaller(
    private val platformPath: Path = Path(
        checkNotNull(System.getProperty("visual.test.ide.path")) {
            "Run the fixture through its Gradle UI-test task to resolve the IDE"
        },
    ),
) : IdeInstaller {
    override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> = withContext(Dispatchers.IO) {
        check(Files.isDirectory(platformPath)) { "Gradle IDE directory is missing: $platformPath" }
        val distributionRoot = distributionRoot()
        val ide = IdeDistributionFactory.installIDE(distributionRoot.toFile(), ideInfo.executableFileName)
        check(ide.productCode == "IC" && ide.build == "242.26775.15") {
            "Expected IntelliJ IDEA Community $IDE_VERSION (IC-242.26775.15), got ${ide.productCode}-${ide.build}"
        }
        check(Files.isSameFile(ide.installationPath, platformPath)) {
            "Starter resolved a different IDE: ${ide.installationPath}; expected $platformPath"
        }
        println("Reusing Gradle IDE: ${ide.installationPath}")
        ide.build to ide
    }

    private fun distributionRoot(): Path {
        if (!SystemInfo.isMac && !SystemInfo.isLinux) return platformPath
        // Starter writes VM options next to the IDE. Give each invocation its own
        // tiny wrapper so those writes stay outside Gradle's shared cache.
        val wrappers = Files.createDirectories(Path("build/starter-ide-links").toAbsolutePath())
        val root = Files.createTempDirectory(wrappers, "ide-")
        if (SystemInfo.isMac) {
            // Gradle flattens .app/Contents; restore the layout without copying it.
            val app = Files.createDirectory(root.resolve("IntelliJ IDEA.app"))
            Files.createSymbolicLink(app.resolve("Contents"), platformPath.toAbsolutePath())
        } else {
            Files.createSymbolicLink(root.resolve("idea"), platformPath.toAbsolutePath())
        }
        return root
    }
}
