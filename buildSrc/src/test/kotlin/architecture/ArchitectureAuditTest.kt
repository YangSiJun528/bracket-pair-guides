package architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchitectureAuditTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `forbidden package edge reports its import line`() {
        val root = temporaryFolder.root
        val sourceRoot = root.resolve("plugin/src/main")
        sourceRoot.resolve("Settings.kt").write(
            """
            package com.sijunyang.bracketpairguides.settings
            import com.sijunyang.bracketpairguides.analysis.BracketSnapshot
            """.trimIndent(),
        )

        val result = audit(root, ArchitectureSource(":plugin", sourceRoot))

        assertEquals(1, result.problems.size)
        assertEquals(2, result.problems.single().location.line)
        assertEquals(
            "storedSettings may not depend on analysisContracts: " +
                "'com.sijunyang.bracketpairguides.analysis.BracketSnapshot'.",
            result.problems.single().message,
        )
    }

    @Test
    fun `dependency bypasses and unknown packages remain visible`() {
        val root = temporaryFolder.root
        val sourceRoot = root.resolve("plugin/src/main")
        sourceRoot.resolve("Bypass.kt").write(
            """
            package com.sijunyang.bracketpairguides.settings
            import com.sijunyang.bracketpairguides.analysis.*
            val direct = com.sijunyang.bracketpairguides.analysis.BracketSnapshot
            typealias Snapshot = String
            """.trimIndent(),
        )
        sourceRoot.resolve("Unknown.kt").write(
            """
            package com.sijunyang.bracketpairguides.unregistered
            class Unknown
            """.trimIndent(),
        )

        val messages = audit(root, ArchitectureSource(":plugin", sourceRoot))
            .problems
            .map(ArchitectureProblem::message)

        assertTrue(messages.any { message -> "wildcard imports are forbidden" in message })
        assertTrue(messages.any { message -> "must use an explicit import" in message })
        assertTrue(messages.any { message -> "typealias is not supported" in message })
        assertTrue(messages.any { message -> "Unknown project package" in message })
    }

    private fun audit(root: File, source: ArchitectureSource): ArchitectureResult {
        val settings = root.resolve("settings.gradle.kts")
        settings.writeText("include(\"engine\", \"plugin\", \"benchmarks\")")
        return ArchitectureAudit(
            root = root,
            sources = listOf(source),
            actualModules = setOf(":engine", ":plugin", ":benchmarks"),
            configuredModuleDependencies = setOf(
                Triple(":plugin", ":engine", "implementation"),
                Triple(":benchmarks", ":engine", "implementation"),
            ),
            settings = settings,
        ).result()
    }

    private fun File.write(content: String) {
        parentFile.mkdirs()
        writeText(content)
    }
}
