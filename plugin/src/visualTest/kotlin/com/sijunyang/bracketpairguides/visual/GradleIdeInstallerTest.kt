package com.sijunyang.bracketpairguides.visual

import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class GradleIdeInstallerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun usesTheExactGradleInstallation() = runBlocking {
        val expected = Path(checkNotNull(System.getProperty("visual.test.ide.path")))

        val (build, ide) = GradleIdeInstaller().install(IdeProductProvider.IC)

        assertEquals("242.26775.15", build)
        assertEquals("IC", ide.productCode)
        assertTrue(Files.isSameFile(expected, ide.installationPath))
        if (SystemInfo.isMac || SystemInfo.isLinux) {
            val options = checkNotNull(ide.patchedVMOptionsFile).toAbsolutePath()
            assertTrue(options.startsWith(Path("build/starter-ide-links").toAbsolutePath()))
        }
    }

    @Test
    fun missingInstallationFailsWithoutDownloadingAReplacement() {
        val missing = temporaryDirectory.resolve("missing-ide")

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { GradleIdeInstaller(missing).install(IdeProductProvider.IC) }
        }

        assertTrue(failure.message.orEmpty().contains("Gradle IDE directory is missing"))
        assertTrue(Files.notExists(missing))
    }
}
