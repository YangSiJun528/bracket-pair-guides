package com.sijunyang.bracketpairguides.analysis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sijunyang.bracketpairguides.analysis.intellij.BracketAnalysis
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisLimit
import com.sijunyang.bracketpairguides.analysis.snapshot.AnalysisOutcome
import com.sijunyang.bracketpairguides.analysis.snapshot.BracketSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class BracketAnalysisTest : BasePlatformTestCase() {
    fun testAnalyzePreservesStampAndAnswersOnlyPublicQueries() {
        val source =
            """
            class Sample {
                void run() {
                    call();
                }
            }
            """.trimIndent()
        myFixture.configureByText("Sample.java", source)
        val request =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = true,
                ),
            )

        val outcome =
            inReadAction {
                analysis().analyze(request, EmptyProgressIndicator())
            }
        val result = complete(outcome)

        assertThat(outcome.stamp).isSameAs(request.stamp)
        assertThat(result.stamp).isSameAs(request.stamp)
        val tokens =
            result.visibleTokens(
                range = TextRange(0, source.length),
                focusOffset = source.indexOf("call"),
                limit = 100,
            )
        assertThat(tokens.isCapped).isFalse()
        assertThat(tokens.size).isPositive()
        assertThat((0 until tokens.size).map(tokens::offsetAt)).isSorted()

        val activePair = checkNotNull(result.activePairAt(source.indexOf("call") + 2))
        assertThat(activePair.openOffset).isEqualTo(source.indexOf('{', source.indexOf("run")))
        assertThat(activePair.closeOffset).isEqualTo(source.indexOf('}', source.indexOf("call")))
        val guide = checkNotNull(result.guideFor(activePair))
        assertThat(guide.guideColumn).isEqualTo(4)
        assertThat(guide.anchorLine).isEqualTo(activePair.closeLine)
    }

    fun testCoverageBuildsOnlyRequestedArtifacts() {
        val source = "class Planned { void run() { call(); } }"
        myFixture.configureByText("Planned.java", source)
        val caretOffset = source.indexOf("call") + 2
        val range = TextRange(0, source.length)

        val tokenOnly =
            complete(
                inReadAction {
                    analysis().analyze(
                        request(
                            AnalysisCoverage(
                                tokens = true,
                                activePair = false,
                                guidePosition = false,
                            ),
                        ),
                        EmptyProgressIndicator(),
                    )
                },
            )
        assertThat(tokenOnly.visibleTokens(range, caretOffset, 100).size).isPositive()
        assertThat(tokenOnly.activePairAt(caretOffset)).isNull()

        val activeOnly =
            complete(
                inReadAction {
                    analysis().analyze(
                        request(
                            AnalysisCoverage(
                                tokens = false,
                                activePair = true,
                                guidePosition = false,
                            ),
                        ),
                        EmptyProgressIndicator(),
                    )
                },
            )
        assertThat(activeOnly.visibleTokens(range, caretOffset, 100).size).isZero()
        assertThat(activeOnly.activePairAt(caretOffset)).isNotNull()

        val inactive =
            complete(
                inReadAction {
                    analysis().analyze(
                        request(
                            AnalysisCoverage(
                                tokens = false,
                                activePair = false,
                                guidePosition = false,
                            ),
                        ),
                        EmptyProgressIndicator(),
                    )
                },
            )
        assertThat(inactive.visibleTokens(range, caretOffset, 100).size).isZero()
        assertThat(inactive.activePairAt(caretOffset)).isNull()
    }

    fun testUnavailableOutcomeRetainsTheAttemptStampWithoutAPartialSnapshot() {
        myFixture.configureByText("Unavailable.java", "class Unavailable { }")
        val request =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = false,
                ),
            )

        val outcome: AnalysisOutcome =
            AnalysisOutcome.Unavailable(
                request.stamp,
                AnalysisLimit.PAIR_CAPACITY,
            )

        assertThat(outcome.stamp).isSameAs(request.stamp)
        assertThat(outcome)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
            }
    }

    fun testProductPairCapacityAcceptsTheBoundaryAndRejectsTheNextPair() {
        val exactSource =
            buildString(200_020) {
                append("class Dense {")
                repeat(99_999) { append("{}") }
                append('}')
            }
        myFixture.configureByText("ExactPairCapacity.java", exactSource)

        val exact = analyzeCurrentTokens()

        assertThat(exact).isInstanceOf(AnalysisOutcome.Complete::class.java)

        val overflowSource =
            buildString(200_022) {
                append("class Dense {")
                repeat(100_000) { append("{}") }
                append('}')
            }
        myFixture.configureByText("ExceededPairCapacity.java", overflowSource)

        val overflow = analyzeCurrentTokens()

        assertThat(overflow)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PAIR_CAPACITY)
            }
    }

    fun testProductPendingOpenCapacityAcceptsTheBoundaryAndRejectsTheNextOpen() {
        myFixture.configureByText(
            "ExactPendingCapacity.java",
            "class Pending " + "{".repeat(50_000),
        )

        val exact = analyzeCurrentTokens()

        assertThat(exact).isInstanceOf(AnalysisOutcome.Complete::class.java)

        myFixture.configureByText(
            "ExceededPendingCapacity.java",
            "class Pending " + "{".repeat(50_001),
        )

        val overflow = analyzeCurrentTokens()

        assertThat(overflow)
            .isInstanceOfSatisfying(AnalysisOutcome.Unavailable::class.java) { unavailable ->
                assertThat(unavailable.limit).isEqualTo(AnalysisLimit.PENDING_OPEN_CAPACITY)
            }
    }

    fun testGuideCapacityKeepsExactPairAndTokenIndexesWithoutPublishingAGuide() {
        val guideLineCount = 1_032_193
        val source =
            buildString(guideLineCount + 20) {
                append("class Huge {\n")
                repeat(guideLineCount - 1) { append('\n') }
                append('}')
            }
        myFixture.configureByText("Huge.java", source)
        val input =
            request(
                AnalysisCoverage(
                    tokens = true,
                    activePair = true,
                    guidePosition = true,
                ),
            )
        val replacementHighlighter =
            EditorHighlighterFactory
                .getInstance()
                .createEditorHighlighter(project, PlainTextFileType.INSTANCE)
        var highlighterReplaced = false
        val progressDelegate = EmptyProgressIndicator()
        val replacingProgress =
            object : ProgressIndicator by progressDelegate {
                override fun checkCanceled() {
                    if (!highlighterReplaced) {
                        highlighterReplaced = true
                        (myFixture.editor as EditorEx).setHighlighter(replacementHighlighter)
                    }
                    progressDelegate.checkCanceled()
                }
            }

        val outcome =
            inReadAction {
                analysis().analyze(input, replacingProgress)
            }

        assertThat(highlighterReplaced).isTrue()
        assertThat(outcome).isInstanceOf(AnalysisOutcome.Limited::class.java)
        val limited = outcome as AnalysisOutcome.Limited
        assertThat(limited.stamp).isSameAs(input.stamp)
        assertThat(limited.limit).isEqualTo(AnalysisLimit.GUIDE_CAPACITY)
        assertThat(
            limited.snapshot.stamp.coverage,
        ).isEqualTo(input.coverage.copy(guidePosition = false))
        assertThat(input.stamp.covers(limited.snapshot.stamp)).isTrue()
        assertThat(
            request(input.coverage.copy(guidePosition = false))
                .stamp
                .covers(limited.snapshot.stamp),
        ).isFalse()
        val pair = checkNotNull(limited.snapshot.activePairAt(source.indexOf('\n') + 1))
        assertThat(limited.snapshot.guideFor(pair)).isNull()
        assertThat(
            limited.snapshot
                .visibleTokens(
                    TextRange(0, source.length),
                    pair.openOffset,
                    10,
                ).size,
        ).isEqualTo(2)
    }

    fun testAnalyzePropagatesPlatformCancellation() {
        myFixture.configureByText(
            "Canceled.java",
            "class Canceled { void run() { call(); } }",
        )
        val delegate = EmptyProgressIndicator()
        val canceled =
            object : ProgressIndicator by delegate {
                override fun checkCanceled(): Unit = throw ProcessCanceledException()
            }

        assertThatThrownBy {
            inReadAction {
                analysis().analyze(
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
        }.isInstanceOf(ProcessCanceledException::class.java)
    }

    private fun request(coverage: AnalysisCoverage): AnalysisInput = AnalysisInput(
        editor = myFixture.editor,
        fileType = myFixture.file.fileType,
        coverage = coverage,
        disabledLanguageIds = emptySet(),
    )

    private fun analysis(): BracketAnalysis = BracketAnalysis()

    private fun analyzeCurrentTokens(): AnalysisOutcome = inReadAction {
        analysis().analyze(
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
        assertThat(outcome)
            .describedAs("complete analysis")
            .isInstanceOf(AnalysisOutcome.Complete::class.java)
        return (outcome as AnalysisOutcome.Complete).snapshot
    }

    private fun <T> inReadAction(action: () -> T): T = ReadAction.compute<T, RuntimeException>(action)
}
