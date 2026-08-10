package architecture

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSyntaxTest {
    @Test
    fun `comments and literals cannot imitate dependencies`() {
        val source = listOf(
            "package com.sijunyang.bracketpairguides.settings",
            "import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences",
            "// com.sijunyang.bracketpairguides.analysis.BracketSnapshot",
            "val literal = \"com.sijunyang.bracketpairguides.analysis.BracketSnapshot\"",
            "val template = \"${'$'}{com.sijunyang.bracketpairguides.analysis.BracketSnapshot}\"",
            "typealias Snapshot = String",
        ).joinToString("\n")

        val syntax = SourceSyntax.from(source, "kt")

        assertEquals(
            listOf(SourceStatement("com.sijunyang.bracketpairguides.settings", 1)),
            syntax.packageDeclarations,
        )
        assertEquals(
            listOf(
                SourceStatement(
                    "com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences",
                    2,
                ),
            ),
            syntax.imports,
        )
        assertEquals(listOf(5, 6), syntax.issues.map(SourceIssue::line))
        assertEquals(
            listOf(
                "Project dependency " +
                    "'com.sijunyang.bracketpairguides.analysis.BracketSnapshot' " +
                    "must use an explicit import.",
                "Production typealias is not supported by the architecture graph; " +
                    "add explicit alias resolution before using it.",
            ),
            syntax.issues.map(SourceIssue::message),
        )
    }
}
