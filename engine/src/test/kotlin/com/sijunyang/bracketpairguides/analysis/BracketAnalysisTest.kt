package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BracketAnalysisTest : BasePlatformTestCase() {
    fun testAnalyzePreservesStampAndAnswersOnlyPublicQueries() {
        val source = """
            class Sample {
                void run() {
                    call();
                }
            }
        """.trimIndent()
        myFixture.configureByText("Sample.java", source)
        val request = request(
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = true,
            ),
        )

        val result = inReadAction {
            BracketAnalysis().analyze(request, EmptyProgressIndicator())
        }

        assertSame(request.stamp, result.stamp)
        val tokens = result.visibleTokens(
            range = TextRange(0, source.length),
            focusOffset = source.indexOf("call"),
            limit = 100,
        )
        assertFalse(tokens.isCapped)
        assertTrue(tokens.size > 0)
        assertEquals(
            (0 until tokens.size).map(tokens::offsetAt).sorted(),
            (0 until tokens.size).map(tokens::offsetAt),
        )

        val activePair = checkNotNull(result.activePairAt(source.indexOf("call") + 2))
        assertEquals(source.indexOf('{', source.indexOf("run")), activePair.openOffset)
        assertEquals(source.indexOf('}', source.indexOf("call")), activePair.closeOffset)
        val guide = checkNotNull(result.guideFor(activePair))
        assertEquals(4, guide.guideColumn)
        assertEquals(activePair.closeLine, guide.anchorLine)
    }

    fun testResolveActivePairMapsTheBoundedSearchKnowledge() {
        val source = "class Sample { }"
        myFixture.configureByText("Active.java", source)
        val analysis = BracketAnalysis()
        inReadAction {
            analysis.analyze(
                request(
                    AnalysisCoverage(
                        tokens = false,
                        activePair = true,
                        guidePosition = false,
                    ),
                ),
                EmptyProgressIndicator(),
            )
        }
        val activeRequest = CaretContext(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            caretOffset = source.indexOf('{') + 1,
        )

        val resolution = inReadAction {
            analysis.resolveActivePair(activeRequest)
        }

        val pair = (resolution as? ActivePairKnowledge.Known)?.pair
        assertNotNull("A tiny warmed token stream must fit the bounded lookup", pair)
        assertEquals(source.indexOf('{'), pair?.openOffset)
        assertEquals(source.indexOf('}'), pair?.closeOffset)
    }

    fun testCoverageBuildsOnlyRequestedArtifacts() {
        val source = "class Planned { void run() { call(); } }"
        myFixture.configureByText("Planned.java", source)
        val caretOffset = source.indexOf("call") + 2
        val range = TextRange(0, source.length)

        val tokenOnly = inReadAction {
            BracketAnalysis().analyze(
                request(
                    AnalysisCoverage(
                        tokens = true,
                        activePair = false,
                        guidePosition = false,
                    ),
                ),
                EmptyProgressIndicator(),
            )
        }
        assertTrue(tokenOnly.visibleTokens(range, caretOffset, 100).size > 0)
        assertNull(tokenOnly.activePairAt(caretOffset))

        val activeOnly = inReadAction {
            BracketAnalysis().analyze(
                request(
                    AnalysisCoverage(
                        tokens = false,
                        activePair = true,
                        guidePosition = false,
                    ),
                ),
                EmptyProgressIndicator(),
            )
        }
        assertEquals(0, activeOnly.visibleTokens(range, caretOffset, 100).size)
        assertNotNull(activeOnly.activePairAt(caretOffset))

        val inactive = inReadAction {
            BracketAnalysis().analyze(
                request(
                    AnalysisCoverage(
                        tokens = false,
                        activePair = false,
                        guidePosition = false,
                    ),
                ),
                EmptyProgressIndicator(),
            )
        }
        assertEquals(0, inactive.visibleTokens(range, caretOffset, 100).size)
        assertNull(inactive.activePairAt(caretOffset))
    }

    fun testResolveActivePairPreservesAuthoritativeMissAndBudgetExhaustion() {
        myFixture.configureByText("NoPair.java", "class NoPair { }")
        val analysis = BracketAnalysis()
        val authoritativeMiss = inReadAction {
            analysis.resolveActivePair(
                CaretContext(
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    caretOffset = 0,
                ),
            )
        }
        assertEquals(ActivePairKnowledge.Known(null), authoritativeMiss)

        val deepSource = buildString {
            append("class Budget { void run() { int value = 0;")
            repeat(600) { append("value++;") }
            append("int target = value; } }")
        }
        myFixture.configureByText("Budget.java", deepSource)
        val exhausted = inReadAction {
            analysis.resolveActivePair(
                CaretContext(
                    editor = myFixture.editor,
                    fileType = myFixture.file.fileType,
                    caretOffset = deepSource.indexOf("target") + 2,
                ),
            )
        }
        assertSame(ActivePairKnowledge.Unknown, exhausted)
    }

    fun testInstalledLanguagesReturnsStableUiReadyDtos() {
        val families = BracketAnalysis().installedLanguages()

        assertTrue(families.isNotEmpty())
        assertEquals(families.map { family -> family.id }.sorted(), families.map { it.id })
        assertTrue(families.all { family -> family.id.isNotBlank() })
        assertTrue(families.all { family -> family.displayName.isNotBlank() })
        assertTrue(families.all { family -> family.memberDisplayNames.isNotEmpty() })
        val textFamily = families.single { family -> family.id == "TEXT" }
        assertTrue("Plain text" in textFamily.memberDisplayNames)
    }

    fun testLanguageFamilyDefensivelyCopiesMemberNames() {
        val members = mutableListOf("Java")
        val family = BraceLanguageFamily("JAVA", "Java", members)

        members.clear()

        assertEquals(listOf("Java"), family.memberDisplayNames)
    }

    fun testAnalyzePropagatesPlatformCancellation() {
        myFixture.configureByText(
            "Canceled.java",
            "class Canceled { void run() { call(); } }",
        )
        val delegate = EmptyProgressIndicator()
        val canceled = object : ProgressIndicator by delegate {
            override fun checkCanceled(): Unit = throw ProcessCanceledException()
        }

        try {
            inReadAction {
                BracketAnalysis().analyze(
                    request(
                        AnalysisCoverage(
                            tokens = true,
                            activePair = true,
                            guidePosition = true,
                        ),
                    ),
                    canceled,
                )
            }
            fail("Expected facade analysis to propagate cancellation")
        } catch (_: ProcessCanceledException) {
            // Expected: the facade must not translate or suppress platform cancellation.
        }
    }

    fun testRequestsDefensivelyCopyDisabledLanguageIds() {
        myFixture.configureByText("Copy.java", "class Copy { }")
        val disabled = mutableSetOf("JAVA")
        val analyzeRequest = AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
            disabledLanguageIds = disabled,
        )
        val activeRequest = CaretContext(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            caretOffset = 1,
            disabledLanguageIds = disabled,
        )

        disabled.clear()
        disabled += "KOTLIN"

        assertEquals(setOf("JAVA"), analyzeRequest.disabledLanguageIds)
        assertEquals(setOf("JAVA"), activeRequest.disabledLanguageIds)
    }

    fun testStampChecksDocumentCoverageAndLanguageSelection() {
        myFixture.configureByText("Revision.java", "class Revision { }")
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val stamp = request(coverage).stamp
        val fileType = myFixture.file.fileType

        assertTrue(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                PlainTextFileType.INSTANCE,
                coverage,
                emptySet(),
            ),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage.copy(activePair = true),
                emptySet(),
            ),
        )
        assertFalse(
            stamp.matchesCurrent(
                myFixture.editor,
                fileType,
                coverage,
                setOf("JAVA"),
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }

        assertFalse(
            stamp.matchesCurrent(myFixture.editor, fileType, coverage, emptySet()),
        )
    }

    fun testStampIgnoresTabSizeOnlyWhenGuidePositionsAreNotRequired() {
        myFixture.configureByText("Tabs.java", "class Tabs { }")
        val editor = myFixture.editor
        val originalTabSize = editor.settings.getTabSize(project)
        val tokenCoverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val tokenStamp = request(tokenCoverage).stamp
        val fileType = myFixture.file.fileType

        try {
            editor.settings.setTabSize(originalTabSize + 1)
            assertTrue(
                tokenStamp.matchesCurrent(
                    editor,
                    fileType,
                    tokenCoverage,
                    emptySet(),
                ),
            )

            val guideCoverage = AnalysisCoverage(
                tokens = false,
                activePair = true,
                guidePosition = true,
            )
            val guideStamp = request(guideCoverage).stamp
            editor.settings.setTabSize(originalTabSize + 2)
            assertFalse(
                guideStamp.matchesCurrent(
                    editor,
                    fileType,
                    guideCoverage,
                    emptySet(),
                ),
            )
        } finally {
            editor.settings.setTabSize(originalTabSize)
        }
    }

    fun testStampRejectsAReplacementHighlighter() {
        myFixture.configureByText("Highlighter.java", "class Highlighter { }")
        val editor = myFixture.editor
        val coverage = AnalysisCoverage(
            tokens = true,
            activePair = false,
            guidePosition = false,
        )
        val stamp = request(coverage).stamp
        val fileType = myFixture.file.fileType

        (editor as EditorEx).setHighlighter(
            EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE),
        )

        assertFalse(stamp.matchesCurrent(editor, fileType, coverage, emptySet()))
    }

    private fun request(coverage: AnalysisCoverage): AnalysisInput = AnalysisInput(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
    )

    private fun <T> inReadAction(action: () -> T): T =
        ReadAction.compute<T, RuntimeException>(action)
}
