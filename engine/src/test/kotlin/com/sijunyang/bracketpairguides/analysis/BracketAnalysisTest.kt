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

        val outcome = inReadAction {
            BracketAnalysis().analyze(request, EmptyProgressIndicator())
        }
        val result = complete(outcome)

        assertSame(request.stamp, outcome.stamp)
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

    fun testCoverageBuildsOnlyRequestedArtifacts() {
        val source = "class Planned { void run() { call(); } }"
        myFixture.configureByText("Planned.java", source)
        val caretOffset = source.indexOf("call") + 2
        val range = TextRange(0, source.length)

        val tokenOnly = complete(inReadAction {
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
        })
        assertTrue(tokenOnly.visibleTokens(range, caretOffset, 100).size > 0)
        assertNull(tokenOnly.activePairAt(caretOffset))

        val activeOnly = complete(inReadAction {
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
        })
        assertEquals(0, activeOnly.visibleTokens(range, caretOffset, 100).size)
        assertNotNull(activeOnly.activePairAt(caretOffset))

        val inactive = complete(inReadAction {
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
        })
        assertEquals(0, inactive.visibleTokens(range, caretOffset, 100).size)
        assertNull(inactive.activePairAt(caretOffset))
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

    fun testUnavailableOutcomeRetainsTheAttemptStampWithoutAPartialSnapshot() {
        myFixture.configureByText("Unavailable.java", "class Unavailable { }")
        val request = request(
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = false,
            ),
        )

        val outcome: AnalysisOutcome = AnalysisOutcome.Unavailable(
            request.stamp,
            AnalysisLimit.PAIR_CAPACITY,
        )

        assertSame(request.stamp, outcome.stamp)
        assertEquals(
            AnalysisLimit.PAIR_CAPACITY,
            (outcome as AnalysisOutcome.Unavailable).limit,
        )
        assertFalse(outcome is AnalysisOutcome.Complete)
    }

    fun testProductPairCapacityAcceptsTheBoundaryAndRejectsTheNextPair() {
        val exactSource = buildString(200_020) {
            append("class Dense {")
            repeat(99_999) { append("{}") }
            append('}')
        }
        myFixture.configureByText("ExactPairCapacity.java", exactSource)

        val exact = analyzeCurrentTokens()

        assertTrue(exact is AnalysisOutcome.Complete)

        val overflowSource = buildString(200_022) {
            append("class Dense {")
            repeat(100_000) { append("{}") }
            append('}')
        }
        myFixture.configureByText("ExceededPairCapacity.java", overflowSource)

        val overflow = analyzeCurrentTokens()

        assertTrue(overflow is AnalysisOutcome.Unavailable)
        assertEquals(
            AnalysisLimit.PAIR_CAPACITY,
            (overflow as AnalysisOutcome.Unavailable).limit,
        )
    }

    fun testProductPendingOpenCapacityAcceptsTheBoundaryAndRejectsTheNextOpen() {
        myFixture.configureByText(
            "ExactPendingCapacity.java",
            "class Pending " + "{".repeat(50_000),
        )

        val exact = analyzeCurrentTokens()

        assertTrue(exact is AnalysisOutcome.Complete)

        myFixture.configureByText(
            "ExceededPendingCapacity.java",
            "class Pending " + "{".repeat(50_001),
        )

        val overflow = analyzeCurrentTokens()

        assertTrue(overflow is AnalysisOutcome.Unavailable)
        assertEquals(
            AnalysisLimit.PENDING_OPEN_CAPACITY,
            (overflow as AnalysisOutcome.Unavailable).limit,
        )
    }

    fun testGuideCapacityKeepsExactPairAndTokenIndexesWithoutPublishingAGuide() {
        val guideLineCount = 1_032_193
        val source = buildString(guideLineCount + 20) {
            append("class Huge {\n")
            repeat(guideLineCount - 1) { append('\n') }
            append('}')
        }
        myFixture.configureByText("Huge.java", source)
        val input = request(
            AnalysisCoverage(
                tokens = true,
                activePair = true,
                guidePosition = true,
            ),
        )
        val replacementHighlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, PlainTextFileType.INSTANCE)
        var highlighterReplaced = false
        val progressDelegate = EmptyProgressIndicator()
        val replacingProgress = object : ProgressIndicator by progressDelegate {
            override fun checkCanceled() {
                if (!highlighterReplaced) {
                    highlighterReplaced = true
                    (myFixture.editor as EditorEx).setHighlighter(replacementHighlighter)
                }
                progressDelegate.checkCanceled()
            }
        }

        val outcome = inReadAction {
            BracketAnalysis().analyze(input, replacingProgress)
        }

        assertTrue(highlighterReplaced)
        assertTrue(outcome is AnalysisOutcome.Limited)
        val limited = outcome as AnalysisOutcome.Limited
        assertSame(input.stamp, limited.stamp)
        assertEquals(AnalysisLimit.GUIDE_CAPACITY, limited.limit)
        assertEquals(
            input.coverage.copy(guidePosition = false),
            limited.snapshot.stamp.coverage,
        )
        assertTrue(input.stamp.covers(limited.snapshot.stamp))
        assertFalse(
            request(input.coverage.copy(guidePosition = false)).stamp
                .covers(limited.snapshot.stamp),
        )
        val pair = checkNotNull(limited.snapshot.activePairAt(source.indexOf('\n') + 1))
        assertNull(limited.snapshot.guideFor(pair))
        assertEquals(
            2,
            limited.snapshot.visibleTokens(
                TextRange(0, source.length),
                pair.openOffset,
                10,
            ).size,
        )
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
        val analysisInput = AnalysisInput(
            editor = myFixture.editor,
            fileType = myFixture.file.fileType,
            coverage = AnalysisCoverage(
                tokens = true,
                activePair = false,
                guidePosition = false,
            ),
            disabledLanguageIds = disabled,
        )
        disabled.clear()
        disabled += "KOTLIN"

        assertEquals(setOf("JAVA"), analysisInput.disabledLanguageIds)
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
        disabledLanguageIds = emptySet(),
    )

    private fun analyzeCurrentTokens(): AnalysisOutcome = inReadAction {
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

    private fun complete(outcome: AnalysisOutcome): BracketSnapshot {
        assertTrue("Expected complete analysis, got ${outcome.javaClass.simpleName}", outcome is AnalysisOutcome.Complete)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private fun <T> inReadAction(action: () -> T): T =
        ReadAction.compute<T, RuntimeException>(action)
}
